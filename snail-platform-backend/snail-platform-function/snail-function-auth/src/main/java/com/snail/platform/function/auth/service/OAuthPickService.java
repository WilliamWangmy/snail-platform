package com.snail.platform.function.auth.service;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 职责:OAuth 多账号选择的一次性凭证(pickKey)——绑定/手机号命中多个可用账号时,
 * 登录首调即返回候选列表,pickKey 是"厂商身份已验证"的唯一证明:
 * UUID 高熵 + 2 分钟 TTL + GETDEL 一次一用。值记 "source|openId|userId,userId,...",
 * 核销后凭候选集校验所选 userId、凭 source+openId 落绑定。
 * 独立成小类:与 LoginPickService 同构(登录凭证统一形态),键前缀独立,与短信 pick 互不干扰。
 * 上游:AuthService 的多命中分支与 OAuth pick 端点;下游:StringRedisTemplate。
 */
@Service
public class OAuthPickService {

    /** Valkey 凭证键前缀 */
    private static final String KEY_PREFIX = "snail:auth:oauth-pick:";

    /** 凭证有效期:给用户看完列表点一下的时间,足够短让泄漏窗口收敛 */
    private static final Duration TTL = Duration.ofMinutes(2);

    /** Redis 存取模板 */
    private final StringRedisTemplate redisTemplate;

    public OAuthPickService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 核销产物:来源、厂商身份与候选 userId 集合 */
    public record PickCandidate(String source, String openId, List<Long> userIds) {
    }

    /** 登记候选集,返回一次性选择凭证;候选只存 userId,身份快照供 pick 时落绑定 */
    public String issue(String source, String openId, List<Long> userIds) {
        String pickKey = cn.hutool.core.util.IdUtil.fastSimpleUUID();
        List<Long> distinct = userIds.stream().distinct().toList();
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < distinct.size(); i++) {
            if (i > 0) {
                joined.append(',');
            }
            joined.append(distinct.get(i));
        }
        redisTemplate.opsForValue().set(KEY_PREFIX + pickKey, source + "|" + openId + "|" + joined, TTL);
        return pickKey;
    }

    /**
     * 核销凭证:GETDEL 原子取回即删,重放只有一次机会;
     * 凭证无效(过期/伪造/格式坏/候选集空)返回 null,调用方统一按"选择已过期"拒绝。
     */
    public PickCandidate consume(String pickKey) {
        String raw = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + pickKey);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int first = raw.indexOf('|');
        int second = first < 0 ? -1 : raw.indexOf('|', first + 1);
        if (first <= 0 || second <= first + 1 || second == raw.length() - 1) {
            return null;
        }
        String source = raw.substring(0, first);
        String openId = raw.substring(first + 1, second);
        try {
            List<Long> userIds = java.util.Arrays.stream(raw.substring(second + 1).split(","))
                    .map(Long::parseLong).toList();
            if (userIds.isEmpty()) {
                return null;
            }
            return new PickCandidate(source, openId, userIds);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
