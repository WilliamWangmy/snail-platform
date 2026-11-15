package com.snail.platform.function.monitor.metric;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 职责:监控指标键枚举——采样、缓冲、落库、查询、告警规则可引用的键的冻结清单(一期 25 键,主方案 §5.1)。
 * 上游:MetricSampler 采样决策、MetricHistoryQuery 校验;下游:MetricCollector(内置取值)、
 * 告警规则 metric 校验、前端指标多选。
 * 口径:key 即落库 sys_monitor_metric.metric 的取值,冻结不可改;采集不可用的键不产样本,
 * 唯一例外 db.up/valkey.up(失败产 0,用于「连不上」告警);category 是前端分组展示用。
 */
public enum MetricKey {

    SYSTEM_CPU_USAGE("system.cpu.usage", "%", "system"),
    PROCESS_CPU_USAGE("process.cpu.usage", "%", "system"),
    JVM_HEAP_USED_PERCENT("jvm.heap.used.percent", "%", "jvm"),
    JVM_HEAP_USED_MB("jvm.heap.used.mb", "MB", "jvm"),
    JVM_NONHEAP_USED_MB("jvm.nonheap.used.mb", "MB", "jvm"),
    JVM_THREADS_LIVE("jvm.threads.live", "个", "jvm"),
    JVM_GC_PAUSE_MS("jvm.gc.pause.ms", "ms/min", "jvm"),
    DISK_USAGE_PERCENT("disk.usage.percent", "%", "system"),
    HTTP_SERVER_QPS("http.server.qps", "次/s", "http"),
    HTTP_SERVER_RT_AVG_MS("http.server.rt.avg.ms", "ms", "http"),
    HTTP_SERVER_ERROR_RATE("http.server.error.rate", "%", "http"),
    DB_POOL_ACTIVE("db.pool.active", "个", "db"),
    DB_POOL_IDLE("db.pool.idle", "个", "db"),
    DB_POOL_PENDING("db.pool.pending", "个", "db"),
    DB_POOL_USAGE_PERCENT("db.pool.usage.percent", "%", "db"),
    DB_SQL_QPS("db.sql.qps", "次/s", "db"),
    DB_SQL_RT_AVG_MS("db.sql.rt.avg.ms", "ms", "db"),
    DB_SQL_SLOW_5M("db.sql.slow.5m", "次", "db"),
    DB_MYSQL_THREADS_CONNECTED("db.mysql.threads.connected", "个", "db"),
    DB_MYSQL_THREADS_RUNNING("db.mysql.threads.running", "个", "db"),
    DB_MYSQL_ROW_LOCK_WAITS("db.mysql.row.lock.waits", "次/min", "db"),
    DB_UP("db.up", "0/1", "up"),
    VALKEY_UP("valkey.up", "0/1", "up"),
    ONLINE_USERS("online.users", "人", "login"),
    LOGIN_FAIL_5M("login.fail.5m", "次", "login");

    /** 指标键:与 sys_monitor_metric.metric 一致,冻结 */
    private final String key;

    /** 展示单位 */
    private final String unit;

    /** 分组:system/jvm/http/db/up/login */
    private final String category;

    MetricKey(String key, String unit, String category) {
        this.key = key;
        this.unit = unit;
        this.category = category;
    }

    public String key() {
        return key;
    }

    public String unit() {
        return unit;
    }

    public String category() {
        return category;
    }

    /** 全部键的集合(采样决策与校验白名单) */
    public static Set<String> all() {
        return Arrays.stream(values()).map(MetricKey::key).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 键 → 枚举;非法键返回 empty,由调用方 400 */
    public static Optional<MetricKey> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        for (MetricKey metricKey : values()) {
            if (metricKey.key.equals(key)) {
                return Optional.of(metricKey);
            }
        }
        return Optional.empty();
    }

    /** 键是否合法(采样映射配置过滤用) */
    public static boolean isValidKey(String key) {
        return fromKey(key).isPresent();
    }
}
