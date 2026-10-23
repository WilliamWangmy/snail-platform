package com.snail.platform.framework.core.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * 职责:Spring Cache 注解体系的唯一启用点(@Cacheable/@CacheEvict 生效的总闸)。
 * 缓存实现与规格不在这里定:类型 caffeine、名单与 TTL 由 start 的 application.yml 单点声明,
 * 本类只开闸,不建 CacheManager、不配缓存条目——避免出现第二处配置来源。
 * 上游:装配扫描(包级覆盖 framework 构件);下游:各业务模块的注解化查询。
 */
@Configuration
@EnableCaching
public class SnailCacheConfig {
}
