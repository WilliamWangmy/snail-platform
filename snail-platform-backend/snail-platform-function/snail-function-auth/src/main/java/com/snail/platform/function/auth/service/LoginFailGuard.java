package com.snail.platform.function.auth.service;

import com.snail.platform.framework.redis.SnailRateGuard;
import java.time.Duration;
import org.springframework.stereotype.Service;

/**
 * 职责:凭证维度(账号或密码错误)的暴力计数与锁定判定,计数只存 Valkey——锁定是运行态,不是审计,不落表。
 * 原子计数逻辑在 framework-redis 的 SnailRateGuard 单点,本类只持有登录场景的键名与阈值。
 * 上游:AuthService 校验链;下游:SnailRateGuard。
 * 口径:键是 IP + 登录名的组合——只按登录名计数,任何人都能用假用户名把真实账号刷进锁定;
 * 组合键让锁定只在同一来源成立,来源轮换由部署侧限流负责。
 * 验证码维度不归本类:它由 CaptchaService 自己按 IP 计数,两类失败的处置手段完全不同。
 */
@Service
public class LoginFailGuard {

    /** Valkey 键前缀:来源 + 登录名的凭证失败计数 */
    private static final String KEY_PREFIX = "snail:auth:fail:credential:";

    /** 锁定的连续凭证失败次数阈值 */
    private static final int MAX_FAILS = 5;

    /** 计数窗口与锁定时长 */
    private static final Duration WINDOW = Duration.ofMinutes(10);

    /** 通用计数锁定器 */
    private final SnailRateGuard rateGuard;

    public LoginFailGuard(SnailRateGuard rateGuard) {
        this.rateGuard = rateGuard;
    }

    /** 该来源对该登录名是否已达锁定阈值 */
    public boolean isCredentialLocked(String ip, String username) {
        return rateGuard.isLocked(key(ip, username), MAX_FAILS);
    }

    /** 记一次凭证失败;首次失败挂窗口,到点自动解锁 */
    public void increaseCredentialFail(String ip, String username) {
        rateGuard.record(key(ip, username), WINDOW);
    }

    /** 登录成功即清零本来源的计数(其他来源的计数随窗口自然过期) */
    public void clearCredentialFails(String ip, String username) {
        rateGuard.clear(key(ip, username));
    }

    private static String key(String ip, String username) {
        return KEY_PREFIX + (ip == null ? "unknown" : ip) + ":" + username;
    }
}
