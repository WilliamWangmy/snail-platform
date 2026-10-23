package com.snail.platform.function.auth.service;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 职责:登录失败的暴力计数与锁定判定,计数只存 Valkey——锁定是运行态,不是审计,不落表。
 * 上游:AuthService 校验链;下游:Valkey。
 * 口径:按登录名全局计数(用户名本就全局唯一),验证码失败与口令失败合并计。
 */
@Service
public class LoginFailGuard {

    /** Valkey 键前缀,按登录名计数 */
    private static final String KEY_PREFIX = "snail:auth:fail:";

    /** 锁定的连续失败次数阈值 */
    private static final int MAX_FAILS = 5;

    /** 计数窗口与锁定时长 */
    private static final Duration WINDOW = Duration.ofMinutes(10);

    /** Redis 计数存取 */
    private final StringRedisTemplate redisTemplate;

    public LoginFailGuard(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 是否已达锁定阈值 */
    public boolean isLocked(String username) {
        String value = redisTemplate.opsForValue().get(KEY_PREFIX + username);
        return value != null && Long.parseLong(value) >= MAX_FAILS;
    }

    /** 记一次失败;首次失败挂窗口,到点自动解锁 */
    public void increase(String username) {
        String key = KEY_PREFIX + username;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, WINDOW);
        }
    }

    /** 登录成功即清零 */
    public void clear(String username) {
        redisTemplate.delete(KEY_PREFIX + username);
    }
}
