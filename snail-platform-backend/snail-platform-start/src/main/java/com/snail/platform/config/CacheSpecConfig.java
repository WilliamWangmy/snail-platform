package com.snail.platform.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 职责:缓存分规格——把原来 spring.cache 单一 spec 拆成按缓存名定制的容量与过期口径。
 * 依据:userSession 是会话态(心跳每请求命中、写后读多),用 expireAfterAccess 续期 + 大容量;
 * userPerms 随角色/菜单变更被 CacheEvictor 全量失效,容量对齐用户量级;
 * dictItem/menuTree/tenantConfig/orgSubtree 是配置类小缓存,条目数远小于用户量,小容量即可。
 * 口径:缓存名单点声明——新增缓存名必须来这里登记规格,业务侧不各自建缓存。
 * 上游:CacheEvictor、各 @Cacheable;下游:Caffeine。
 */
@Configuration
public class CacheSpecConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        // 会话态:访问续期;30 分钟无访问即逐出,容量防超载击穿堆内存
        manager.registerCustomCache("userSession", Caffeine.newBuilder()
                .maximumSize(20_000)
                .expireAfterAccess(Duration.ofMinutes(30))
                .build());
        // 登录人权限快照:写后 10 分钟兜底过期,主动失效交 CacheEvictor
        manager.registerCustomCache("userPerms", Caffeine.newBuilder()
                .maximumSize(5_000)
                .expireAfterWrite(Duration.ofMinutes(10))
                .build());
        // 配置类小缓存:条目数量级小,统一 500/10m
        for (String name : new String[] {"dictItem", "menuTree", "tenantConfig", "orgSubtree"}) {
            manager.registerCustomCache(name, Caffeine.newBuilder()
                    .maximumSize(500)
                    .expireAfterWrite(Duration.ofMinutes(10))
                    .build());
        }
        return manager;
    }
}
