package com.snail.platform.function.notify.service;

import java.util.List;

/**
 * 职责:出站通道 SPI——站内信落库即达,站外投递(邮件/短信)由实现各自承担;
 * 实现注册为 Spring Bean 即接入投递器,可用性自查(available)而不是条件装配,
 * 因为 JavaMailSender 由自动配置提供,@ConditionalOnBean 在业务组件上判不到它。
 * 上游:OutboundDispatcher;下游:MailOutboundChannel 及将来的短信通道。
 */
public interface OutboundChannel {

    /** 通道标识,落 sys_notify_outbound.channel */
    String channel();

    /** 通道是否可用;缺配置的实现返回 false,投递器跳过而非报错 */
    default boolean available() {
        return true;
    }

    /** 投递一次;失败抛异常,由投递器重试一次后落失败记录 */
    void send(OutboundMessage message) throws Exception;

    /** 一次投递的消息体:targets 的 address 按通道语义寻址(邮件为邮箱) */
    record OutboundMessage(
            /** 消息 ID */
            Long messageId,
            /** 标题 */
            String title,
            /** 正文,纯文本 */
            String content,
            /** 收件目标 */
            List<OutboundTarget> targets) {
    }

    /** 收件目标:userId 溯源,address 为通道寻址地址(可为空,实现侧过滤) */
    record OutboundTarget(
            /** 用户 ID */
            Long userId,
            /** 寻址地址:邮箱/手机号 */
            String address) {
    }
}
