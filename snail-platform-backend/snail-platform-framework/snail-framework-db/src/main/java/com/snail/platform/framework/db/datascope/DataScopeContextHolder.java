package com.snail.platform.framework.db.datascope;

import java.util.function.Supplier;

/**
 * 职责:数据权限窄窗口的持有处——只有开窗线程内的 SELECT 会被注入权限谓词,窗口外(按 ID 取详情、
 * 删除前置检查、关联计数)不受影响,这是"列表按 scope 过滤"与"程序内部查询"的分界线。
 * 上游:业务 Service 的列表查询一律经 filter(...) 执行;下游:SnailDataScopeInnerInterceptor。
 * 约束:与 TenantContextHolder 同线程模型,异步线程不自动带值,必须显式传递。
 */
public final class DataScopeContextHolder {

    /** 当前窗口的权限谓词;窗口外为 null,拦截器据此跳过 */
    private static final ThreadLocal<DataScopeFilter> FILTER = new ThreadLocal<>();

    private DataScopeContextHolder() {
    }

    public static DataScopeFilter get() {
        return FILTER.get();
    }

    /**
     * 在权限窗口内执行一段查询;filter 为 null(全部档)直接透传执行,不开窗。
     * 结束(含异常)必关窗,谓词不会漏到下一段查询。
     */
    public static <T> T filter(DataScopeFilter filter, Supplier<T> action) {
        if (filter == null) {
            return action.get();
        }
        DataScopeFilter previous = FILTER.get();
        FILTER.set(filter);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                FILTER.remove();
            } else {
                FILTER.set(previous);
            }
        }
    }
}
