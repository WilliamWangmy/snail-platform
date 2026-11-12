package com.snail.platform.function.system.log.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.snail.platform.framework.core.context.TenantContextHolder;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.db.PageAssembler;
import com.snail.platform.framework.excel.ExcelExporter;
import com.snail.platform.framework.security.authority.AuthorityChecker;
import com.snail.platform.function.system.log.dto.LogCleanRequest;
import com.snail.platform.function.system.log.dto.OperLogDetailVO;
import com.snail.platform.function.system.log.dto.OperLogExportRow;
import com.snail.platform.function.system.log.dto.OperLogListVO;
import com.snail.platform.function.system.log.dto.OperLogQuery;
import com.snail.platform.function.system.log.entity.SysOperLog;
import com.snail.platform.function.system.log.mapper.SysOperLogMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 职责:操作日志服务面——分页筛选、详情取证、xlsx 导出、历史清除。sys_oper_log 在租户拦截器 ignore 清单,
 * 租户边界由本服务显式钉死:无 platform:operlog:all 者一律锁本租户(请求里的 tenantId 参数一概不认),
 * 持有该码者可按租户筛,不筛即跨租户全量。
 * 上游:OperLogController;下游:SysOperLogMapper、ExcelExporter。
 * 口径:无更新路径,唯一写动作是按保留期清除历史(不可逆,分批执行);导出行数超上限即拒,引导缩小时间范围而不是悄悄截断。
 */
@Service
public class OperLogService {

    /** 跨租户判定权限码,平台面唯一口径 */
    private static final String PLATFORM_ALL = "platform:operlog:all";

    /** 导出行数上限,超出拒绝 */
    private static final long EXPORT_ROW_LIMIT = 50_000L;

    /** 清除单批行数:历史可能上千万行,一次 DELETE 会长时间锁表,分批跑每批独立提交摊薄压力 */
    private static final int CLEAN_BATCH = 1_000;

    /** 操作日志表只读 */
    private final SysOperLogMapper operLogMapper;

    public OperLogService(SysOperLogMapper operLogMapper) {
        this.operLogMapper = operLogMapper;
    }

    /**
     * 分页:谓词见 buildWrapper,排序恒按自增 ID 倒序——新记录顶到最前,同毫秒并发写入也不因 oper_time 并列而漂页。
     */
    public PageResult<OperLogListVO> page(OperLogQuery query) {
        Page<SysOperLog> page = PageAssembler.from(query);
        IPage<SysOperLog> result = operLogMapper.selectPage(page,
                buildWrapper(query).orderByDesc(SysOperLog::getId));
        return PageResult.of(result.getRecords().stream().map(OperLogListVO::of).toList(), result.getTotal());
    }

    /** 详情:非平台租户跨租户取行按"不存在"处理——日志的存在性本身是租户隐私 */
    public OperLogDetailVO detail(Long id) {
        SysOperLog log = operLogMapper.selectById(id);
        if (log == null || (!isPlatform() && !Objects.equals(TenantContextHolder.get(), log.getTenantId()))) {
            throw BizException.badRequest("日志不存在");
        }
        return OperLogDetailVO.of(log);
    }

    /**
     * 导出:先数后取,超上限直接拒——悄悄截断会给审计人一份"看起来完整"的文件;
     * 两次查询共用同一套谓词,文件里的就是页面上看到的;last 拼的是常量上限,不是请求参数。
     * 导出本身被 @OperLog 打标:谁把多少行审计数据带走,也要留在审计里。
     */
    public void export(HttpServletResponse response, OperLogQuery query) throws IOException {
        Long counted = operLogMapper.selectCount(buildWrapper(query));
        if (counted > EXPORT_ROW_LIMIT) {
            throw BizException.badRequest("导出行数超过 5 万,请缩小时间范围");
        }
        List<SysOperLog> rows = operLogMapper.selectList(
                buildWrapper(query)
                        .orderByDesc(SysOperLog::getId)
                        .last("LIMIT " + EXPORT_ROW_LIMIT));
        ExcelExporter.write(response, "操作日志", OperLogExportRow.class,
                rows.stream().map(OperLogExportRow::of).toList());
    }

    /**
     * 清除保留期之外的历史日志:时间锚点恒为服务端时钟,1m/6m/1y 三档由 LogCleanRequest 的白名单正则锁死;
     * 租户边界与查询同口径(pinTenant)——平台侧不传 tenantId 即跨租户全量,页面有二次确认兜底。
     * 删除按 CLEAN_BATCH 分批循环,每批独立提交、不挂长事务(千万级历史一把 DELETE 会锁死业务);
     * 半途失败的语义是"已删的批次不回滚、重试继续删",对清除动作这是可接受的进度语义。
     * 返回实际删除行数,给操作人一个可核对的回执。
     */
    public long clean(LogCleanRequest request) {
        LocalDateTime cutoff = cutoffOf(request.before());
        Long pinned = pinTenant(request.tenantId());
        long total = 0L;
        while (true) {
            int deleted = operLogMapper.delete(Wrappers.<SysOperLog>lambdaQuery()
                    .eq(pinned != null, SysOperLog::getTenantId, pinned)
                    .lt(SysOperLog::getOperTime, cutoff)
                    .last("LIMIT " + CLEAN_BATCH));
            total += deleted;
            if (deleted < CLEAN_BATCH) {
                return total;
            }
        }
    }

    /** 保留期 → 截止时间:1m 一个月 / 6m 半年 / 1y 一年;正则白名单拦在前,这里再兜一道防直调 */
    private LocalDateTime cutoffOf(String before) {
        LocalDateTime now = LocalDateTime.now();
        return switch (before) {
            case "1m" -> now.minusMonths(1);
            case "6m" -> now.minusMonths(6);
            case "1y" -> now.minusYears(1);
            default -> throw BizException.badRequest("保留期仅支持 1m/6m/1y");
        };
    }

    /**
     * 谓词单点,分页/计数/列表三处共用:租户边界来自 pinTenant,请求里的 tenantId 对非平台侧一概不认;
     * 模块与操作名 like、类型与结果 eq、时间是 [beginTime, endTime] 闭区间,两端可各缺一边。
     * 空串条件由 notBlank 收成"不拼这段 like",不留一条恒真的 LIKE '%%'。
     */
    private LambdaQueryWrapper<SysOperLog> buildWrapper(OperLogQuery query) {
        Long pinned = pinTenant(query.tenantId());
        String module = query.module();
        String operator = query.operator();
        return Wrappers.<SysOperLog>lambdaQuery()
                .eq(pinned != null, SysOperLog::getTenantId, pinned)
                .like(notBlank(module), SysOperLog::getModule, module)
                .eq(query.businessType() != null, SysOperLog::getBusinessType, query.businessType())
                .like(notBlank(operator), SysOperLog::getOperator, operator)
                .eq(query.status() != null, SysOperLog::getStatus, query.status())
                .ge(query.beginTime() != null, SysOperLog::getOperTime, query.beginTime())
                .le(query.endTime() != null, SysOperLog::getOperTime, query.endTime());
    }

    /** 平台侧放行显式租户参数(含 null=跨租户全量);其余租户钉死本租户 */
    private Long pinTenant(Long requested) {
        return isPlatform() ? requested : TenantContextHolder.get();
    }

    /** 平台侧身份只认权限码,不认"租户 ID 等于几"——种子把码授给谁,谁能跨租户看 */
    private boolean isPlatform() {
        return AuthorityChecker.has(PLATFORM_ALL);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
