package com.snail.platform.function.external.api;

import java.util.Map;

/**
 * 职责:短信发送端口——消费方要"按模板发一条短信",不感知服务商与签名细节。
 * 契约口径:国内短信本就是"签名+报备模板+参数"形态,端口只收 templateCode + params,
 * 自由文案降级是调用方的事,不进契约;phone 单发一号一次,群发由调用方展开。
 * 失败抛 ExternalCallException——送达承诺,由调用方决定重试;服务商限流原样上抛,不自动重试。
 * 上游:auth 验证码、notify 通知短信(M2 接入);下游:AliyunSmsClient。
 */
public interface SmsPort {

    /** 短信能力是否可用:未配置 vendor 与密钥时为 false,调用方按"通道未配置"降级 */
    boolean available();

    /** 发送一条模板短信;失败抛 ExternalCallException */
    void send(SmsCommand command);

    /**
     * 一条短信的完整描述:params 为模板变量键值,模板编码与服务商报备一一对应;
     * caller/bizType/bizId 供流水溯源,均可空。
     */
    record SmsCommand(
            /** 收件手机号 */
            String phone,
            /** 服务商模板编码 */
            String templateCode,
            /** 模板变量键值 */
            Map<String, String> params,
            /** 调用方标识,落流水 caller 列 */
            String caller,
            /** 业务域标签,落流水 biz_type 列 */
            String bizType,
            /** 关联业务 ID,落流水 biz_id 列 */
            Long bizId) {
    }
}
