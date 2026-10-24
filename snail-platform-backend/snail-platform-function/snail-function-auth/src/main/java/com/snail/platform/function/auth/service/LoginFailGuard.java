package com.snail.platform.function.auth.service;

import com.snail.platform.framework.redis.SnailRateGuard;
import java.time.Duration;
import org.springframework.stereotype.Service;

/**
 * 职责:登录失败的暴力计数与锁定判定,计数只存 Valkey——锁定是运行态,不是审计,不落表。
 * 原子计数逻辑在 framework-redis 的 SnailRateGuard 单点,本类只持有登录场景的键名与阈值。
 * 上游:AuthService 校验链;下游:SnailRateGuard。
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

    /** 通用计数锁定器 */
    private final SnailRateGuard rateGuard;

    public LoginFailGuard(SnailRateGuard rateGuard) {
        this.rateGuard = rateGuard;
    }

    /** 是否已达锁定阈值 */
    public boolean isLocked(String username) {
        return rateGuard.isLocked(KEY_PREFIX + username, MAX_FAILS);
    }

    /** 记一次失败;首次失败挂窗口,到点自动解锁 */
    public void increase(String username) {
        rateGuard.record(KEY_PREFIX + username, WINDOW);
    }

    /** 登录成功即清零 */
    public void clear(String username) {
        rateGuard.clear(KEY_PREFIX + username);
    }
}
