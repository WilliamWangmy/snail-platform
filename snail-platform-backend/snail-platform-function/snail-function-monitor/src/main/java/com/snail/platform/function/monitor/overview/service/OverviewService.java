package com.snail.platform.function.monitor.overview.service;

import com.snail.platform.function.monitor.alert.mapper.SysMonitorAlertMapper;
import com.snail.platform.function.monitor.config.MonitorProperties;
import com.snail.platform.function.monitor.metric.HealthProbe;
import com.snail.platform.function.monitor.metric.MetricKey;
import com.snail.platform.function.monitor.metric.MetricRingBuffer;
import com.snail.platform.function.monitor.metric.MetricSampler;
import com.snail.platform.function.monitor.metric.MetricSampler.TickSnapshot;
import com.snail.platform.function.monitor.overview.dto.OverviewSummaryVO;
import com.snail.platform.function.monitor.overview.dto.TrendsVO;
import com.snail.platform.function.system.api.LoginStatPort;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 职责:总览聚合服务——summary(实例/健康/当前值/告警计数)与 trends(登录趋势补 0/在线数)。
 * 上游:OverviewController;下游:HealthProbe(健康)、MetricRingBuffer(当前值)、SysMonitorAlertMapper
 * (开放告警计数)、LoginStatPort(趋势)、StringRedisTemplate(在线数)。
 * 口径:健康四项并发预算 500ms 由 HealthProbe 自带;当前值取环形缓冲最近一点,缺失写 null;
 * trends 缺失日补 0,窗口 [today-days+1, today+1)。
 */
@Service
public class OverviewService {

    /** 总览展示的 8 个键(契约顺序) */
    private static final List<String> SUMMARY_KEYS = List.of(
            MetricKey.SYSTEM_CPU_USAGE.key(),
            MetricKey.JVM_HEAP_USED_PERCENT.key(),
            MetricKey.JVM_THREADS_LIVE.key(),
            MetricKey.HTTP_SERVER_QPS.key(),
            MetricKey.HTTP_SERVER_ERROR_RATE.key(),
            MetricKey.HTTP_SERVER_RT_AVG_MS.key(),
            MetricKey.ONLINE_USERS.key(),
            MetricKey.DISK_USAGE_PERCENT.key());

    private final MonitorProperties properties;
    private final Environment environment;
    private final HealthProbe healthProbe;
    private final MetricRingBuffer ringBuffer;
    private final MetricSampler sampler;
    private final SysMonitorAlertMapper alertMapper;
    private final LoginStatPort loginStatPort;
    private final StringRedisTemplate redisTemplate;

    public OverviewService(MonitorProperties properties, Environment environment, HealthProbe healthProbe,
                           MetricRingBuffer ringBuffer, MetricSampler sampler, SysMonitorAlertMapper alertMapper,
                           LoginStatPort loginStatPort, StringRedisTemplate redisTemplate) {
        this.properties = properties;
        this.environment = environment;
        this.healthProbe = healthProbe;
        this.ringBuffer = ringBuffer;
        this.sampler = sampler;
        this.alertMapper = alertMapper;
        this.loginStatPort = loginStatPort;
        this.redisTemplate = redisTemplate;
    }

    /** 总览 summary 组装 */
    public OverviewSummaryVO summary() {
        TickSnapshot tick = sampler.lastTick();
        Map<String, HealthProbe.ProbeResult> probes = healthProbe.probeAll(
                properties.getLogs().getFile().getDir(), properties.getDiag().getAgentHealthUrl(),
                () -> agentReachable());
        Map<String, OverviewSummaryVO.HealthItem> health = new LinkedHashMap<>();
        probes.forEach((key, result) -> health.put(key,
                new OverviewSummaryVO.HealthItem(result.status(), result.latencyMs())));
        Map<String, Double> metrics = new LinkedHashMap<>();
        SUMMARY_KEYS.forEach(key -> {
            MetricRingBuffer.MetricPoint point = ringBuffer.latest(key);
            metrics.put(key, point == null ? null : point.v());
        });
        Map<String, Long> alertCount = new LinkedHashMap<>();
        alertCount.put("warning", 0L);
        alertCount.put("critical", 0L);
        alertMapper.countActiveByLevel().forEach(row -> {
            Object level = row.get("level");
            Object cnt = row.get("cnt");
            if (level != null && cnt != null && alertCount.containsKey(level.toString())) {
                alertCount.put(level.toString(), ((Number) cnt).longValue());
            }
        });
        return new OverviewSummaryVO(buildInstance(), health, metrics, alertCount,
                tick.metricsSource(), tick.degraded());
    }

    /** 登录趋势与在线数;days 1-30 由 controller 校验 */
    public TrendsVO trends(int days) {
        LocalDate begin = LocalDate.now().minusDays(days - 1L);
        LocalDate end = LocalDate.now().plusDays(1);
        Map<LocalDate, LoginStatPort.DayCount> byDay = new LinkedHashMap<>();
        loginStatPort.dailyCounts(begin, end).forEach(day -> byDay.put(day.date(), day));
        List<TrendsVO.DayTrend> trend = new ArrayList<>();
        for (LocalDate date = begin; date.isBefore(end); date = date.plusDays(1)) {
            LoginStatPort.DayCount day = byDay.get(date);
            trend.add(day == null ? new TrendsVO.DayTrend(date.toString(), 0L, 0L)
                    : new TrendsVO.DayTrend(day.date().toString(), day.total(), day.fail()));
        }
        return new TrendsVO(trend, onlineNow());
    }

    /** 当前在线数:SCAN online:idx:* 逐键 SCARD 求和(与 collectOnline 同源) */
    private long onlineNow() {
        try {
            Long total = redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Long>) connection -> {
                long sum = 0;
                try (Cursor<byte[]> cursor = connection.scan(ScanOptions.scanOptions()
                        .match(com.snail.platform.framework.redis.SnailRedisKeys.ONLINE_INDEX + "*")
                        .count(100).build())) {
                    while (cursor.hasNext()) {
                        Long size = connection.sCard(cursor.next());
                        if (size != null) {
                            sum += size;
                        }
                    }
                }
                return sum;
            });
            return total == null ? 0 : total;
        } catch (Exception e) {
            return 0;
        }
    }

    private OverviewSummaryVO.Instance buildInstance() {
        String version = environment.getProperty("build.version");
        long startTime = ManagementFactory.getRuntimeMXBean().getStartTime();
        LocalDateTime start = Instant.ofEpochMilli(startTime).atZone(ZoneId.systemDefault()).toLocalDateTime();
        return new OverviewSummaryVO.Instance(
                environment.getProperty("spring.application.name", "snail-platform"),
                version == null ? "unknown" : version,
                List.of(environment.getActiveProfiles()),
                start.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                (System.currentTimeMillis() - startTime) / 1000);
    }

    /** agent 探活动作(诊断子域接入前占位:URL 空 skip;非空时 HTTP 探测由 Step 6 的探针复用) */
    private boolean agentReachable() {
        // 一期 agent 探针由 snail-platform-agent /health 提供;此处用 HTTP GET + 200 判定
        try {
            var client = java.net.http.HttpClient.newHttpClient();
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(properties.getDiag().getAgentHealthUrl()))
                    .timeout(java.time.Duration.ofMillis(500))
                    .GET().build();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
