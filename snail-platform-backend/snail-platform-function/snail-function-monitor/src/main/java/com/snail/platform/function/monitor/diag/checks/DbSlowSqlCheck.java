package com.snail.platform.function.monitor.diag.checks;

import com.snail.platform.function.monitor.config.MonitorProperties;
import com.snail.platform.function.monitor.db.slow.SlowSqlCollector;
import com.snail.platform.function.monitor.diag.DiagCheck;
import org.springframework.stereotype.Component;

/**
 * db.slow_sql:近 5 分钟慢 SQL 次数;>10 warn、>50 fail;采集关闭 skip。
 */
@Component
public class DbSlowSqlCheck implements DiagCheck {

    private final MonitorProperties properties;
    private final SlowSqlCollector collector;

    public DbSlowSqlCheck(MonitorProperties properties, SlowSqlCollector collector) {
        this.properties = properties;
        this.collector = collector;
    }

    @Override
    public String key() {
        return "db.slow_sql";
    }

    @Override
    public String name() {
        return "慢 SQL";
    }

    @Override
    public Item run() {
        if (!properties.getDb().getSlowSql().isEnabled()) {
            return Item.skip(key(), name(), "慢 SQL 采集已关闭", "如需观察,开启 snail.monitor.db.slow-sql.enabled");
        }
        long count = collector.slowCount5m();
        if (count > 50) {
            return Item.fail(key(), name(), null, "近 5 分钟慢 SQL " + count + " 次(>50)",
                    "在数据库面板查看慢 SQL Top 并加索引或改写 SQL");
        }
        if (count > 10) {
            return Item.warn(key(), name(), null, "近 5 分钟慢 SQL " + count + " 次(>10)",
                    "查看慢 SQL Top,关注执行计划与索引");
        }
        return Item.pass(key(), name(), null, "近 5 分钟慢 SQL " + count + " 次", null);
    }
}
