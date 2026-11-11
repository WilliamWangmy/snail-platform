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

    /** 分端登录用户统计,按租户与登录端的永久 SET:snail:stat:login:{tenantId}:{scene} */
    public static final String STAT_LOGIN = PREFIX + "stat:login:";

    /** 分端日活跃统计,按租户与日期的 SET(48h 过期):snail:stat:active:{tenantId}:{yyyyMMdd} */
    public static final String STAT_ACTIVE = PREFIX + "stat:active:";

    private SnailRedisKeys() {
    }

    /** 在线会话详情键:snail:online:{tenantId}:{userId} */
    public static String online(Long tenantId, Long userId) {
        return ONLINE + tenantId + ":" + userId;
    }

    /** 租户在线会话索引键(集合成员为用户 ID):snail:online:idx:{tenantId} */
    public static String onlineIndex(Long tenantId) {
        return ONLINE_INDEX + tenantId;
    }

    /**
     * 分端登录用户统计键:snail:stat:login:{tenantId}:{scene}。
     * 永久 SET,登录成功收尾 SADD;读侧 SCARD,键丢失由 sys_login_log lazy 自愈回填(统计层设计 §5.6),
     * 无外部消费依赖,可整段清理重自愈。
     */
    public static String statLogin(Long tenantId, String scene) {
        return STAT_LOGIN + tenantId + ":" + scene;
    }

    /** 分端日活跃统计键:snail:stat:active:{tenantId}:{yyyyMMdd},48h TTL,预留展示位 */
    public static String statActive(Long tenantId, String day) {
        return STAT_ACTIVE + tenantId + ":" + day;
    }
}
