package com.snail.platform.function.external.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.db.PageAssembler;
import com.snail.platform.function.external.dto.ExternalCallLogQuery;
import com.snail.platform.function.external.dto.ExternalCallLogVO;
import com.snail.platform.function.external.entity.SysExternalCallLog;
import com.snail.platform.function.external.mapper.SysExternalCallLogMapper;
import org.springframework.stereotype.Service;

/**
 * 职责:第三方调用流水查询面——平台只读检索,写路径在 ExternalCallLedger 的异步线程,这里零写。
 * 流水是 ignore 表,平台管理动作不挂数据权限开窗:表里本就有平台级行(tenant_id NULL),
 * 租户面不提供入口,平台侧跨租户全量看。
 * 上游:ExternalCallController;下游:SysExternalCallLogMapper。
 */
@Service
public class ExternalCallLogService {

    /** 流水表只读 */
    private final SysExternalCallLogMapper callLogMapper;

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
}
