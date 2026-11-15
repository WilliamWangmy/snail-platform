package com.snail.platform.function.monitor.alert.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.snail.platform.framework.core.context.LoginUserContextHolder;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.db.PageAssembler;
import com.snail.platform.function.monitor.alert.dto.AlertDetailVO;
import com.snail.platform.function.monitor.alert.dto.AlertQuery;
import com.snail.platform.function.monitor.alert.dto.AlertVO;
import com.snail.platform.function.monitor.alert.dto.RuleVO;
import com.snail.platform.function.monitor.alert.entity.SysMonitorAlert;
import com.snail.platform.function.monitor.alert.entity.SysMonitorAlertRule;
import com.snail.platform.function.monitor.alert.mapper.SysMonitorAlertMapper;
import com.snail.platform.function.monitor.alert.mapper.SysMonitorAlertRuleMapper;
import com.snail.platform.function.monitor.metric.service.MetricService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 职责:告警查询与操作——active(开放态,级别权重+首触倒序)、page(历史分页)、detail(规则快照+series+通知记录)、
 * ack(幂等)、silence(覆盖式写规则 silence_until)。
 * 上游:AlertController;下游:SysMonitorAlertMapper、SysMonitorAlertRuleMapper、MetricService(series 派生)。
 * 口径:ack 重复调用返回 ok(resolved 亦 ok,幂等口径);silence 1-1440 分钟覆盖式;
 * series 按 rule.metric + first~last 从采样表派生(DD-10),超过指标保留期(7 天)为空数组并标 seriesExpired;
 * @Transactional 只挂 ack/silence 写路径(住 .service,DD-25)。
 */
@Service
public class AlertService {

    /** 级别权重:critical 最前 */
    private static final Map<String, Integer> LEVEL_WEIGHT = Map.of("critical", 0, "warning", 1, "info", 2);

    /** series 最大点数(DD-10) */
    private static final int SERIES_MAX_POINTS = 200;

    private final SysMonitorAlertMapper alertMapper;
    private final SysMonitorAlertRuleMapper ruleMapper;
    private final MetricService metricService;
    private final ObjectMapper objectMapper;

    public AlertService(SysMonitorAlertMapper alertMapper, SysMonitorAlertRuleMapper ruleMapper,
                        MetricService metricService, ObjectMapper objectMapper) {
        this.alertMapper = alertMapper;
        this.ruleMapper = ruleMapper;
        this.metricService = metricService;
        this.objectMapper = objectMapper;
    }

    /** 开放告警(firing/acked):级别权重 + first_time 倒序,不分页 */
    public List<AlertVO> active() {
        List<SysMonitorAlert> alerts = alertMapper.selectList(new LambdaQueryWrapper<SysMonitorAlert>()
                .in(SysMonitorAlert::getStatus, List.of("firing", "acked")));
        Map<Long, SysMonitorAlertRule> rules = currentRules(alerts);
        return alerts.stream()
                .map(a -> toVO(a, rules.get(a.getRuleId())))
                .sorted(Comparator
                        .comparing((AlertVO vo) -> LEVEL_WEIGHT.getOrDefault(vo.level(), 3))
                        .thenComparing(AlertVO::firstTime, Comparator.reverseOrder()))
                .toList();
    }

    /** 历史分页:status/level/ruleId/时间窗,first_time 倒序 */
    public PageResult<AlertVO> page(AlertQuery query) {
        LambdaQueryWrapper<SysMonitorAlert> wrapper = new LambdaQueryWrapper<SysMonitorAlert>()
                .eq(query.status() != null && !query.status().isBlank(), SysMonitorAlert::getStatus, query.status())
                .eq(query.level() != null && !query.level().isBlank(), SysMonitorAlert::getLevel, query.level())
                .eq(query.ruleId() != null, SysMonitorAlert::getRuleId, query.ruleId())
                .ge(query.beginTime() != null, SysMonitorAlert::getFirstTime, query.beginTime())
                .le(query.endTime() != null, SysMonitorAlert::getFirstTime, query.endTime())
                .orderByDesc(SysMonitorAlert::getFirstTime)
                .orderByDesc(SysMonitorAlert::getAlertId);
        var page = alertMapper.selectPage(PageAssembler.from(query), wrapper);
        Map<Long, SysMonitorAlertRule> rules = currentRules(page.getRecords());
        return PageResult.of(page.getRecords().stream().map(a -> toVO(a, rules.get(a.getRuleId()))).toList(),
                page.getTotal());
    }

