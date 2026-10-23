package com.snail.platform.framework.db;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 职责:framework-db 的配置单点。租户豁免清单与分页上限在 yml 声明,代码不硬编码任何表名。
 * 上游:snail-platform-start 的 application*.yml;下游:MybatisPlusConfig、SnailTenantLineHandler。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "snail.db")
public class SnailDbProperties {

    /** 租户过滤配置 */
    private final Tenant tenant = new Tenant();

    /** 分页配置 */
    private final Pagination pagination = new Pagination();

    @Getter
    @Setter
    public static class Tenant {

        /** 不参与租户过滤的表名清单,小写,如 sys_tenant、sys_login_log */
        private List<String> ignoreTables = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class Pagination {

        /** 单页最大行数,请求超过即按此夹紧 */
        private long maxLimit = 500;
    }
}
