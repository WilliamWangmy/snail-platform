package com.snail.platform.framework.redis;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 职责:通用计数锁定器——"N 次失败锁 M 分钟"这类暴力防护的唯一实现;
 * INCR 与挂窗口用 Lua 打包原子执行,首个请求进程死掉也不会留下永不过期的计数键
 * (修正逐条 increment+expire 的非原子竞态)。
 * 上游:auth 登录失败计数、自助改密失败计数;下游:Valkey。
 * 口径:键由调用方经 SnailRedisKeys 组合;计数只存 Valkey,锁定是运行态不落表。
 */
@Component
public class SnailRateGuard {

    /** INCR 与首次挂窗口原子化:count==1 时才 PEXPIRE,窗口从首次失败起算 */
    private static final DefaultRedisScript<Long> RECORD_SCRIPT = new DefaultRedisScript<>(
            "local n = redis.call('INCR', KEYS[1]) "
                    + "if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
                    + "return n", Long.class);

    private final StringRedisTemplate redisTemplate;

    public SnailRateGuard(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 是否已达锁定阈值 */
    public boolean isLocked(String key, int threshold) {
        String value = redisTemplate.opsForValue().get(key);
        return value != null && Long.parseLong(value) >= threshold;
    }

    /** 记一次失败,返回当前计数;首次失败挂窗口,到点自动解锁 */
    public long record(String key, Duration window) {
        Long count = redisTemplate.execute(RECORD_SCRIPT, List.of(key), String.valueOf(window.toMillis()));
        return count == null ? 0 : count;
    }

    /** 动作成功即清零 */
    public void clear(String key) {
        redisTemplate.delete(key);
    }
}
