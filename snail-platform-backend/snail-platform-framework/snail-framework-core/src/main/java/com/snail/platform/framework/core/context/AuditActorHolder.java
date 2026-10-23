package com.snail.platform.framework.core.context;

/**
 * 职责:当前操作人用户 ID 的持有处,审计字段自动填充的唯一来源。
 * 上游:snail-framework-security 认证链(登录成功后注入);下游:snail-framework-db 的审计填充器。
 * 未认证线程(应用启动、Flyway 迁移)为 null,审计列允许留空。
 */
public final class AuditActorHolder {

    /** 当前操作人用户 ID;系统级线程为 null */
    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    private AuditActorHolder() {
    }

    public static void set(Long userId) {
        USER_ID.set(userId);
    }

    public static Long get() {
        return USER_ID.get();
    }

    public static void clear() {
        USER_ID.remove();
    }
}
