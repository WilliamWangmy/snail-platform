package com.snail.platform.framework.db;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.snail.platform.framework.db.datascope.SnailDataScopeInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 职责:数据库横切能力的唯一装配点——租户过滤 → 数据权限 → 全表攻击拦截 → 分页,顺序不能换,
 * 分页 count 要先拿到租户与权限条件改写后的 SQL;方言 DbType.MYSQL 只在这里固定一次。
 * 全表攻击拦截在最后兜底:一条忘写 WHERE 的全表 update/delete 在这里被直接拒绝,
 * 不依赖业务侧纪律(ArchUnit 只拦 update(null, wrapper) 源码形态,运行时形态由这里把守)。
 * 上游:application*.yml 的 snail.db 配置;下游:所有用 MyBatis-Plus 的业务模块。
 * 不做:任何表名、任何业务 Mapper 的特判。
 */
@Configuration
@MapperScan("com.snail.platform.**.mapper")
@EnableConfigurationProperties(SnailDbProperties.class)
public class MybatisPlusConfig {

    /** 租户判定处理器,豁免清单来自 SnailDbProperties */
    @Bean
    public SnailTenantLineHandler snailTenantLineHandler(SnailDbProperties properties) {
        return new SnailTenantLineHandler(properties);
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(SnailDbProperties properties,
                                                         SnailTenantLineHandler tenantLineHandler) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(tenantLineHandler));
        // 数据权限固定在租户之后、分页之前:分页 count 拿的必须是权限改写后的 SQL
        interceptor.addInnerInterceptor(new SnailDataScopeInnerInterceptor());
        // 全表 update/delete 兜底:必须在分页之前,拦截的是改写后的最终 SQL
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(properties.getPagination().getMaxLimit());
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }

    /** 审计四件套自动填充 */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new SnailAuditMetaObjectHandler();
    }
}
