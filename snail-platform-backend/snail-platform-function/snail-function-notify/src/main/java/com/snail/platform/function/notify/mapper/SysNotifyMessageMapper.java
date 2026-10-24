package com.snail.platform.function.notify.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.snail.platform.function.notify.entity.SysNotifyMessage;

/**
 * 职责:sys_notify_message 数据访问。租户托管表,平台面跨租户检索走登记的 bypass 窄窗口。
 * 上游:MybatisPlusConfig 的 @MapperScan;下游:notify 子域 service。
 */
public interface SysNotifyMessageMapper extends BaseMapper<SysNotifyMessage> {
}
