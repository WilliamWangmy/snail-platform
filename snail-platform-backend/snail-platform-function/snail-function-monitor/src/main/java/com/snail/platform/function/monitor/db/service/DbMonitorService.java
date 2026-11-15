package com.snail.platform.function.monitor.db.service;

import com.snail.platform.function.monitor.config.MonitorProperties;
import com.snail.platform.function.monitor.db.DbServerProbe;
import com.snail.platform.function.monitor.db.dto.DbSummaryVO;
import com.snail.platform.function.monitor.db.dto.SlowSqlDigestVO;
import com.snail.platform.function.monitor.db.dto.SlowSqlEventVO;
import com.snail.platform.function.monitor.db.dto.SlowSqlQueryVO;
import com.snail.platform.function.monitor.db.slow.SlowSqlCollector;
import com.zaxxer.hikari.HikariDataSource;
import java.time.LocalDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 职责:数据库面板聚合服务——池读数(Hikari MXBean 实时,内存读无 I/O)、慢 SQL 统计与 Top/最近事件、
 * 服务端最近快照三段组装。
 * 上游:DbController;下游:HikariDataSource(HikariPoolMXBean)、SlowSqlCollector、DbServerProbe。
 * 口径:池每次请求实时读;非 Hikari/池未启动 pool.available=false 数值 null;
 * db.slow-sql.enabled=false 时 sql.enabled=false 计数 null、slowTop/recent 为空;
 * server 段只读采样 tick 快照不触发探测。
 */
@Service
public class DbMonitorService {

    private final MonitorProperties properties;
    private final ObjectProvider<DataSource> dataSource;
    private final SlowSqlCollector slowSqlCollector;
    private final DbServerProbe dbServerProbe;

    public DbMonitorService(MonitorProperties properties, ObjectProvider<DataSource> dataSource,
                            SlowSqlCollector slowSqlCollector, DbServerProbe dbServerProbe) {
        this.properties = properties;
        this.dataSource = dataSource;
        this.slowSqlCollector = slowSqlCollector;
        this.dbServerProbe = dbServerProbe;
    }

    /** 面板 summary:pool(实时) + sql(实时) + server(最近快照) + slowTop(前 5) */
    public DbSummaryVO summary() {
        return new DbSummaryVO(pool(), sqlStats(), serverSnapshot(), top(5), LocalDateTime.now().withNano(0));
    }

    /** 慢 SQL 详查:top 前 20 + recent 前 50 */
    public SlowSqlQueryVO slow() {
        return new SlowSqlQueryVO(slowSqlCollector.thresholdMs(), slowSqlCollector.since(), top(20), recent(50));
    }

    // ---------- 池 ----------

    private DbSummaryVO.Pool pool() {
        try {
            DataSource ds = dataSource.getIfAvailable();
            if (ds instanceof HikariDataSource hikari && hikari.getHikariPoolMXBean() != null) {
                int active = hikari.getHikariPoolMXBean().getActiveConnections();
                int idle = hikari.getHikariPoolMXBean().getIdleConnections();
                int pending = hikari.getHikariPoolMXBean().getThreadsAwaitingConnection();
                int max = hikari.getMaximumPoolSize();
                int total = active + idle;
                double usage = max == 0 ? 0.0 : Math.round(active * 100.0 / max * 10) / 10.0;
                return new DbSummaryVO.Pool(true, active, idle, total, max, pending, usage);
            }
        } catch (Exception ignored) {
            // 池读数失败按不可用降级
        }
        return new DbSummaryVO.Pool(false, null, null, null, null, null, null);
    }

    // ---------- 慢 SQL ----------

    private DbSummaryVO.SqlStats sqlStats() {
        boolean enabled = properties.getDb().getSlowSql().isEnabled();
        if (!enabled) {
            return new DbSummaryVO.SqlStats(false, null, null, null, null, null, null);
        }
        return new DbSummaryVO.SqlStats(true, slowSqlCollector.execPerSec(), slowSqlCollector.avgRtMs(),
                slowSqlCollector.thresholdMs(), slowSqlCollector.slowCount5m(), slowSqlCollector.slowCountTotal(),
                slowSqlCollector.since());
    }

    private List<SlowSqlDigestVO> top(int limit) {
        if (!properties.getDb().getSlowSql().isEnabled()) {
            return List.of();
        }
        return slowSqlCollector.top(limit).stream()
                .map(d -> new SlowSqlDigestVO(d.digestId(), d.sql(), d.mapperId(), d.slowCount(),
                        d.maxMs(), d.avgMs(), d.lastMs(), d.lastTime()))
                .toList();
    }

    private List<SlowSqlEventVO> recent(int limit) {
        if (!properties.getDb().getSlowSql().isEnabled()) {
            return List.of();
        }
        return slowSqlCollector.recent().stream().limit(limit)
                .map(e -> new SlowSqlEventVO(e.time(), e.costMs(), e.digestId(), e.sql(), e.mapperId(), e.traceId()))
                .toList();
    }

    // ---------- 服务端 ----------

    private DbSummaryVO.Server serverSnapshot() {
        DbServerProbe.ServerSnapshot snap = dbServerProbe.snapshot();
        return new DbSummaryVO.Server(snap.available(), snap.version(), snap.maxConnections(),
                snap.threadsConnected(), snap.threadsRunning(), snap.rowLockWaitsPerMin(),
                snap.lastError(), snap.checkedAt());
    }
}
