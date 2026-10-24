package com.snail.platform.function.notify.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.snail.platform.framework.db.entity.TenantAuditedEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 职责:sys_notify_recipient 表 PO。收件行,一人一行,收件箱的查询与已读事实源;
 * nickname 是发送时刻快照,此后改名不回溯——通知是"当时的事实"。
 * 上游:framework-db 的 TenantAuditedEntity;下游:notify 模块的 service 与 mapper。
 */
@Getter
@Setter
@TableName("sys_notify_recipient")
public class SysNotifyRecipient extends TenantAuditedEntity {

    /** 收件行 ID */
    @TableId(value = "recipient_id", type = IdType.AUTO)
    private Long recipientId;

    /** 消息 ID */
    private Long messageId;

    /** 收件人用户 ID,收件箱查询谓词 */
    private Long userId;

    /** 收件人显示名快照 */
    private String nickname;

    /** 已读:0 未读 1 已读 */
    private Integer readStatus;

    /** 已读时间 */
    private java.time.LocalDateTime readTime;
}
