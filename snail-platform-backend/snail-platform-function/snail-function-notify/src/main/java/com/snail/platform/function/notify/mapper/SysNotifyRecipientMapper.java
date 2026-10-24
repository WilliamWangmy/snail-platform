package com.snail.platform.function.notify.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.snail.platform.function.notify.entity.SysNotifyRecipient;

/**
 * 职责:sys_notify_recipient 数据访问。租户托管表,收件箱谓词恒带 user_id,私人数据不走数据权限开窗。
 * 上游:MybatisPlusConfig 的 @MapperScan;下游:notify 子域 service。
 */
public interface SysNotifyRecipientMapper extends BaseMapper<SysNotifyRecipient> {
}
