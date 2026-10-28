package com.snail.platform.function.system.support;

import java.util.Collection;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 职责:缓存失效的编程式出口——只在数据真的变了的分支调用,清动作一律排到事务提交之后。
 * 为什么不用 @CacheEvict:注解在方法正常返回时就清,幂等短路分支跟着白清一次;清还发生在提交之前,
 * 并发读可能把旧值重新回填,这次失效就丢了(TTL 10 分钟才兜底)。
 * 键口径:会话与字典的键都带租户段,失效按"租户:主体"逐个点清,不提供跨租户的全清手段。
 * 上游:system 域各 service;下游:CacheManager(实现与规格由 start 的 application.yml 单点声明)。
 */
@Component
public class CacheEvictor {

    /** Spring Cache 抽象,按缓存名单点取 */
    private final CacheManager cacheManager;

    public CacheEvictor(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /** 单键失效:key 必须与 @Cacheable 生成的字面量完全一致,口径经 {@link #tenantKey} 拼 */
    public void evict(String cacheName, Object key) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            return;
        }
        afterCommit(() -> cache.evict(key));
    }

    /** 一组键失效:只登记一次提交回调,提交后逐个点清;空集直接跳过 */
    public void evictEach(String cacheName, Collection<?> keys) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null || keys.isEmpty()) {
            return;
        }
        afterCommit(() -> keys.forEach(cache::evict));
    }

    /** 带租户段的键口径:生产端的 SpEL 与这里的拼法必须一致,都是"租户 ID:主体" */
    public static String tenantKey(Object tenantId, Object id) {
        return tenantId + ":" + id;
    }

    /**
     * 提交后再清:提交前清掉的条目可能被并发读用旧值重新回填,等于没清。
     * 无事务上下文(纯读路径或未开事务的写)时立即清。
     */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
