package com.snail.platform.function.auth.service;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 职责:多租户账号选择的一次性凭证(pickKey)的登记与核销——验证码在登录首调即核销,
 * pickKey 是"验证码已验证"的唯一证明:UUID 高熵 + 2 分钟 TTL + GETDEL 一次一用。
 * 候选只存 userId 列表(逗号拼接,Long 无转义歧义,不值得引 JSON),签发时按 ID 现查,
 * 不回传手机号等档案快照。独立成小类:密码登录将来若也要多账号选择,凭证机制原样复用。
 * 上游:AuthService 的多命中分支与 pick 端点;下游:StringRedisTemplate。
 */
@Service
public class LoginPickService {

    /** Valkey 凭证键前缀 */
    private static final String KEY_PREFIX = "snail:auth:sms-pick:";

    /** 凭证有效期:给用户看完列表点一下的时间,足够短让泄漏窗口收敛 */
    private static final Duration TTL = Duration.ofMinutes(2);

    /** Redis 存取模板 */
    private final StringRedisTemplate redisTemplate;

    public LoginPickService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 登记候选集,返回一次性选择凭证 */
    public String issue(List<Long> userIds) {
        String pickKey = cn.hutool.core.util.IdUtil.fastSimpleUUID();
        List<Long> distinct = userIds.stream().distinct().toList();
        redisTemplate.opsForValue().set(KEY_PREFIX + pickKey, joinIds(distinct), TTL);
        return pickKey;
    }

    /**
     * 核销凭证取候选集:GETDEL 原子取回即删,重放只有一次机会;
     * 凭证无效(过期/伪造/格式坏)返回 null,调用方统一按"选择已过期"拒绝。
     */
    public List<Long> consume(String pickKey) {
        String raw = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + pickKey);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Arrays.stream(raw.split(",")).map(Long::parseLong).toList();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String joinIds(List<Long> userIds) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < userIds.size(); i++) {
            if (i > 0) {
                joined.append(',');
            }
            joined.append(userIds.get(i));
        }
        return joined.toString();
    }
}
