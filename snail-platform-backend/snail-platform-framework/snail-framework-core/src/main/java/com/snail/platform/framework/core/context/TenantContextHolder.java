package com.snail.platform.framework.core.context;

/**
 * 职责:当前线程租户身份的唯一持有处。登录认证解析出租户后注入,
 * framework-db 的租户拦截器与 framework-log 的落库切面都只从这儿取值。
 * 上游:snail-framework-security 的认证链;下游:snail-framework-db、snail-framework-log。
 * 约束:异步线程(虚拟线程、日志落库线程)不会自动带值,必须在请求线程先捕获再显式恢复。
 */
public final class TenantContextHolder {

    /** 当前请求的租户 ID;未认证线程为 null */
    private static final ThreadLocal<Long> TENANT_ID = new ThreadLocal<>();

    /** 租户过滤旁路窗口标记;仅认证前按全局唯一键定位用户的窄路径使用 */
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);

    private TenantContextHolder() {
    }

    public static void set(Long tenantId) {
        TENANT_ID.set(tenantId);
    }

    public static Long get() {
        return TENANT_ID.get();
    }

    /** 是否处于租户过滤旁路窗口 */
    public static boolean isBypassed() {
        return BYPASS.get();
    }

    /**
     * 在无租户过滤的窄窗口内执行一段查询,结束(含异常)必恢复原状态。
     * 只允许三类路径使用:登录按全局唯一键定位用户;新增/改名时按 username 做全表查重;
     * 平台面的跨租户检索(如公告列表、平台文件列表,平台管理动作的读侧豁免)。
     * 除此之外的业务 CRUD 严禁调用。
     */
    public static <T> T withoutTenant(java.util.function.Supplier<T> action) {
        boolean previous = BYPASS.get();
        BYPASS.set(true);
        try {
            return action.get();
        } finally {
            BYPASS.set(previous);
        }
    }

    /**
     * 把当前线程的租户身份临时切到指定租户,结束(含异常)必恢复原值。
     * 只允许租户开通这一处使用:新租户的根组织/内置角色/管理员是"替别人写数据",
     * 拦截器注入与审计必须落在新租户头上,而不是发起请求的平台管理员租户。
     */
    public static <T> T asTenant(Long tenantId, java.util.function.Supplier<T> action) {
        return runAs(tenantId, action);
    }

    /**
     * 在指定租户身份下执行一段流程引擎回调,结束(含异常)必恢复原值。
     * 只允许 framework-workflow 的租户桥使用:Flowable 的定时器与异步作业线程不经过请求链,
     * 登录人与租户上下文都是空的,而回调要替"该流程实例所属的租户"写库——这个租户 ID 只能由
     * 引擎侧的实例/作业反查得到,不接受调用方自选,所以它与 asTenant(主动替他人播种)语义不同,
     * 单独登记为约定 §6 的第四类合法旁路,并由 ArchUnit 把调用点锁在租户桥一处。
     * 租户 ID 为空即拒绝:反查不到租户说明实例不属于任何租户,宁可失败也不让数据落错家。
     */
    public static <T> T runAsEngineTenant(Long tenantId, java.util.function.Supplier<T> action) {
        if (tenantId == null) {
            throw new IllegalStateException("引擎回调缺少租户上下文:目标流程实例的 tenantId 为空,拒绝执行");
        }
        return runAs(tenantId, action);
    }

    /** 两个切换入口共用的线程身份换入换出:恢复时区分"原本有值"与"原本无值",无值必须 remove 而非 set(null) */
    private static <T> T runAs(Long tenantId, java.util.function.Supplier<T> action) {
        Long previous = TENANT_ID.get();
        TENANT_ID.set(tenantId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                TENANT_ID.remove();
            } else {
                TENANT_ID.set(previous);
            }
        }
    }

    /** 请求收尾必须清理,防止线程复用导致租户串台 */
    public static void clear() {
        TENANT_ID.remove();
        BYPASS.remove();
    }
}
