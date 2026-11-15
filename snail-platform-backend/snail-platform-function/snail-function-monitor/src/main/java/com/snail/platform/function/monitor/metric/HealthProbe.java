package com.snail.platform.function.monitor.metric;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * 职责:实时探活单点——数据库(SELECT 校验)/Valkey(PING)/磁盘(FileStore 可用空间)/agent(HTTP /health)
 * 四项健康探测,总览健康芯片与采集器的 db.up/valkey.up 键共用。
 * 上游:OverviewService(健康芯片)、MetricCollector(up 键);下游:JDBC/Redis 连接。
 * 口径:每项探测自带超时(db 用连接校验超时、valkey/agent 用命令/请求超时),失败返回 down 而不抛——
 * 探活失败是结果不是故障;agent 未配置返回 skip。
 */
@Component
public class HealthProbe {

    /** 数据源(缺容器中无 DataSource 时探活降级) */
    private final ObjectProvider<DataSource> dataSource;

    /** Redis 连接工厂(Valkey PING) */
    private final ObjectProvider<RedisConnectionFactory> redisConnectionFactory;

    public HealthProbe(ObjectProvider<DataSource> dataSource, ObjectProvider<RedisConnectionFactory> redisConnectionFactory) {
        this.dataSource = dataSource;
        this.redisConnectionFactory = redisConnectionFactory;
    }

    /**
     * 探活结果快照:status(up/down/skip) + latencyMs(毫秒,skip 为 null)。
     */
    public record ProbeResult(
            /** up/down/skip */
            String status,
            /** 探测耗时(ms);skip 为 null */
            Long latencyMs) {

        public static ProbeResult up(long latencyMs) {
            return new ProbeResult("up", latencyMs);
        }

        public static ProbeResult down(Long latencyMs) {
            return new ProbeResult("down", latencyMs);
        }

        public static ProbeResult skip() {
            return new ProbeResult("skip", null);
        }
    }

    /** 数据库探活:借连接做 isValid 校验;失败即 down */
    public ProbeResult probeDb() {
        DataSource ds = dataSource.getIfAvailable();
        if (ds == null) {
            return ProbeResult.down(null);
        }
        long start = System.nanoTime();
        try (var conn = ds.getConnection()) {
            boolean valid = conn.isValid(1);
            long latencyMs = elapsedMs(start);
            return valid ? ProbeResult.up(latencyMs) : ProbeResult.down(latencyMs);
        } catch (Exception e) {
            return ProbeResult.down(elapsedMs(start));
        }
    }

    /** Valkey 探活:PING;失败即 down */
    public ProbeResult probeValkey() {
        RedisConnectionFactory factory = redisConnectionFactory.getIfAvailable();
        if (factory == null) {
            return ProbeResult.down(null);
        }
        long start = System.nanoTime();
        try (var conn = factory.getConnection()) {
            conn.ping();
            return ProbeResult.up(elapsedMs(start));
        } catch (Exception e) {
            return ProbeResult.down(elapsedMs(start));
        }
    }

    /** 磁盘健康:logs 目录所在 FileStore 可用空间占比;目录不存在视为 down */
    public ProbeResult probeDisk(String logsDir) {
        long start = System.nanoTime();
        try {
            FileStore store = Files.getFileStore(Path.of(logsDir));
            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();
            long latencyMs = elapsedMs(start);
            if (total <= 0) {
                return ProbeResult.down(latencyMs);
            }
            return usable * 100 / total >= 5 ? ProbeResult.up(latencyMs) : ProbeResult.down(latencyMs);
        } catch (IOException e) {
            return ProbeResult.down(elapsedMs(start));
        }
    }

    /** agent 探活:HTTP GET 探针地址;未配置返回 skip;由调用方提供探测动作(诊断/总览共用) */
    public ProbeResult probeAgent(String agentHealthUrl, Supplier<Boolean> reachable) {
        if (agentHealthUrl == null || agentHealthUrl.isBlank()) {
            return ProbeResult.skip();
        }
        long start = System.nanoTime();
        try {
            boolean ok = reachable.get();
            long latencyMs = elapsedMs(start);
            return ok ? ProbeResult.up(latencyMs) : ProbeResult.down(latencyMs);
        } catch (Exception e) {
            return ProbeResult.down(elapsedMs(start));
        }
    }

    /** 四项健康一次探测,键为 db/valkey/disk/agent;单项异常不影响其余项 */
    public Map<String, ProbeResult> probeAll(String logsDir, String agentHealthUrl, Supplier<Boolean> agentReachable) {
        Map<String, ProbeResult> result = new LinkedHashMap<>();
        result.put("db", safe(() -> probeDb()));
        result.put("valkey", safe(() -> probeValkey()));
        result.put("disk", safe(() -> probeDisk(logsDir)));
        result.put("agent", safe(() -> probeAgent(agentHealthUrl, agentReachable)));
        return result;
    }

    private ProbeResult safe(Supplier<ProbeResult> action) {
        try {
            return action.get();
        } catch (Exception e) {
            return ProbeResult.down(null);
        }
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
