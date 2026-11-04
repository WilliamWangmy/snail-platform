package com.snail.platform.function.external.api;

import java.util.List;

/**
 * 职责:邮件发送端口——消费方要"发一封邮件",不感知 SMTP 与厂商。
 * 契约口径:失败(含配置缺席下被调用、地址全无效)抛 ExternalCallException,送达承诺由调用方决定重试;
 * 地址的过滤(空白/非法/去重)在实现侧完成,消费方传原始列表即可。
 * 上游:notify 邮件出站通道及将来的其他消费方;下游:SmtpMailClient。
 */
public interface MailPort {

    /** 邮件能力是否可用:spring.mail 未配置时为 false,调用方按"通道未配置"降级,不产生失败流水 */
    boolean available();

    /** 发送一封邮件;失败抛 ExternalCallException */
    void send(MailMessage message);

    /**
     * 一封邮件的完整描述:to 为原始收件地址列表(实现侧过滤与去重),
     * caller 是调用方自报标识(notify/auth/…),bizType/bizId 供流水按业务溯源,均可空。
     */
    record MailMessage(
            /** 主题 */
            String subject,
            /** 正文,纯文本 */
            String content,
            /** 收件地址列表 */
            List<String> to,
            /** 调用方标识,落流水 caller 列 */
            String caller,
            /** 业务域标签,落流水 biz_type 列 */
            String bizType,
            /** 关联业务 ID,落流水 biz_id 列 */
            Long bizId) {
    }
}
