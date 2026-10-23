package com.snail.platform.framework.core.context;

/**
 * 职责:当前操作人"展示快照"(用户 ID + 登录名 + 组织 ID)的持有处,由认证过滤器在请求线程注入。
 * 与 AuditActorHolder 的分工:那个是审计列的唯一来源(只要 ID),这个供留痕类写入(日志要人名与部门,
 * 且必须请求线程取齐——异步落库线程没有 SecurityContext)。
 * 上游:snail-framework-security 认证链;下游:snail-framework-log 操作日志切面。
 */
public final class OperatorContextHolder {

    /** 操作人快照;登录时随 JWT 载荷带出,不回查库 */
    public record Operator(Long userId, String username, Long orgId) {
    }

    /** 当前请求的操作人快照;未认证线程为 null */
    private static final ThreadLocal<Operator> OPERATOR = new ThreadLocal<>();

    private OperatorContextHolder() {
    }

    public static void set(Operator operator) {
        OPERATOR.set(operator);
    }

    public static Operator get() {
        return OPERATOR.get();
    }

    public static void clear() {
        OPERATOR.remove();
    }
}
