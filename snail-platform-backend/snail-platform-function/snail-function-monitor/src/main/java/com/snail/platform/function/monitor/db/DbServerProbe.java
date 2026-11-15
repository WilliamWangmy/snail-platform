package com.snail.platform.function.monitor.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 职责:MySQL 服务端状态探针——SHOW GLOBAL STATUS / max_connections / VERSION 的每 tick 快照读。
 * 上游:MetricSampler.tick()(经 MetricCollector.collectServer);下游:MetricCollector(db.mysql.* 键)、
 * DbMonitorService(面板 server 段)。
 * 口径:每次执行 getConnection + setReadOnly + queryTimeout,失败置 available=false + lastError(截 200 字),
 * 不抛出——服务端状态不可用是降级结果;锁等待按相邻两次读数增量/间隔分钟折算,首次为 0;
 * db.server.enabled=false 时不执行,快照 available=false + lastError="disabled"。
 */
@Component
public class DbServerProbe {

    private static final Logger log = LoggerFactory.getLogger(DbServerProbe.class);

    /** 数据源 */
    private final ObjectProvider<DataSource> dataSource;

    /** 配置(只读 enabled/timeoutMs,构造注入一次) */
    private final com.snail.platform.function.monitor.config.MonitorProperties properties;

    /** 最近快照(volatile,采样线程写、请求线程读) */
    private volatile ServerSnapshot snapshot = new ServerSnapshot(false, null, null, null, null, 0L, "等待首次探测", null);

    /** 上次 Innodb_row_lock_waits 读数(增量折算) */
    private volatile Long lastRowLockWaits;

    /** 上次读数时间 */
    private volatile LocalDateTime lastCheckedAt;

    /**
     * 服务端状态快照:available/version/maxConnections/threadsConnected/threadsRunning/rowLockWaitsPerMin/lastError/checkedAt。
     */
    public record ServerSnapshot(
            /** 探测是否成功 */
            boolean available,
            /** MySQL 版本 */
            String version,
            /** max_connections */
            Long maxConnections,
            /** Threads_connected */
            Long threadsConnected,
            /** Threads_running */
            Long threadsRunning,
            /** 行锁等待折算(次/min) */
            Long rowLockWaitsPerMin,
            /** 最近错误(截 200 字);disabled 表示探针关闭 */
            String lastError,
            /** 快照时间 */
            LocalDateTime checkedAt) {
    }

    public DbServerProbe(ObjectProvider<DataSource> dataSource,
                         com.snail.platform.function.monitor.config.MonitorProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    /** 最近快照(只读,不触发探测) */
    public ServerSnapshot snapshot() {
        return snapshot;
    }

    /** 由采样 tick 调用:执行三条查询并刷新快照;全部异常吞掉转降级 */
    public void probe() {
        if (!properties.getDb().getServer().isEnabled()) {
            snapshot = new ServerSnapshot(false, null, null, null, null, 0L, "disabled", LocalDateTime.now().withNano(0));
            return;
        }
        DataSource ds = dataSource.getIfAvailable();
        if (ds == null) {
            snapshot = new ServerSnapshot(false, null, null, null, null, 0L, "数据源不可用", LocalDateTime.now().withNano(0));
            return;
        }
        long start = System.currentTimeMillis();
        try (Connection conn = ds.getConnection()) {
            conn.setReadOnly(true);
            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout((int) Math.max(1, properties.getDb().getServer().getTimeoutMs() / 1000));
                Map<String, Long> status = showGlobalStatus(stmt);
                long maxConnections = queryLong(stmt, "SELECT @@global.max_connections");
                String version = queryString(stmt, "SELECT VERSION()");
                LocalDateTime now = LocalDateTime.now().withNano(0);
                Long rowLockWaits = status.get("Innodb_row_lock_waits");
                long perMin = computeRowLockPerMin(rowLockWaits, now);
                snapshot = new ServerSnapshot(true, version, maxConnections,
                        status.get("Threads_connected"), status.get("Threads_running"), perMin, null, now);
                lastRowLockWaits = rowLockWaits;
                lastCheckedAt = now;
            }
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("monitor MySQL 服务端探测失败: {}", message);
            snapshot = new ServerSnapshot(false, null, null, null, null, 0L, truncate(message),
                    LocalDateTime.now().withNano(0));
        }
        log.debug("monitor MySQL 服务端探测耗时 {}ms", System.currentTimeMillis() - start);
    }

    private Map<String, Long> showGlobalStatus(Statement stmt) throws Exception {
        Map<String, Long> result = new LinkedHashMap<>();
        try (ResultSet rs = stmt.executeQuery(
                "SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Threads_running','Innodb_row_lock_waits')")) {
            while (rs.next()) {
                result.put(rs.getString(1), rs.getLong(2));
            }
        }
        return result;
    }

    private long queryLong(Statement stmt, String sql) throws Exception {
        try (ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private String queryString(Statement stmt, String sql) throws Exception {
        try (ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** 行锁等待增量/间隔分钟(取整);首次为 0 */
    private long computeRowLockPerMin(Long rowLockWaits, LocalDateTime now) {
        if (lastRowLockWaits == null || lastCheckedAt == null || rowLockWaits == null) {
            return 0;
        }
        long delta = Math.max(0, rowLockWaits - lastRowLockWaits);
        long minutes = Math.max(1, java.time.Duration.between(lastCheckedAt, now).toSeconds() / 60);
        return delta / minutes;
    }

    private String truncate(String message) {
        return message.length() <= 200 ? message : message.substring(0, 200);
    }
}
