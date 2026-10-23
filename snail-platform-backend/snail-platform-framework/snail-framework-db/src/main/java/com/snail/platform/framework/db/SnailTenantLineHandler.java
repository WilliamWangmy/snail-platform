package com.snail.platform.framework.db;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.snail.platform.framework.core.context.TenantContextHolder;
import java.util.Locale;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;

/**
 * 职责:决定租户拦截器给哪些语句注入 tenant_id、注入谁的值、哪些表豁免。
 * 值只认 TenantContextHolder;上下文缺失视为编程错误直接抛出,宁可崩也不放行一条无租户条件的 SQL。
 * 上游:MybatisPlusConfig 装配进拦截器链;下游:JSqlParser 改写 SQL。
 */
public class SnailTenantLineHandler implements TenantLineHandler {

    /** 租户判别列名,全库口径统一 */
    private static final String TENANT_COLUMN = "tenant_id";

    /** 豁免清单配置 */
    private final SnailDbProperties properties;

    public SnailTenantLineHandler(SnailDbProperties properties) {
        this.properties = properties;
    }

    @Override
    public Expression getTenantId() {
        Long tenantId = TenantContextHolder.get();
        if (tenantId == null) {
            throw new IllegalStateException("租户上下文未注入:租户托管表的查询必须在认证后的线程上执行");
        }
        return new LongValue(tenantId);
    }

    @Override
    public String getTenantIdColumn() {
        return TENANT_COLUMN;
    }

    @Override
    public boolean ignoreTable(String tableName) {
        // 旁路窗口(登录窄路径)内一切表豁免;窗口由 TenantContextHolder.withoutTenant 成对管理
        if (TenantContextHolder.isBypassed()) {
            return true;
        }
        String normalized = tableName.toLowerCase(Locale.ROOT);
        return properties.getTenant().getIgnoreTables().stream()
                .anyMatch(t -> t.toLowerCase(Locale.ROOT).equals(normalized));
    }
}
