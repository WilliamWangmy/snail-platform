package com.snail.platform.framework.core.context;

/**
 * 职责:当前登录人快照的请求级持有处,由认证过滤器在请求线程注入、请求收尾清理。
 * 登录人快照是操作人信息的唯一来源:审计列取 userId(),操作日志取 username/orgId,自助操作保护也取 userId();
 * 全平台不再另设操作人 ThreadLocal。
 * 与 SnailPrincipal 的分工:那个是 JWT 里的身份四元组(SecurityContext 主体),这个是补全后的人员/组织/租户/角色快照,
 * 只读不写,不参与判权。
 * 上游:snail-framework-security 认证链;下游:framework-db 审计填充、framework-log 留痕切面、业务侧展示与自助保护。
 */
public final class LoginUserContextHolder {

    /** 当前登录人快照;未认证线程为 null */
    private static final ThreadLocal<LoginUser> LOGIN_USER = new ThreadLocal<>();

    private LoginUserContextHolder() {
    }

    public static void set(LoginUser loginUser) {
        LOGIN_USER.set(loginUser);
    }

    public static LoginUser get() {
        return LOGIN_USER.get();
    }

    /** 当前登录人 ID;未认证线程为 null——审计填充与自助操作判定的统一口径 */
    public static Long userId() {
        LoginUser loginUser = LOGIN_USER.get();
        return loginUser == null ? null : loginUser.userId();
    }

    /** 请求收尾必须清理,防止线程复用导致登录人串台 */
    public static void clear() {
        LOGIN_USER.remove();
    }
}