    /** 详情:告警 + 规则当前快照(已删则 null)+ series + notify_log 解析 */
    public AlertDetailVO detail(Long alertId) {
        SysMonitorAlert alert = alertMapper.selectById(alertId);
        if (alert == null) {
            throw BizException.badRequest("告警不存在");
        }
        SysMonitorAlertRule rule = ruleMapper.selectById(alert.getRuleId());
        RuleVO ruleVO = rule == null ? null : new RuleVO(rule.getRuleId(), rule.getName(), rule.getMetric(),
                rule.getComparator(), rule.getThreshold(), rule.getDurationPoints(), rule.getLevel(),
                rule.getEnabled(), rule.getIsSystem(), rule.getSilenceUntil(), rule.getRemark(),
                rule.getCreateBy(), rule.getCreateTime(), rule.getUpdateTime());
        boolean expired = isExpired(alert);
        List<AlertDetailVO.SeriesPoint> series = expired || rule == null ? List.of()
                : metricService.rawPoints(rule.getMetric(), alert.getFirstTime(), alert.getLastTime(),
                        SERIES_MAX_POINTS).stream()
                        .map(p -> new AlertDetailVO.SeriesPoint(p.t(), p.v()))
                        .toList();
        return new AlertDetailVO(toVO(alert, rule), ruleVO, series, parseNotifyLog(alert), expired);
    }

    /** 确认:firing→acked;acked/resolved 幂等返回 ok;告警不存在 400 */
    @Transactional
    public void ack(Long alertId) {
        SysMonitorAlert alert = alertMapper.selectById(alertId);
        if (alert == null) {
            throw BizException.badRequest("告警不存在");
        }
        if ("firing".equals(alert.getStatus())) {
            SysMonitorAlert patch = new SysMonitorAlert();
            patch.setAlertId(alertId);
            patch.setStatus("acked");
            patch.setAckBy(nickname());
            patch.setAckTime(LocalDateTime.now().withNano(0));
            alertMapper.updateById(patch);
        }
        // acked/resolved:无操作,幂等口径
    }

    /** 静默:覆盖式写规则 silence_until = now + minutes;返回截止时间 */
    @Transactional
    public LocalDateTime silence(Long alertId, Integer minutes) {
        if (minutes == null || minutes < 1 || minutes > 1440) {
            throw BizException.badRequest("静默时长 1-1440 分钟");
        }
        SysMonitorAlert alert = alertMapper.selectById(alertId);
        if (alert == null) {
            throw BizException.badRequest("告警不存在");
        }
        LocalDateTime until = LocalDateTime.now().withNano(0).plusMinutes(minutes);
        ruleMapper.updateSilenceUntil(alert.getRuleId(), until);
        return until;
    }

    // ---------- 内部 ----------

    private Map<Long, SysMonitorAlertRule> currentRules(List<SysMonitorAlert> alerts) {
        List<Long> ruleIds = alerts.stream().map(SysMonitorAlert::getRuleId).distinct().toList();
        if (ruleIds.isEmpty()) {
            return Map.of();
        }
        return ruleMapper.selectByIds(ruleIds).stream()
                .collect(Collectors.toMap(SysMonitorAlertRule::getRuleId, Function.identity()));
    }

    private boolean isExpired(SysMonitorAlert alert) {
        LocalDateTime retentionBoundary = LocalDateTime.now().withNano(0).minusDays(7);
        return alert.getLastTime() != null && alert.getLastTime().isBefore(retentionBoundary);
    }

    private List<AlertDetailVO.NotifyRecord> parseNotifyLog(SysMonitorAlert alert) {
        if (alert.getNotifyLog() == null || alert.getNotifyLog().isBlank()) {
            return List.of();
        }
        try {
            List<AlertDetailVO.NotifyRecord> records = objectMapper.readValue(alert.getNotifyLog(),
                    objectMapper.getTypeFactory().constructCollectionType(ArrayList.class, AlertDetailVO.NotifyRecord.class));
            return records;
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

    private AlertVO toVO(SysMonitorAlert alert, SysMonitorAlertRule rule) {
        return new AlertVO(alert.getAlertId(), alert.getRuleId(), alert.getRuleName(), alert.getMetric(),
                alert.getLevel(), alert.getStatus(), alert.getTriggerValue(), alert.getThreshold(),
                alert.getComparator(), alert.getFirstTime(), alert.getLastTime(), alert.getResolveTime(),
                alert.getAckBy(), alert.getAckTime(), rule == null ? null : rule.getSilenceUntil());
    }
}
