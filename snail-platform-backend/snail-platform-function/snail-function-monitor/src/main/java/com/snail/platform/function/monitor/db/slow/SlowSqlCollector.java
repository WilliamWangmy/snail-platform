package com.snail.platform.function.monitor.db.slow;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/**
 * 职责:SQL 执行观测的有界聚合器——全部执行的计数/窗口(qps/rt/慢 SQL 5 分钟数)与
 * 超阈值 SQL 的摘要聚合(Top N)/最近事件(带 traceId)。由 SlowSqlInterceptor 喂数,MetricCollector/DbMonitorService 读。
 * 上游:SlowSqlInterceptor(请求线程计时);下游:MetricCollector(db.sql.* 键)、DbMonitorService(面板)。
 * 口径:计数用 LongAdder、摘要用 ConcurrentHashMap(compute 原子累加),不锁全局;
 * 窗口推进靠 record 时按当前秒/分钟取桶(无后台线程);进程重启清零;所有结构有界
 * (摘要 maxDigests LRU、事件 recentSize)。
 */
@Component
public class SlowSqlCollector {

    /** 自启动累计执行次数 */
    private final LongAdder totalExec = new LongAdder();

    /** 自启动累计耗时(纳秒) */
    private final LongAdder totalCostNanos = new LongAdder();

    /** 最近 60 秒:1 秒桶(序号=epochSecond) */
    private final Bucket[] secondBuckets = new Bucket[60];

    /** 最近 5 分钟:1 分钟桶(序号=epochMinute) */
    private final Bucket[] minuteBuckets = new Bucket[5];

    /** 慢 SQL 摘要:digestId → 聚合行,容量 maxDigests,LRU 淘汰 */
    private final Map<String, SlowSqlDigest> digests = new ConcurrentHashMap<>();

    /** 最近慢 SQL 事件(带 traceId),容量 recentSize */
    private final ConcurrentLinkedDeque<SlowSqlEvent> recentEvents = new ConcurrentLinkedDeque<>();

    /** 自启动累计慢 SQL 次数 */
    private final AtomicLong slowCountTotal = new AtomicLong();

    /** 采集器启动时间(since 出参) */
    private final LocalDateTime since = LocalDateTime.now().withNano(0);

    /** 阈值与容量(由拦截器侧配置注入口径,写值在首次 record 前) */
    private volatile long thresholdMs = 1000;
    private volatile int maxDigests = 500;
    private volatile int recentSize = 100;
    private volatile int maxSqlLength = 500;

    /** 秒/分钟桶:exec 与累计耗时 */
    private static final class Bucket {
        volatile long seq = -1;
        final LongAdder count = new LongAdder();
        final LongAdder costNanos = new LongAdder();
    }

    /**
     * 慢 SQL 摘要聚合行:归一化 SQL 的按 digest 聚合(次数/最大耗时/均值/最近一次)。
     */
    public static final class SlowSqlDigest {
        /** 摘要 ID(归一化 SQL SHA-256 前 8 位) */
        public final String digestId;
        /** 归一化 SQL 文本(≤maxSqlLength) */
        public final String sql;
        /** mapper 全限定 ID */
        public final String mapperId;
        /** 慢命中次数 */
        public final LongAdder slowCount = new LongAdder();
        /** 最大耗时(ms) */
        volatile long maxMs;
        /** 累计耗时(ms,算均值用) */
        volatile long sumMs;
        /** 最近一次耗时(ms) */
        volatile long lastMs;
        /** 最近一次命中时间 */
        volatile LocalDateTime lastTime;
        /** LRU 判据:最近命中纳秒时间戳 */
        volatile long lastHitNanos;

        SlowSqlDigest(String digestId, String sql, String mapperId, long costMs, LocalDateTime time, long hitNanos) {
            this.digestId = digestId;
            this.sql = sql;
            this.mapperId = mapperId;
            this.maxMs = costMs;
            this.sumMs = costMs;
            this.lastMs = costMs;
            this.lastTime = time;
            this.lastHitNanos = hitNanos;
        }

        public String digestId() {
            return digestId;
        }

        public String sql() {
            return sql;
        }

        public String mapperId() {
            return mapperId;
        }

        public long slowCount() {
            return slowCount.sum();
        }

        public long maxMs() {
            return maxMs;
        }

        public long avgMs() {
            long count = slowCount.sum();
            return count == 0 ? 0 : sumMs / count;
        }

        public long lastMs() {
            return lastMs;
        }

        public LocalDateTime lastTime() {
            return lastTime;
        }
    }

    /**
     * 最近慢 SQL 事件:单次执行明细(带 traceId,异步/调度线程为 null)。
     */
    public record SlowSqlEvent(
            /** 执行时间 */
            LocalDateTime time,
            /** 耗时(ms) */
            long costMs,
            /** 摘要 ID */
            String digestId,
            /** 归一化 SQL */
            String sql,
            /** mapper 全限定 ID */
            String mapperId,
            /** 链路 ID(MDC);异步/调度线程为 null */
            String traceId) {
    }

    /** 配置注入(拦截器注册时调用,启动期一次) */
    public void configure(long thresholdMs, int maxDigests, int recentSize, int maxSqlLength) {
        this.thresholdMs = thresholdMs;
        this.maxDigests = maxDigests;
        this.recentSize = recentSize;
        this.maxSqlLength = maxSqlLength;
    }

    public long thresholdMs() {
        return thresholdMs;
    }

