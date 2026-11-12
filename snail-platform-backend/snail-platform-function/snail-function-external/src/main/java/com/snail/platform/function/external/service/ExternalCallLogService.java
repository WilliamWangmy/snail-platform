package com.snail.platform.function.external.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.db.PageAssembler;
import com.snail.platform.function.external.dto.ExternalCallLogCleanRequest;
import com.snail.platform.function.external.dto.ExternalCallLogQuery;
import com.snail.platform.function.external.dto.ExternalCallLogVO;
import com.snail.platform.function.external.entity.SysExternalCallLog;
import com.snail.platform.function.external.mapper.SysExternalCallLogMapper;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

/**
 * 职责:第三方调用流水服务面——平台只读检索 + 按保留期清除历史。写路径在 ExternalCallLedger 的异步线程,
 * 唯一删除动作是 clean。流水是 ignore 表,平台管理动作不挂数据权限开窗:表里本就有平台级行(tenant_id NULL),
 * 租户面不提供入口,平台侧跨租户全量看、全量清。
 * 上游:ExternalCallController;下游:SysExternalCallLogMapper。
 */
@Service
public class ExternalCallLogService {

    /** 流水表数据访问:读在 page,写在落账线程与 clean */
    private final SysExternalCallLogMapper callLogMapper;

    /** 清除单批行数:与操作/登录日志同口径,分批独立提交摊薄大表删除压力 */
    private static final int CLEAN_BATCH = 1_000;

    public ExternalCallLogService(SysExternalCallLogMapper callLogMapper) {
        this.callLogMapper = callLogMapper;
    }

    /**
     * 分页:厂商/调用方精确、终态精确、时间 [beginTime, endTime] 闭区间,空条件不拼进 SQL;
     * 按自增 ID 倒序——同毫秒的调用也有确定次序。没有详情端点:快照已脱敏截断,列表行就是全行。
     */
    public PageResult<ExternalCallLogVO> page(ExternalCallLogQuery query) {
        Page<SysExternalCallLog> page = PageAssembler.from(query);
        IPage<SysExternalCallLog> result = callLogMapper.selectPage(page,
                buildWrapper(query).orderByDesc(SysExternalCallLog::getCallId));
        return PageResult.of(result.getRecords().stream().map(ExternalCallLogVO::of).toList(),
                result.getTotal());
    }

    /** 谓词单点:厂商/调用方/终态 eq,时间闭区间 ge/le */
    private LambdaQueryWrapper<SysExternalCallLog> buildWrapper(ExternalCallLogQuery query) {
        return Wrappers.<SysExternalCallLog>lambdaQuery()
                .eq(query.vendor() != null && !query.vendor().isBlank(),
                        SysExternalCallLog::getVendor, query.vendor())
                .eq(query.caller() != null && !query.caller().isBlank(),
                        SysExternalCallLog::getCaller, query.caller())
                .eq(query.status() != null, SysExternalCallLog::getStatus, query.status())
                .ge(query.beginTime() != null, SysExternalCallLog::getCreateTime, query.beginTime())
                .le(query.endTime() != null, SysExternalCallLog::getCreateTime, query.endTime());
    }

    /**
     * 清除保留期之外的历史流水:时间列是 create_time,锚点恒为服务端时钟,三档保留期由白名单正则锁死;
     * 整表按期删、无租户参数——流水是平台面账本。分批独立提交、不挂长事务(短信接入后写入量大,
     * 一把 DELETE 会锁死业务),半途失败按"已删批次不回滚、重试继续删"的进度语义处理,返回实际删除行数。
     */
    public long clean(ExternalCallLogCleanRequest request) {
        LocalDateTime cutoff = cutoffOf(request.before());
        long total = 0L;
        while (true) {
            int deleted = callLogMapper.delete(Wrappers.<SysExternalCallLog>lambdaQuery()
                    .lt(SysExternalCallLog::getCreateTime, cutoff)
                    .last("LIMIT " + CLEAN_BATCH));
            total += deleted;
            if (deleted < CLEAN_BATCH) {
                return total;
            }
        }
    }

    /** 保留期 → 截止时间:1m 一个月 / 6m 半年 / 1y 一年;与 system 日志清除同一份三档口径 */
    private LocalDateTime cutoffOf(String before) {
        LocalDateTime now = LocalDateTime.now();
        return switch (before) {
            case "1m" -> now.minusMonths(1);
            case "6m" -> now.minusMonths(6);
            case "1y" -> now.minusYears(1);
            default -> throw BizException.badRequest("保留期仅支持 1m/6m/1y");
        };
    }
}
