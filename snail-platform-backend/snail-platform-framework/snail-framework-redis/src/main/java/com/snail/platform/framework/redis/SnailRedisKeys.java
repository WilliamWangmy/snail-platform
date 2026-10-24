package com.snail.platform.framework.redis;

/**
 * 职责:Redis 键名的唯一收口处——前缀与键段格式全平台单点,业务侧不手拼裸键。
 * 口径:全部键以 snail: 开头;认证/限频段为 snail:auth:*,在线会话段为 snail:online:*。
 * 上游:各业务组件;下游:Valkey。
 */
public final class SnailRedisKeys {

    /** 全平台统一键前缀 */
    public static final String PREFIX = "snail:";

    /** 自助改密失败计数,按用户 ID:snail:auth:profile-fail:{userId} */
    public static final String PROFILE_PWD_FAIL = PREFIX + "auth:profile-fail:";

    /** 在线会话详情,按租户与用户:snail:online:{tenantId}:{userId} */
    public static final String ONLINE = PREFIX + "online:";

    /** 在线会话索引,按租户的 SET:snail:online:idx:{tenantId} */
    public static final String ONLINE_INDEX = PREFIX + "online:idx:";

    private SnailRedisKeys() {
    }

    public static String online(Long tenantId, Long userId) {
        return ONLINE + tenantId + ":" + userId;
    }

    public static String onlineIndex(Long tenantId) {
        return ONLINE_INDEX + tenantId;
    }
}
