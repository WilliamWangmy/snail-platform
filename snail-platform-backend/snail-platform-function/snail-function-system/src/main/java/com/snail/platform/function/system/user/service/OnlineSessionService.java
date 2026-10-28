package com.snail.platform.function.system.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.snail.platform.framework.core.context.LoginUser;
import com.snail.platform.framework.core.context.LoginUserContextHolder;
import com.snail.platform.framework.core.context.TenantContextHolder;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.redis.SnailRedisKeys;
import com.snail.platform.framework.security.heartbeat.SessionHeartbeatPort;
import com.snail.platform.framework.security.token.SnailPrincipal;
import com.snail.platform.function.system.user.dto.OnlineQuery;
import com.snail.platform.function.system.user.dto.OnlineSessionVO;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 职责:在线会话的 Redis 实现——心跳写入"谁在线/从哪来/最近活跃",列表按租户索引聚合,强制下线双保险。
 * 心跳每认证请求一次:SETEX 会话键(30 分钟滑动) + SADD/EXPIRE 索引(Lua 原子);异常全吞、告警限频,
 * redis 抖动不阻断业务请求。强制下线 = 令牌版本自增(真正的吊销) + 会话键清理(列表即时消失)。
 * 上游:SnailAuthFilter 经 SessionHeartbeatPort 端口、OnlineController;下游:Valkey、UserService.forceLogout。
 */
@Service
public class OnlineSessionService implements SessionHeartbeatPort {

    private static final Logger log = LoggerFactory.getLogger(OnlineSessionService.class);

    /** 会话与索引的滑动窗口 */
    private static final Duration ONLINE_TTL = Duration.ofMinutes(30);

    /** SADD 与 EXPIRE 原子化:索引键永不过期竞态(修正逐条写的非原子竞态) */
    private static final DefaultRedisScript<Long> INDEX_SCRIPT = new DefaultRedisScript<>(
            "redis.call('SADD', KEYS[1], ARGV[1]) "
                    + "redis.call('EXPIRE', KEYS[1], ARGV[2]) "
                    + "return 1", Long.class);

    /** 心跳失败告警限频:每 100 次告警一条,redis 长时间宕机不刷屏 */
    private static final int WARN_EVERY = 100;

    /** Valkey 存取 */
    private final StringRedisTemplate redisTemplate;

    /** 会话详情的 JSON 编解码 */
    private final ObjectMapper objectMapper;

    /** 强制下线的令牌吊销单点 */
    private final UserService userService;

    /** 心跳失败计数,限频告警 */
    private final AtomicLong failures = new AtomicLong();

    public OnlineSessionService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                                UserService userService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.userService = userService;
    }

    /** 会话快照,首见时间定格,其余随心跳刷新;username 取自令牌身份、nickname 取自同请求已装配的登录人快照,都免回查档案 */
    private record OnlineSession(String username, String nickname, String ip, String userAgent,
                                 LocalDateTime firstSeen, LocalDateTime lastActive) {
    }

    @Override
    public void beat(SnailPrincipal principal, String ip, String userAgent) {
        try {
            String key = SnailRedisKeys.online(principal.tenantId(), principal.userId());
            LocalDateTime now = LocalDateTime.now();
            // 首见时间跨心跳保留:整键覆盖写会把"上线时刻"漂成"最近一次请求",在线列表就再也读不出会话时长
            OnlineSession previous = parseQuietly(redisTemplate.opsForValue().get(key));
            // 显示名取自过滤器刚写入的登录人快照(它排在心跳之前),不在这里多查一次档案
            LoginUser snapshot = LoginUserContextHolder.get();
            OnlineSession session = new OnlineSession(principal.username(),
                    snapshot == null ? null : snapshot.nickname(), ip, userAgent,
                    previous == null ? now : previous.firstSeen(), now);
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(session), ONLINE_TTL);
            redisTemplate.execute(INDEX_SCRIPT,
                    List.of(SnailRedisKeys.onlineIndex(principal.tenantId())),
                    String.valueOf(principal.userId()), String.valueOf(ONLINE_TTL.toSeconds()));
        } catch (Exception e) {
            // %1 让第一次失败就告警,之后每 100 次一条,宕机期间不刷屏也不至于全无提示
            if (failures.incrementAndGet() % WARN_EVERY == 1) {
                log.warn("在线心跳写入失败(限频告警): {}", e.getMessage());
            }
        }
    }

    /** 在线列表:索引取人、读会话、内存分页;在线规模 = 活跃用户数,上限可控 */
    public PageResult<OnlineSessionVO> page(OnlineQuery query) {
        Long tenantId = TenantContextHolder.get();
        Set<String> members = redisTemplate.opsForSet().members(SnailRedisKeys.onlineIndex(tenantId));
        String keyword = query.keyword() == null ? "" : query.keyword().trim();
        List<OnlineSessionVO> all = members == null ? List.of() : members.stream()
                .map(Long::valueOf)
                .map(userId -> read(tenantId, userId))
                // 索引里可能残留会话键已过期的成员,读不到快照就当不在线,不让列表出空行
                .filter(Objects::nonNull)
                .filter(vo -> keyword.isEmpty()
                        || contains(vo.username(), keyword) || contains(vo.nickname(), keyword))
                .sorted(Comparator.comparing(OnlineSessionVO::lastActive).reversed())
                .toList();
        int from = (query.pageNum() - 1) * query.pageSize();
        List<OnlineSessionVO> rows = from >= all.size() ? List.of()
                : all.subList(from, Math.min(from + query.pageSize(), all.size()));
        return PageResult.of(rows, all.size());
    }

    /** 强制下线:不能踢自己;令牌版本自增是真吊销,redis 清理是尽力而为 */
    public void kick(Long userId) {
        Long operator = LoginUserContextHolder.userId();
        if (Objects.equals(operator, userId)) {
            throw BizException.badRequest("不能强制下线自己");
        }
        userService.forceLogout(userId);
        try {
            Long tenantId = TenantContextHolder.get();
            redisTemplate.delete(SnailRedisKeys.online(tenantId, userId));
            redisTemplate.opsForSet().remove(SnailRedisKeys.onlineIndex(tenantId), String.valueOf(userId));
        } catch (Exception e) {
            log.warn("在线会话键清理失败(版本吊销已生效): {}", e.getMessage());
        }
    }

    /** 关键字按包含匹配;null 出现在最小快照与加字段之前写入的旧会话键上,空值不匹配 */
    private static boolean contains(String value, String keyword) {
        return value != null && value.contains(keyword);
    }

    private OnlineSessionVO read(Long tenantId, Long userId) {
        OnlineSession session = parseQuietly(redisTemplate.opsForValue().get(SnailRedisKeys.online(tenantId, userId)));
        if (session == null) {
            return null;
        }
        return new OnlineSessionVO(userId, session.username(), session.nickname(), session.ip(),
                session.userAgent(), session.firstSeen(), session.lastActive());
    }

    /** 会话 JSON 解码;空值与结构漂移(Jackson 不认 record)一律按"无会话"处理,不让列表整页报错 */
    private OnlineSession parseQuietly(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, OnlineSession.class);
        } catch (Exception e) {
            return null;
        }
    }
}
