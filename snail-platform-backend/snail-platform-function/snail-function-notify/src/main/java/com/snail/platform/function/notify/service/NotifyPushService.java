package com.snail.platform.function.notify.service;

import com.snail.platform.framework.core.context.LoginUserContextHolder;
import com.snail.platform.framework.core.exception.BizException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * 职责:SSE 会话单点——userId 到在线 emitter 的注册表、订阅建连、本机推送、心跳保活。
 * 注册表按用户聚合(多端/多标签页各一条连接),推送全量遍历,send 失败即摘除;
 * 心跳每 25s 发 SSE 注释帧(:ping),防 Nginx/LB 空闲断连,前端无感。
 * 消息的跨实例扇出经 Redis 频道(见 NotifyPushListener),本类只管"推给本机持有的连接"。
 * 上游:NotifyPushController(建连)与 NotifyPushListener(收到频道消息);
 * 下游:SseEmitter 各在线连接。推送 payload 只有 messageId/title/bizType 快照,不含正文。
 */
@Component
public class NotifyPushService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(NotifyPushService.class);

    /** 跨实例扇出的 Redis 频道;落库实例 publish 一次,各实例的监听器推自己持有的连接 */
    public static final String PUSH_CHANNEL = "snail:notify:push";

    /** 心跳间隔:小于常见网关 60s 空闲断连阈值,留足余量 */
    static final long HEARTBEAT_SECONDS = 25;

    /** 在线连接注册表:userId → 该用户全部在线 emitter;列表线程安全,遍历时允许并发增删 */
    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /** JSON 序列化:频道消息与 SSE 帧共用同一 payload 形态 */
    private final ObjectMapper objectMapper;

    /** 心跳线程:daemon 不阻止 JVM 退出,停机由 destroy 收口 */
    private final ScheduledExecutorService heartbeat;

    public NotifyPushService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "notify-push-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        this.heartbeat.scheduleAtFixedRate(this::pingAll, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 一次推送的载荷:收件用户全集 + 消息快照;正文不进推送,详情由前端点开再取
     */
    public record PushPayload(
            /** 消息 ID */
            Long messageId,
            /** 标题 */
            String title,
            /** 业务域标签 */
            String bizType,
            /** 收件用户 ID 全集 */
            List<Long> userIds) {
    }

    /**
     * 建连:当前登录人的 SSE 连接入注册表;三个回调都收敛到同一个摘除动作,
     * 断开/超时/出错只走一条路径,注册表不留僵尸连接。超时 0L 表示永不超时,活性靠心跳与前端重连。
     */
    public SseEmitter subscribe() {
        Long userId = LoginUserContextHolder.userId();
        if (userId == null) {
            throw BizException.unauthorized("未认证");
        }
        SseEmitter emitter = new SseEmitter(0L);
        List<SseEmitter> list = emitters.computeIfAbsent(userId, key -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable unregister = () -> unregister(userId, emitter);
        emitter.onCompletion(unregister);
        emitter.onTimeout(unregister);
        emitter.onError(t -> unregister.run());
        try {
            // 建连即回一帧:前端 fetch 流读到它才算订阅成功,也顺带冲掉代理的首包缓冲
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (Exception e) {
            unregister.run();
            throw new IllegalStateException("SSE 建连首帧发送失败", e);
        }
        return emitter;
    }

    /** 推给本机持有的连接:逐 emitter 独立 try-catch,一条死连接不影响其余收件人 */
    public void pushLocal(PushPayload payload) {
        if (emitters.isEmpty()) {
            return;
        }
        String json = toJson(payload);
        for (Long userId : payload.userIds()) {
            List<SseEmitter> list = emitters.get(userId);
            if (list == null) {
                continue;
            }
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().data(json, MediaType.APPLICATION_JSON));
                } catch (Exception e) {
                    log.debug("用户 {} 的一条 SSE 连接推送失败,摘除: {}", userId, e.getMessage());
                    unregister(userId, emitter);
                }
            }
        }
    }

    /** 当前在线连接数:运维观测用 */
    public int onlineConnections() {
        return emitters.values().stream().mapToInt(List::size).sum();
    }

    /** 摘除连接并回收空列表,防止 userId 维度的键无限累积 */
    private void unregister(Long userId, SseEmitter emitter) {
        emitters.computeIfPresent(userId, (key, list) -> {
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
    }

    /** 心跳:SSE 注释帧前端不可见,写失败说明连接已死,顺手摘除 */
    private void pingAll() {
        for (Map.Entry<Long, List<SseEmitter>> entry : emitters.entrySet()) {
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception e) {
                    unregister(entry.getKey(), emitter);
                }
            }
        }
    }

    /** 序列化失败按空串处理:send 侧对空 JSON 的 emitter.send 会被上游 catch,不会带脏帧出去 */
    private String toJson(PushPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("推送载荷序列化失败: {}", e.getMessage());
            return "";
        }
    }

    /** 停机:心跳线程立即退出;在线连接由客户端按断线逻辑自行降级轮询 */
    @Override
    public void destroy() {
        heartbeat.shutdownNow();
    }

    /** 供监听器侧构造载荷时的 userIds 去重口径(空值过滤),通道与监听器共用 */
    public static List<Long> distinctUserIds(List<Long> userIds) {
        return userIds == null ? List.of()
                : userIds.stream().filter(Objects::nonNull).distinct().toList();
    }
}
