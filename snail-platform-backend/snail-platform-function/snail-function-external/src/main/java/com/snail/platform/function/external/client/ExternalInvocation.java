package com.snail.platform.function.external.client;

/**
 * 职责:一次第三方调用的描述——模板方法 invoke 的入参,流水行的 request/biz 列来源。
 * requestJson 由客户端组装:地址先经 ExternalMasker 打码,密钥类字段根本不进快照,
 * 键级兜底脱敏(SensitiveDataMasker)在 AbstractExternalClient 里统一过一遍。
 * public:mock 子包(client.mock)的适配器也要构造它,跨包需要公开。
 * 上游:各厂商适配器构造;下游:AbstractExternalClient。
 */
public record ExternalInvocation(
        /** 动作标识,落流水 action 列:send-mail/send-sms/geo-ip */
        String action,
        /** 调用方自报标识,落流水 caller 列,缺省 unknown */
        String caller,
        /** 业务域标签,落流水 biz_type 列 */
        String bizType,
        /** 关联业务 ID,落流水 biz_id 列 */
        Long bizId,
        /** 请求快照(JSON 形态,地址已打码),落库前再过键级脱敏与截断 */
        String requestJson) {
}
