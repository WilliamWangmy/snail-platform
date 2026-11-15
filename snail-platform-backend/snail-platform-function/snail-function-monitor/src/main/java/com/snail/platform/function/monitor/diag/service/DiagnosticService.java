package com.snail.platform.function.monitor.diag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.snail.platform.framework.core.context.LoginUserContextHolder;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.db.PageAssembler;
import com.snail.platform.function.monitor.config.MonitorProperties;
import com.snail.platform.function.monitor.diag.CheckRegistry;
import com.snail.platform.function.monitor.diag.dto.ReportQuery;
import com.snail.platform.function.monitor.diag.dto.ReportVO;
import com.snail.platform.function.monitor.diag.entity.SysMonitorDiagReport;
import com.snail.platform.function.monitor.diag.mapper.SysMonitorDiagReportMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 职责:诊断报告服务——run(同用户 10s 限频 → 并行体检 → 落库)、page、detail。
 * 上游:DiagController;下游:CheckRegistry、SysMonitorDiagReportMapper。
 * 口径:run 不挂事务(12 项检查在事务外,报告落库是单条 insert 自提交即原子,§8.2);
 * 限频用 ConcurrentHashMap 惰性清理;triggerBy 用登录人昵称快照;items JSON 手工序列化(DD-08)。
 */
@Service
public class DiagnosticService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticService.class);

    private final MonitorProperties properties;
    private final CheckRegistry checkRegistry;
    private final SysMonitorDiagReportMapper reportMapper;
    private final ObjectMapper objectMapper;

    /** 同用户上次 run 时间戳(userId → epochMs);惰性清理 */
    private final ConcurrentHashMap<Long, Long> lastRun = new ConcurrentHashMap<>();

    public DiagnosticService(MonitorProperties properties, CheckRegistry checkRegistry,
                             SysMonitorDiagReportMapper reportMapper, ObjectMapper objectMapper) {
        this.properties = properties;
        this.checkRegistry = checkRegistry;
        this.reportMapper = reportMapper;
        this.objectMapper = objectMapper;
    }

    /** 一键体检:限频 + 并行检查 + 落库,返回完整报告 */
    public ReportVO run() {
        if (!properties.isEnabled()) {
            throw BizException.badRequest("监控功能未开放");
        }
        Long userId = LoginUserContextHolder.userId();
        long now = System.currentTimeMillis();
        long rateLimitMs = properties.getDiag().getRateLimitSeconds() * 1000;
        if (userId != null) {
            Long last = lastRun.get(userId);
            if (last != null && now - last < rateLimitMs) {
                throw BizException.badRequest("诊断过于频繁，请稍后再试");
            }
            lastRun.put(userId, now);
            // 惰性清理:超过 1000 条时移除过期项
            if (lastRun.size() > 1000) {
                lastRun.entrySet().removeIf(entry -> now - entry.getValue() > 60_000);
            }
        }
        long start = System.currentTimeMillis();
        CheckRegistry.Result result = checkRegistry.runAll(
                java.time.Duration.ofMillis(properties.getDiag().getItemTimeoutMs()),
                java.time.Duration.ofMillis(properties.getDiag().getTotalTimeoutMs()));
        long costMs = System.currentTimeMillis() - start;
        String itemsJson = serializeItems(result.items());
        SysMonitorDiagReport report = new SysMonitorDiagReport();
        report.setTriggerBy(nickname());
        report.setTriggerTime(LocalDateTime.now().withNano(0));
        report.setCostMs(result.costMs());
        report.setOverall(result.overall());
        report.setItems(itemsJson);
        reportMapper.insert(report);
        return new ReportVO(report.getReportId(), report.getTriggerBy(), report.getTriggerTime(),
                report.getCostMs(), report.getOverall(), result.items().stream()
                .map(item -> new ReportVO.Item(item.key(), item.name(), item.status(),
                        item.latencyMs(), item.detail(), item.suggestion()))
                .toList());
    }

    /** 报告分页(不展开 items) */
    public PageResult<ReportVO> page(ReportQuery query) {
        LambdaQueryWrapper<SysMonitorDiagReport> wrapper = new LambdaQueryWrapper<SysMonitorDiagReport>()
                .ge(query.beginTime() != null, SysMonitorDiagReport::getTriggerTime, query.beginTime())
                .le(query.endTime() != null, SysMonitorDiagReport::getTriggerTime, query.endTime())
                .orderByDesc(SysMonitorDiagReport::getTriggerTime)
                .orderByDesc(SysMonitorDiagReport::getReportId);
        var page = reportMapper.selectPage(PageAssembler.from(query), wrapper);
        return PageResult.of(page.getRecords().stream().map(this::toSummaryVO).toList(), page.getTotal());
    }

    /** 报告详情(items JSON 回读结构化) */
    public ReportVO detail(Long reportId) {
        SysMonitorDiagReport report = reportMapper.selectById(reportId);
        if (report == null) {
            throw BizException.badRequest("报告不存在");
        }
        return new ReportVO(report.getReportId(), report.getTriggerBy(), report.getTriggerTime(),
                report.getCostMs(), report.getOverall(), deserializeItems(report.getItems()));
    }

    private ReportVO toSummaryVO(SysMonitorDiagReport report) {
        return new ReportVO(report.getReportId(), report.getTriggerBy(), report.getTriggerTime(),
                report.getCostMs(), report.getOverall(), null);
    }

    private String serializeItems(List<com.snail.platform.function.monitor.diag.DiagCheck.Item> items) {
        try {
            return objectMapper.writeValueAsString(items.stream()
                    .map(item -> new ReportVO.Item(item.key(), item.name(), item.status(),
                            item.latencyMs(), item.detail(), item.suggestion()))
                    .toList());
        } catch (Exception e) {
            log.error("monitor 诊断 items 序列化失败: {}", e.getMessage());
            return "[]";
        }
    }

    private List<ReportVO.Item> deserializeItems(String itemsJson) {
        if (itemsJson == null || itemsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(itemsJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ReportVO.Item.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private String nickname() {
        var user = LoginUserContextHolder.get();
        if (user != null && user.nickname() != null) {
            return user.nickname();
        }
        return user == null ? null : user.username();
    }
}
