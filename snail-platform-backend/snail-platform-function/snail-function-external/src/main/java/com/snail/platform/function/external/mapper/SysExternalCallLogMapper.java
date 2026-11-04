package com.snail.platform.function.external.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.snail.platform.function.external.entity.SysExternalCallLog;

/**
 * 职责:sys_external_call_log 数据访问。只增流水表,在租户拦截器 ignore 清单,
 * 异步落账线程不依赖线程租户上下文,租户归属靠行内快照列。
 * 上游:MybatisPlusConfig 的 @MapperScan;下游:ExternalCallLedger(写)、ExternalCallLogService(读)。
 */
public interface SysExternalCallLogMapper extends BaseMapper<SysExternalCallLog> {
}
