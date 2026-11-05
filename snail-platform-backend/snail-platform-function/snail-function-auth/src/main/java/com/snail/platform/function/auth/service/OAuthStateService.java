package com.snail.platform.function.auth.service;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 职责:OAuth 授权跳转的一次性 state 凭证——防 CSRF/重放,authorize 签发、login 核销。
 * UUID 高熵 + 5 分钟 TTL + GETDEL 原子一次一用;值记 "source|redirectUri",
 * 核销时同时校验来源,防止 state 在来源之间挪用。独立成小类:与 LoginPickService 同构
 * (登录凭证统一形态),键前缀独立,与短信 pick 互不干扰。
 * 上游:AuthService 的 authorize 与 loginByOauth;下游:StringRedisTemplate。
 */
@Service
public class OAuthStateService {

    /** Valkey state 键前缀 */
    private static final String KEY_PREFIX = "snail:auth:oauth-state:";

    /** state 有效期:覆盖"跳厂商页→扫码→确认→回跳"全程,足够短让泄漏窗口收敛 */
    private static final Duration TTL = Duration.ofMinutes(5);

    /** Redis 存取模板 */
    private final StringRedisTemplate redisTemplate;

    public OAuthStateService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 登记 state,值 "source|redirectUri"(redirectUri 已过白名单,不会为空);返回高熵 state */
    public String issue(String source, String redirectUri) {
        String state = cn.hutool.core.util.IdUtil.fastSimpleUUID();
        redisTemplate.opsForValue().set(KEY_PREFIX + state, source + "|" + redirectUri, TTL);
        return state;
    }

    /**
     * 核销 state:GETDEL 原子取回即删,重放只有一次机会;
     * 与预期 source 不符(跨来源挪用)同样视为无效。返回其中的 redirectUri,
     * 无效/过期/格式坏返回 null,调用方统一按"登录已过期"拒绝。
     */
    public String consume(String state, String expectedSource) {
        String raw = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + state);
        if (raw == null) {
            return null;
        }
        int separator = raw.indexOf('|');
        if (separator <= 0 || separator == raw.length() - 1) {
            return null;
        }
        if (!raw.substring(0, separator).equals(expectedSource)) {
            return null;
        }
        return raw.substring(separator + 1);
    }
}
