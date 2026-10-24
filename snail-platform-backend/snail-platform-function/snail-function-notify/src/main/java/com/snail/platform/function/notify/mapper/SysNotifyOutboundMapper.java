package com.snail.platform.function.notify.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.snail.platform.function.notify.entity.SysNotifyOutbound;

/**
 * 职责:sys_notify_outbound 数据访问。队列状态表,在租户拦截器 ignore 清单,
 * 异步投递线程落库不依赖线程租户上下文,租户归属靠行内快照列。
 * 上游:MybatisPlusConfig 的 @MapperScan;下游:OutboundDispatcher。
 */
public interface SysNotifyOutboundMapper extends BaseMapper<SysNotifyOutbound> {
}
