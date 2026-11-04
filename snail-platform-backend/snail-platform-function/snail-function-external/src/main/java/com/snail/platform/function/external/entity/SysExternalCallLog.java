package com.snail.platform.function.external.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 职责:sys_external_call_log 表 PO。第三方接口调用流水,一次调用一行,只增不改;
 * 不继承审计基类(日志无修改无假删除,给删除字段就是给篡改留门),create_time 由落账线程显式写。
 * 表在租户拦截器 ignore 清单——落账发生在异步线程,无线程租户上下文,靠本行 tenant_id 快照溯源。
 * 上游:ExternalCallLedger 异步写入;下游:ExternalCallLogService 平台查询。
 */
@Getter
@Setter
@TableName("sys_external_call_log")
public class SysExternalCallLog {

    /** 调用 ID */
    @TableId(value = "call_id", type = IdType.AUTO)
    private Long callId;

    /** 厂商标识:smtp-mail/aliyun-sms/amap */
    private String vendor;

    /** 动作:send-mail/send-sms/geo-ip */
    private String action;

    /** 调用方标识,自报(notify/auth/system),缺省 unknown */
    private String caller;

    /** 业务域标签,调用方自填 */
    private String bizType;

    /** 关联业务 ID */
    private Long bizId;

    /** 请求快照,脱敏+截断 */
    private String request;

    /** 响应摘要,脱敏+截断 */
    private String response;

    /** 调用终态:0 成功 1 失败 */
    private Integer status;

    /** 失败原因,截断落库 */
    private String error;

    /** 耗时毫秒 */
    private Integer costMs;

    /** 链路 ID,MDC 透传,无链路体系时为 NULL */
    private String traceId;

    /** 触发人用户 ID 快照,系统触发为 NULL */
    private Long operatorId;

    /** 租户快照,平台级调用为 NULL */
    private Long tenantId;

    /** 调用时间 */
    private LocalDateTime createTime;
}
