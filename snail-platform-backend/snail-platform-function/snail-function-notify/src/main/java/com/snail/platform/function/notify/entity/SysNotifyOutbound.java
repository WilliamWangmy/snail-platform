package com.snail.platform.function.notify.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.snail.platform.framework.db.entity.AuditedEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 职责:sys_notify_outbound 表 PO。出站通道投递记录,一消息一通道一行,队列状态表;
 * 表在租户拦截器 ignore 清单——落库发生在异步投递线程,无线程租户上下文,靠本行 tenant_id 快照溯源。
 * 上游:framework-db 的 AuditedEntity(审计列由系统级线程写入,操作人留空);下游:OutboundDispatcher。
 */
@Getter
@Setter
@TableName("sys_notify_outbound")
public class SysNotifyOutbound extends AuditedEntity {

    /** 投递记录 ID */
    @TableId(value = "outbound_id", type = IdType.AUTO)
    private Long outboundId;

    /** 消息 ID */
    private Long messageId;

    /** 通道:mail | log */
    private String channel;

    /** 寻址摘要:每通道都记 batch×目标数,无可用通道时记原因占位 */
    private String target;

    /** 投递终态:1 成功 2 失败;0 仅为 DDL 默认值,代码不写 */
    private Integer status;

    /** 失败原因,截断落库 */
    private String error;

    /** 重试次数 */
    private Integer retryCount;

    /** 投递完成时间 */
    private java.time.LocalDateTime sendTime;

    /** 消息归属租户快照,溯源用 */
    private Long tenantId;
}
