package com.snail.platform.function.notify.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.snail.platform.framework.db.entity.TenantAuditedEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 职责:sys_notify_message 表 PO。通知消息主体,租户托管;收件行在 sys_notify_recipient 一人一行。
 * 上游:framework-db 的 TenantAuditedEntity;下游:notify 模块的 service 与 mapper。
 */
@Getter
@Setter
@TableName("sys_notify_message")
public class SysNotifyMessage extends TenantAuditedEntity {

    /** 消息 ID */
    @TableId(value = "message_id", type = IdType.AUTO)
    private Long messageId;

    /** 标题 */
    private String title;

    /** 正文,纯文本 */
    private String content;

    /** 业务域标签:announcement/account/…,消费方按它反查业务上下文 */
    private String bizType;

    /** 关联业务 ID */
    private Long bizId;

    /** 发送人用户 ID,系统代发为 NULL */
    private Long senderId;

    /** 发送人显示名快照,发送时刻定格 */
    private String senderName;

    /** 发送时间 */
    private java.time.LocalDateTime sendTime;
}
