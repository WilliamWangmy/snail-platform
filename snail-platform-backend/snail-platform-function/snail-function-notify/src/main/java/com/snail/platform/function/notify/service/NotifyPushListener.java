package com.snail.platform.function.notify.service;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 职责:订阅 Redis 频道 snail:notify:push 的跨实例广播,收到后推给本机持有的 SSE 连接。
 * 监听容器在本类内自建自管(afterPropertiesSet 启动、destroy 停止),不引入模块级 @Configuration——
 * 与"本模块不提供装配类"的既定口径一致,容器的生命周期与投递线程同口径收口。
 * 频道消息不持久化:只影响"亮灯时机",不影响数据完整性(收件行已落库,轮询兜底)。
 * 上游:SseOutboundChannel 的 publish;下游:NotifyPushService.pushLocal。
 */
@Component
public class NotifyPushListener implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(NotifyPushListener.class);

    /** 本机推送单点 */
    private final NotifyPushService pushService;

    /** 载荷反序列化与通道侧同构 */
    private final ObjectMapper objectMapper;

    /** Redis 连接工厂:自动配置提供,容器经它建 pub/sub 长连接 */
    private final RedisConnectionFactory connectionFactory;

    /** 监听容器;本类自建自管,不走模块级 @Configuration */
    private RedisMessageListenerContainer container;

    public NotifyPushListener(NotifyPushService pushService, ObjectMapper objectMapper,
                              RedisConnectionFactory connectionFactory) {
        this.pushService = pushService;
        this.objectMapper = objectMapper;
        this.connectionFactory = connectionFactory;
    }

    @Override
    public void afterPropertiesSet() {
        container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // 发布侧是 StringRedisTemplate(UTF-8 字节),这里直接按字符串解码,不经反射适配器
        container.addMessageListener((message, pattern) ->
                handleMessage(new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic(NotifyPushService.PUSH_CHANNEL));
        container.afterPropertiesSet();
        container.start();
        log.info("通知推送监听已启动,频道: {}", NotifyPushService.PUSH_CHANNEL);
    }

    /**
     * 频道回调:反序列化失败按坏消息丢弃并告警——推送是尽力而为,不让坏帧拖垮订阅线程
     */
    public void handleMessage(String body) {
        try {
            NotifyPushService.PushPayload payload =
                    objectMapper.readValue(body, NotifyPushService.PushPayload.class);
            pushService.pushLocal(payload);
        } catch (Exception e) {
            log.warn("推送广播处理失败,已丢弃: {}", e.getMessage());
        }
    }

    @Override
    public void destroy() throws Exception {
        if (container != null) {
            container.stop();
            container.destroy();
        }
    }
}
