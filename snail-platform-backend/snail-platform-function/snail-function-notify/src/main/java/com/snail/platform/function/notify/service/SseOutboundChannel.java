package com.snail.platform.function.notify.service;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 职责:SSE 出站通道——把"落库完成的站内消息"广播到 Redis 频道,跨实例扇出交给
 * 各实例的 NotifyPushListener 推自己持有的连接。本通道只做一次 publish:单实例部署
 * 同一条链路(自己的监听器收到后推本地),不为省一跳 Redis 维护双路径。
 * available 由 snail.notify.push.enabled 决定,关闭即退化纯轮询,投递器自然跳过。
 * 上游:OutboundDispatcher;下游:Redis 频道 snail:notify:push → NotifyPushListener。
 */
@Component
public class SseOutboundChannel implements OutboundChannel {

    /** 推送总开关;关闭时通道不可用,出站投递跳过,不产生任何 Redis 流量 */
    private final boolean enabled;

    /** Redis 写口:只做 convertAndSend,不碰业务键 */
    private final StringRedisTemplate redisTemplate;

    /** 载荷序列化与 NotifyPushListener 同构 */
    private final ObjectMapper objectMapper;

    public SseOutboundChannel(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                              @Value("${snail.notify.push.enabled:true}") boolean enabled) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    @Override
    public String channel() {
        return CHANNEL_SSE;
    }

    @Override
    public boolean available() {
        return enabled;
    }

    @Override
    public void send(OutboundMessage message) throws Exception {
        // 收件人全无效(理论不可达,发送侧已闸)时静默返回,不往频道发空载荷
        List<Long> userIds = NotifyPushService.distinctUserIds(
                message.targets().stream().map(OutboundChannel.OutboundTarget::userId).toList());
        if (userIds.isEmpty()) {
            return;
        }
        String json = objectMapper.writeValueAsString(new NotifyPushService.PushPayload(
                message.messageId(), message.title(), message.bizType(), userIds));
        redisTemplate.convertAndSend(NotifyPushService.PUSH_CHANNEL, json);
    }
}
