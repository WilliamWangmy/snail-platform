package com.snail.platform.function.monitor.diag.checks;

import com.snail.platform.function.monitor.db.dto.DbSummaryVO;
import com.snail.platform.function.monitor.db.service.DbMonitorService;
import com.snail.platform.function.monitor.diag.DiagCheck;
import org.springframework.stereotype.Component;

/**
 * db.pool:Hikari 使用率与等待线程;>80% 或 pending>0 warn,>90% 或池不可用 fail。
 */
@Component
public class DbPoolCheck implements DiagCheck {

    private final DbMonitorService dbMonitorService;

    public DbPoolCheck(DbMonitorService dbMonitorService) {
        this.dbMonitorService = dbMonitorService;
    }

    @Override
    public String key() {
        return "db.pool";
    }

    @Override
    public String name() {
        return "连接池水位";
    }

    @Override
    public Item run() {
        DbSummaryVO.Pool pool = dbMonitorService.summary().pool();
        if (!pool.available()) {
            return Item.fail(key(), name(), null, "连接池不可读(非 Hikari 或池未启动)",
                    "确认数据源实现为 Hikari 且应用已完成启动");
        }
        if (pool.usagePercent() != null && pool.usagePercent() > 90) {
            return Item.fail(key(), name(), null, "使用率 " + pool.usagePercent() + "%(>90%),等待线程 " + pool.pending(),
                    "评估调大 maximum-pool-size 或排查连接泄漏");
        }
        if ((pool.usagePercent() != null && pool.usagePercent() > 80) || (pool.pending() != null && pool.pending() > 0)) {
            return Item.warn(key(), name(), null, "使用率 " + pool.usagePercent() + "%,等待线程 " + pool.pending(),
                    "关注慢 SQL 与事务时长,必要时扩池");
        }
        return Item.pass(key(), name(), null,
                "使用率 " + pool.usagePercent() + "%,active=" + pool.active() + "/" + pool.max(), null);
    }
}