    /** 所有执行:计数与秒/分钟桶(供 qps/rt/慢 SQL 5 分钟窗口) */
    public void record(String mapperId, long costNanos) {
        totalExec.increment();
        totalCostNanos.add(costNanos);
        long epochSecond = System.currentTimeMillis() / 1000;
        bucket(secondBuckets, epochSecond, 60).count.increment();
        bucket(secondBuckets, epochSecond, 60).costNanos.add(costNanos);
        long epochMinute = epochSecond / 60;
        bucket(minuteBuckets, epochMinute, 5).count.increment();
    }

    /** 仅超阈值执行:归一化 + 摘要聚合 + 最近事件 */
    public void recordSlow(String mapperId, long costNanos, String sql) {
        long costMs = costNanos / 1_000_000;
        LocalDateTime now = LocalDateTime.now().withNano(0);
        String normalized = SlowSqlNormalizer.normalize(sql, maxSqlLength);
        String digestId = SlowSqlNormalizer.digest(normalized);
        long hitNanos = System.nanoTime();
        SlowSqlDigest digest = digests.compute(digestId, (k, existing) -> {
            if (existing == null) {
                SlowSqlDigest created = new SlowSqlDigest(k, normalized, mapperId, costMs, now, hitNanos);
                created.slowCount.increment();
                return created;
            }
            existing.slowCount.increment();
            if (costMs > existing.maxMs) {
                existing.maxMs = costMs;
            }
            existing.sumMs += costMs;
            existing.lastMs = costMs;
            existing.lastTime = now;
            existing.lastHitNanos = hitNanos;
            return existing;
        });
        evictIfNeeded();
        recentEvents.addLast(new SlowSqlEvent(now, costMs, digestId, normalized, mapperId,
                org.slf4j.MDC.get(com.snail.platform.framework.core.web.TraceFilter.TRACE_ID)));
        while (recentEvents.size() > recentSize) {
            recentEvents.pollFirst();
        }
        slowCountTotal.incrementAndGet();
    }

    /** 最近 60 秒执行速率(次/s,1 位小数) */
    public double execPerSec() {
        long epochSecond = System.currentTimeMillis() / 1000;
        long count = 0;
        for (Bucket bucket : secondBuckets) {
            if (bucket != null && bucket.seq >= epochSecond - 60) {
                count += bucket.count.sum();
            }
        }
        return Math.round(count / 60.0 * 10) / 10.0;
    }

    /** 最近 60 秒平均耗时(ms,1 位小数) */
    public double avgRtMs() {
        long epochSecond = System.currentTimeMillis() / 1000;
        long count = 0;
        long costNanos = 0;
        for (Bucket bucket : secondBuckets) {
            if (bucket != null && bucket.seq >= epochSecond - 60) {
                count += bucket.count.sum();
                costNanos += bucket.costNanos.sum();
            }
        }
        if (count == 0) {
            return 0.0;
        }
        return Math.round(costNanos / 1_000_000.0 / count * 10) / 10.0;
    }

    /** 最近 5 分钟慢 SQL 次数(5 个 1 分钟桶之和) */
    public long slowCount5m() {
        long epochMinute = System.currentTimeMillis() / 1000 / 60;
        long count = 0;
        for (Bucket bucket : minuteBuckets) {
            if (bucket != null && bucket.seq >= epochMinute - 5) {
                count += bucket.count.sum();
            }
        }
        return count;
    }

    /** 自启动累计慢 SQL 次数 */
    public long slowCountTotal() {
        return slowCountTotal.get();
    }

    /** 慢 SQL Top N:按 maxMs 降序 */
    public List<SlowSqlDigest> top(int n) {
        return digests.values().stream()
                .sorted(Comparator.comparingLong(SlowSqlDigest::maxMs).reversed())
                .limit(n)
                .toList();
    }

    /** 最近慢 SQL 事件:时间倒序(新的在前) */
    public List<SlowSqlEvent> recent() {
        List<SlowSqlEvent> list = new ArrayList<>(recentEvents);
        list.sort(Comparator.comparing(SlowSqlEvent::time).reversed());
        return list;
    }

    /** 采集器启动时间 */
    public LocalDateTime since() {
        return since;
    }

    /** 桶取用:序号变化即新桶清零(无后台线程) */
    private Bucket bucket(Bucket[] buckets, long seq, int size) {
        int index = (int) (seq % size);
        Bucket bucket = buckets[index];
        if (bucket == null) {
            synchronized (buckets) {
                if (buckets[index] == null) {
                    buckets[index] = new Bucket();
                }
                bucket = buckets[index];
            }
        }
        if (bucket.seq != seq) {
            synchronized (bucket) {
                if (bucket.seq != seq) {
                    bucket.count.reset();
                    bucket.costNanos.reset();
                    bucket.seq = seq;
                }
            }
        }
        return bucket;
    }

    /** 摘要超限 LRU:扫描取 lastHitNanos 最小者移除(500 条内低频,可接受) */
    private void evictIfNeeded() {
        while (digests.size() > maxDigests) {
            String victim = null;
            long min = Long.MAX_VALUE;
            for (Map.Entry<String, SlowSqlDigest> entry : digests.entrySet()) {
                if (entry.getValue().lastHitNanos < min) {
                    min = entry.getValue().lastHitNanos;
                    victim = entry.getKey();
                }
            }
            if (victim == null || digests.remove(victim) == null) {
                break;
            }
        }
    }
}
