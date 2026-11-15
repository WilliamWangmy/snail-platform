package com.snail.platform.function.monitor.metric;

import com.snail.platform.framework.redis.SnailRedisKeys;
import com.snail.platform.function.monitor.config.MonitorProperties;
import com.snail.platform.function.monitor.db.DbServerProbe;
import com.snail.platform.function.monitor.db.slow.SlowSqlCollector;
import com.snail.platform.function.system.api.LoginStatPort;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 职责:内置指标采集器——按"请求的键组"逐组取值(MXBean/Micrometer/Hikari/慢 SQL/服务端探针/探活/Redis/登录统计),
 * 只算请求的键,任何单键异常 log.warn 后跳过该键(不产样本),整组异常不影响其他组(DD-06)。
 * 上游:BuiltinMetricSource(采样器经它统一内置来源);下游:环形缓冲与采样落库。
 * 口径:cpu/heap/qps 等差分键首次只记基线不产样本;db.up/valkey.up 是唯一"失败产 0"的键;
 * db.mysql.* 依赖服务端探针快照,available=false 时不产样本。
 */
@Component
public class MetricCollector {

    private static final Logger log = LoggerFactory.getLogger(MetricCollector.class);

    private final MonitorProperties properties;
    private final ObjectProvider<DataSource> dataSource;
    private final SlowSqlCollector slowSqlCollector;
    private final DbServerProbe dbServerProbe;
    private final HealthProbe healthProbe;
    private final LoginStatPort loginStatPort;
    private final ObjectProvider<StringRedisTemplate> redisTemplate;

    /** JVM/HTTP 差分基线:首次 tick 只记基线 */
    private final Map<String, Double> lastValues = new HashMap<>();
    private volatile long lastTickEpochMs;

