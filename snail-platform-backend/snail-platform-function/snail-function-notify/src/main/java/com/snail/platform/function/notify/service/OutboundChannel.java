package com.snail.platform.function.notify.service;

import java.util.List;
import java.util.Map;

/**
 * 职责:出站通道 SPI——站内信落库即达,站外投递(邮件/短信)由实现各自承担;
 * 实现注册为 Spring Bean 即接入投递器,可用性自查(available)而不是条件装配,
 * 因为 JavaMailSender 由自动配置提供,@ConditionalOnBean 在业务组件上判不到它。
 * 上游:OutboundDispatcher;下游:MailOutboundChannel 及将来的短信通道。
 */
public interface OutboundChannel {

    /** 通道名:站内信——不是出站通道,是"是否落收件行"的开关(NotifySendCommand.channels 口径) */
    String CHANNEL_INNER = "inner";

    /** 通道名:站内消息实时推送(落收件行的消息自动附加,保铃铛在线推) */
    String CHANNEL_SSE = "sse";

    /** 通道名:邮件出站 */
    String CHANNEL_MAIL = "mail";

    /** 通道名:短信出站 */
    String CHANNEL_SMS = "sms";

    /** 通道标识,落 sys_notify_outbound.channel */
    String channel();

    /** 通道是否可用;缺配置的实现返回 false,投递器跳过而非报错 */
    default boolean available() {
        return true;
    }

    /** 投递一次;失败抛异常,由投递器重试一次后落失败记录 */
    void send(OutboundMessage message) throws Exception;

    /** 一次投递的消息体:targets 按通道语义寻址(邮件取 email,短信取 phone) */
    record OutboundMessage(
            /** 消息 ID */
            Long messageId,
            /** 标题 */
            String title,
            /** 正文,纯文本 */
            String content,
            /** 业务域标签,SSE 推送端按它分流 */
            String bizType,
            /** 收件目标 */
            List<OutboundTarget> targets,
            /** 模板变量键值,模板制通道(短信)消费;null 归一化为空集 */
            Map<String, String> params) {

        public OutboundMessage {
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }

    /** 收件目标:userId 溯源;email/phone 为对应通道的寻址地址,可空由实现侧过滤 */
    record OutboundTarget(
            /** 用户 ID */
            Long userId,
            /** 邮箱,邮件通道寻址 */
            String email,
            /** 手机号,短信通道寻址 */
            String phone) {
    }
}
