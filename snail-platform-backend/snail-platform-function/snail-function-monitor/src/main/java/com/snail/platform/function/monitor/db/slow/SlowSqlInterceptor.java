package com.snail.platform.function.monitor.db.slow;

import com.snail.platform.function.monitor.config.MonitorProperties;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 职责:MyBatis Executor 拦截器——每次执行只计时(正常路径零额外 SQL),超阈值才归一化并聚合(§5.2.2)。
 * 上游:MyBatis-Plus starter 自动收集 Interceptor Bean(MonitorAutoConfiguration @Bean 注册,不改拦截器链);
 * 下游:SlowSqlCollector(record/recordSlow)。
 * 口径:monitor 自身 mapper(com.snail.platform.function.monitor. 前缀)整体跳过防自观测污染(DD-07);
 * 观测失败一律吞掉不影响原执行;运行期 enabled=false 时首行直接放行不计时;
 * Bean 装配由 MonitorAutoConfiguration 按 db.slow-sql.enabled 条件注册(关闭即摘除,qps 键断点)。
 */
@Intercepts({
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                        CacheKey.class, BoundSql.class}),
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class})
})
public class SlowSqlInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(SlowSqlInterceptor.class);

    /** monitor 自身 mapper 前缀:采样/查询 SQL 不进 qps 与慢 SQL 统计 */
    private static final String SELF_PREFIX = "com.snail.platform.function.monitor.";

    private final SlowSqlCollector collector;
    private final MonitorProperties properties;

    public SlowSqlInterceptor(SlowSqlCollector collector, MonitorProperties properties) {
        this.collector = collector;
        this.properties = properties;
        collector.configure(properties.getDb().getSlowSql().getThresholdMs(),
                properties.getDb().getSlowSql().getMaxDigests(),
                properties.getDb().getSlowSql().getRecentSize(),
                properties.getDb().getSlowSql().getMaxSqlLength());
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        if (!properties.isEnabled() || !properties.getDb().getSlowSql().isEnabled()) {
            return invocation.proceed();
        }
        long start = System.nanoTime();
        try {
            return invocation.proceed();
        } finally {
            long cost = System.nanoTime() - start;
            try {
                String id = ((MappedStatement) invocation.getArgs()[0]).getId();
                if (!id.startsWith(SELF_PREFIX)) {
                    collector.record(id, cost);
                    if (cost / 1_000_000 >= properties.getDb().getSlowSql().getThresholdMs()) {
                        collector.recordSlow(id, cost, resolveSql(invocation));
                    }
                }
            } catch (Exception ignore) {
                // 观测失败不影响原执行
            }
        }
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    /** 解析 SQL:6 参 query 直接用 BoundSql;4 参 query/update 慢路径才 getBoundSql */
    private String resolveSql(Invocation invocation) {
        Object[] args = invocation.getArgs();
        if (args.length >= 6 && args[5] instanceof BoundSql boundSql) {
            return boundSql.getSql();
        }
        MappedStatement ms = (MappedStatement) args[0];
        return ms.getBoundSql(args[1]).getSql();
    }
}