    public MetricCollector(MonitorProperties properties, ObjectProvider<DataSource> dataSource,
                           SlowSqlCollector slowSqlCollector, DbServerProbe dbServerProbe,
                           HealthProbe healthProbe, LoginStatPort loginStatPort,
                           ObjectProvider<StringRedisTemplate> redisTemplate,
                           ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistryProvider) {
        this.properties = properties;
        this.dataSource = dataSource;
        this.slowSqlCollector = slowSqlCollector;
        this.dbServerProbe = dbServerProbe;
        this.healthProbe = healthProbe;
        this.loginStatPort = loginStatPort;
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    /** 按请求键组采集:返回可用键值(缺失键缺席);永不抛出 */
    public Map<String, Double> collect(Set<String> keys) {
        Map<String, Double> values = new LinkedHashMap<>();
        collectJvm(keys, values);
        collectDisk(keys, values);
        collectHttp(keys, values);
        collectPool(keys, values);
        collectSql(keys, values);
        collectServer(keys, values);
        collectUp(keys, values);
        collectLogin(keys, values);
        collectOnline(keys, values);
        lastTickEpochMs = System.currentTimeMillis();
        return values;
    }

    // ---------- JVM:cpu/heap/threads/gc ----------

    private void collectJvm(Set<String> keys, Map<String, Double> out) {
        if (keys.contains(MetricKey.SYSTEM_CPU_USAGE.key()) || keys.contains(MetricKey.PROCESS_CPU_USAGE.key())) {
            try {
                com.sun.management.OperatingSystemMXBean os =
                        (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
                double system = os.getCpuLoad();
                double process = os.getProcessCpuLoad();
                if (system >= 0) {
                    out.put(MetricKey.SYSTEM_CPU_USAGE.key(), round1(system * 100));
                }
                if (process >= 0) {
                    out.put(MetricKey.PROCESS_CPU_USAGE.key(), round1(process * 100));
                }
            } catch (Exception e) {
                log.warn("monitor 采集 cpu 失败: {}", e.getMessage());
            }
        }
        if (keys.contains(MetricKey.JVM_HEAP_USED_PERCENT.key()) || keys.contains(MetricKey.JVM_HEAP_USED_MB.key())
                || keys.contains(MetricKey.JVM_NONHEAP_USED_MB.key())) {
            try {
                MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
                long heapUsed = memory.getHeapMemoryUsage().getUsed();
                long heapMax = memory.getHeapMemoryUsage().getMax();
                if (heapMax > 0) {
                    out.put(MetricKey.JVM_HEAP_USED_PERCENT.key(), round1(heapUsed * 100.0 / heapMax));
                }
                out.put(MetricKey.JVM_HEAP_USED_MB.key(), heapUsed / 1024.0 / 1024.0);
                out.put(MetricKey.JVM_NONHEAP_USED_MB.key(), memory.getNonHeapMemoryUsage().getUsed() / 1024.0 / 1024.0);
            } catch (Exception e) {
                log.warn("monitor 采集 heap 失败: {}", e.getMessage());
            }
        }
        if (keys.contains(MetricKey.JVM_THREADS_LIVE.key())) {
            try {
                out.put(MetricKey.JVM_THREADS_LIVE.key(),
                        (double) ManagementFactory.getThreadMXBean().getThreadCount());
            } catch (Exception e) {
                log.warn("monitor 采集 threads 失败: {}", e.getMessage());
            }
        }
        if (keys.contains(MetricKey.JVM_GC_PAUSE_MS.key())) {
            try {
                long totalGcTime = ManagementFactory.getGarbageCollectorMXBeans().stream()
                        .mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
                Double last = lastValues.get("gc.time");
                if (last != null && lastTickEpochMs > 0) {
                    long elapsedMinutes = Math.max(1, (System.currentTimeMillis() - lastTickEpochMs) / 60_000);
                    out.put(MetricKey.JVM_GC_PAUSE_MS.key(), (double) Math.max(0, totalGcTime - last.longValue()) / elapsedMinutes);
                }
                lastValues.put("gc.time", (double) totalGcTime);
            } catch (Exception e) {
                log.warn("monitor 采集 gc 失败: {}", e.getMessage());
            }
        }
    }

    // ---------- 磁盘 ----------

    private void collectDisk(Set<String> keys, Map<String, Double> out) {
        if (!keys.contains(MetricKey.DISK_USAGE_PERCENT.key())) {
            return;
        }
        try {
            var store = Files.getFileStore(Path.of(properties.getLogs().getFile().getDir()));
            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();
            if (total > 0) {
                out.put(MetricKey.DISK_USAGE_PERCENT.key(), round1((total - usable) * 100.0 / total));
            }
        } catch (Exception e) {
            log.warn("monitor 采集 disk 失败: {}", e.getMessage());
        }
    }

    // ---------- HTTP:Micrometer http.server.requests 差分 ----------

    private void collectHttp(Set<String> keys, Map<String, Double> out) {
        boolean needQps = keys.contains(MetricKey.HTTP_SERVER_QPS.key());
        boolean needRt = keys.contains(MetricKey.HTTP_SERVER_RT_AVG_MS.key());
        boolean needError = keys.contains(MetricKey.HTTP_SERVER_ERROR_RATE.key());
        if (!needQps && !needRt && !needError) {
            return;
        }
        try {
            io.micrometer.core.instrument.MeterRegistry registry = meterRegistry;
            if (registry == null) {
                return;
            }
            long count = 0;
            double totalNanos = 0;
            long errorCount = 0;
            for (io.micrometer.core.instrument.Timer timer : registry.find("http.server.requests").timers()) {
                count += (long) timer.count();
                totalNanos += timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS);
                String status = timer.getId().getTag("status");
                if (status != null && status.startsWith("5")) {
                    errorCount += (long) timer.count();
                }
            }
            Double lastCount = lastValues.get("http.count");
            Double lastTotal = lastValues.get("http.total");
            Double lastError = lastValues.get("http.error");
            if (lastCount != null && lastTotal != null && lastError != null && lastTickEpochMs > 0) {
                long elapsedSeconds = Math.max(1, (System.currentTimeMillis() - lastTickEpochMs) / 1000);
                long countDiff = Math.max(0, count - lastCount.longValue());
                if (needQps) {
                    out.put(MetricKey.HTTP_SERVER_QPS.key(), round1(countDiff * 1.0 / elapsedSeconds));
                }
                if (needRt && countDiff > 0) {
                    double totalDiff = Math.max(0, totalNanos - lastTotal);
                    out.put(MetricKey.HTTP_SERVER_RT_AVG_MS.key(), round1(totalDiff / countDiff / 1_000_000));
                }
                if (needError && countDiff > 0) {
                    long errorDiff = Math.max(0, errorCount - lastError.longValue());
                    out.put(MetricKey.HTTP_SERVER_ERROR_RATE.key(), round1(errorDiff * 100.0 / countDiff));
                }
            }
            lastValues.put("http.count", (double) count);
            lastValues.put("http.total", totalNanos);
            lastValues.put("http.error", (double) errorCount);
        } catch (Exception e) {
            log.warn("monitor 采集 http 失败: {}", e.getMessage());
        }
    }

    /** MeterRegistry(actuator 传入);未就绪时 http 键缺样本 */
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    // ---------- 连接池:Hikari ----------

    private void collectPool(Set<String> keys, Map<String, Double> out) {
        if (keys.stream().noneMatch(k -> k.startsWith("db.pool."))) {
            return;
        }
        try {
            DataSource ds = dataSource.getIfAvailable();
            if (ds instanceof HikariDataSource hikari && hikari.getHikariPoolMXBean() != null) {
                int active = hikari.getHikariPoolMXBean().getActiveConnections();
                int idle = hikari.getHikariPoolMXBean().getIdleConnections();
                int pending = hikari.getHikariPoolMXBean().getThreadsAwaitingConnection();
                int max = hikari.getMaximumPoolSize();
                out.put(MetricKey.DB_POOL_ACTIVE.key(), (double) active);
                out.put(MetricKey.DB_POOL_IDLE.key(), (double) idle);
                out.put(MetricKey.DB_POOL_PENDING.key(), (double) pending);
                out.put(MetricKey.DB_POOL_USAGE_PERCENT.key(), max == 0 ? 0.0 : round1(active * 100.0 / max));
            }
        } catch (Exception e) {
            log.warn("monitor 采集 pool 失败: {}", e.getMessage());
        }
    }

    // ---------- SQL:慢 SQL 采集器窗口 ----------

    private void collectSql(Set<String> keys, Map<String, Double> out) {
        try {
            if (keys.contains(MetricKey.DB_SQL_QPS.key())) {
                out.put(MetricKey.DB_SQL_QPS.key(), slowSqlCollector.execPerSec());
            }
            if (keys.contains(MetricKey.DB_SQL_RT_AVG_MS.key())) {
                out.put(MetricKey.DB_SQL_RT_AVG_MS.key(), slowSqlCollector.avgRtMs());
            }
            if (keys.contains(MetricKey.DB_SQL_SLOW_5M.key())) {
                out.put(MetricKey.DB_SQL_SLOW_5M.key(), (double) slowSqlCollector.slowCount5m());
            }
        } catch (Exception e) {
            log.warn("monitor 采集 sql 失败: {}", e.getMessage());
        }
    }

    // ---------- MySQL 服务端:最近快照 ----------

    private void collectServer(Set<String> keys, Map<String, Double> out) {
        try {
            DbServerProbe.ServerSnapshot snap = dbServerProbe.snapshot();
            if (!snap.available()) {
                return;
            }
            if (keys.contains(MetricKey.DB_MYSQL_THREADS_CONNECTED.key()) && snap.threadsConnected() != null) {
                out.put(MetricKey.DB_MYSQL_THREADS_CONNECTED.key(), snap.threadsConnected().doubleValue());
            }
            if (keys.contains(MetricKey.DB_MYSQL_THREADS_RUNNING.key()) && snap.threadsRunning() != null) {
                out.put(MetricKey.DB_MYSQL_THREADS_RUNNING.key(), snap.threadsRunning().doubleValue());
            }
            if (keys.contains(MetricKey.DB_MYSQL_ROW_LOCK_WAITS.key()) && snap.rowLockWaitsPerMin() != null) {
                out.put(MetricKey.DB_MYSQL_ROW_LOCK_WAITS.key(), snap.rowLockWaitsPerMin().doubleValue());
            }
        } catch (Exception e) {
            log.warn("monitor 采集 mysql server 失败: {}", e.getMessage());
        }
    }

    // ---------- 探活:db.up/valkey.up(唯一失败产 0 的键) ----------

    private void collectUp(Set<String> keys, Map<String, Double> out) {
        if (keys.contains(MetricKey.DB_UP.key())) {
            try {
                out.put(MetricKey.DB_UP.key(), "up".equals(healthProbe.probeDb().status()) ? 1.0 : 0.0);
            } catch (Exception e) {
                out.put(MetricKey.DB_UP.key(), 0.0);
            }
        }
        if (keys.contains(MetricKey.VALKEY_UP.key())) {
            try {
                out.put(MetricKey.VALKEY_UP.key(), "up".equals(healthProbe.probeValkey().status()) ? 1.0 : 0.0);
            } catch (Exception e) {
                out.put(MetricKey.VALKEY_UP.key(), 0.0);
            }
        }
    }

    // ---------- 登录失败:近 5 分钟 ----------

    private void collectLogin(Set<String> keys, Map<String, Double> out) {
        if (!keys.contains(MetricKey.LOGIN_FAIL_5M.key())) {
            return;
        }
        try {
            out.put(MetricKey.LOGIN_FAIL_5M.key(),
                    (double) loginStatPort.failCountSince(LocalDateTime.now().minusMinutes(5)));
        } catch (Exception e) {
            log.warn("monitor 采集 login.fail 失败: {}", e.getMessage());
        }
    }

    // ---------- 在线用户:SCAN online:idx:* 逐键 SCARD 求和 ----------

    private void collectOnline(Set<String> keys, Map<String, Double> out) {
        if (!keys.contains(MetricKey.ONLINE_USERS.key())) {
            return;
        }
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        if (template == null) {
            return;
        }
        try {
            Long total = template.execute((org.springframework.data.redis.core.RedisCallback<Long>) connection -> {
                long sum = 0;
                try (Cursor<byte[]> cursor = connection.scan(ScanOptions.scanOptions()
                        .match(SnailRedisKeys.ONLINE_INDEX + "*").count(100).build())) {
                    while (cursor.hasNext()) {
                        byte[] key = cursor.next();
                        Long size = connection.sCard(key);
                        if (size != null) {
                            sum += size;
                        }
                    }
                }
                return sum;
            });
            out.put(MetricKey.ONLINE_USERS.key(), total == null ? 0.0 : total.doubleValue());
        } catch (Exception e) {
            log.warn("monitor 采集 online.users 失败: {}", e.getMessage());
        }
    }

    private double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
