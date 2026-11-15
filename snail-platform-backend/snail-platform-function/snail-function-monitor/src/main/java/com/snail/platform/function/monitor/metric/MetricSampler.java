package com.snail.platform.function.monitor.metric;

import com.snail.platform.function.monitor.config.MonitorProperties;
import com.snail.platform.function.monitor.datasource.MetricSource;
import com.snail.platform.function.monitor.db.DbServerProbe;
import com.snail.platform.function.monitor.db.slow.SlowSqlCollector;
import com.snail.platform.function.monitor.metric.entity.SysMonitorMetric;
import com.snail.platform.function.monitor.metric.mapper.SysMonitorMetricMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 职责:采样调度器——每 tick 按"外部映射键 + 内置兜底"合并取值(MDD-06),写环形缓冲、逐条落库,
 * 发布 MetricTickEvent 驱动评估与状态刷新(DD-05)。
 * 上游:@Scheduled 调度;下游:MetricRingBuffer、SysMonitorMetricMapper、事件监听方(AlertEvaluator、
 * DataSourceStatusService)。
 * 口径:首行总闸短路(DD-01);落库失败 log.error 后继续不重试(DD-09);整体 try-catch 防 tick 停摆(§2.4);
 * 外部源部分失败置 degraded=true。
 */
@Component
public class MetricSampler {

    private static final Logger log = LoggerFactory.getLogger(MetricSampler.class);

    private final MonitorProperties properties;
    private final Map<String, MetricSource> sources;
    private final MetricRingBuffer ringBuffer;
    private final SysMonitorMetricMapper mapper;
    private final SlowSqlCollector slowSqlCollector;
    private final DbServerProbe dbServerProbe;
    private final ApplicationEventPublisher publisher;

    /** 最近一次 tick 快照(实时端点 metricsSource/degraded 出参) */
    private volatile TickSnapshot lastTick = new TickSnapshot(0, "builtin", false);

    /**
     * 最近 tick 快照:epochMs/metricsSource(builtin|prometheus)/degraded。
     */
    public record TickSnapshot(
            /** tick epoch 毫秒 */
            long tickEpochMs,
            /** 本响应主要来源 */
            String metricsSource,
            /** 外部源部分失败已回退内置 */
            boolean degraded) {
    }

    public MetricSampler(MonitorProperties properties, Map<String, MetricSource> sources,
                         MetricRingBuffer ringBuffer, SysMonitorMetricMapper mapper,
                         SlowSqlCollector slowSqlCollector, DbServerProbe dbServerProbe,
                         ApplicationEventPublisher publisher) {
        this.properties = properties;
        this.sources = sources;
        this.ringBuffer = ringBuffer;
        this.mapper = mapper;
        this.slowSqlCollector = slowSqlCollector;
        this.dbServerProbe = dbServerProbe;
        this.publisher = publisher;
    }

    /** 最近 tick 快照 */
    public TickSnapshot lastTick() {
        return lastTick;
    }

    /**
     * 采样 tick:initialDelay 10s,间隔由 snail.monitor.sample-interval-seconds 驱动(评估/状态刷新同 tick 消费)。
     */
    @Scheduled(initialDelayString = "10000",
            fixedDelayString = "#{${snail.monitor.sample-interval-seconds:60} * 1000}")
    public void tick() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            Set<String> allKeys = MetricKey.all();
            MetricSource external = sources.get("prometheus");
            Map<String, String> promQueries = properties.getMetrics().getPrometheus().getQueries();
            boolean usePrometheus = "prometheus".equals(properties.getMetrics().getSource())
                    && external != null && !promQueries.isEmpty();
            Map<String, Double> values = new LinkedHashMap<>();
            boolean degraded = false;
            if (usePrometheus) {
                // 映射键 ∩ 合法键:未登记的 PromQL 配置不参与采样
                Set<String> promKeys = new java.util.LinkedHashSet<>();
                promQueries.keySet().stream().filter(MetricKey::isValidKey).forEach(promKeys::add);
                promKeys.retainAll(allKeys);
                if (!promKeys.isEmpty()) {
                    Map<String, Double> externalValues = external.fetch(promKeys);
                    values.putAll(externalValues);
                    degraded = externalValues.size() < promKeys.size();
                }
            }
            Set<String> builtinKeys = new java.util.LinkedHashSet<>(allKeys);
            builtinKeys.removeAll(values.keySet());
            MetricSource builtin = sources.get("builtin");
            if (builtin != null) {
                values.putAll(builtin.fetch(builtinKeys));
            }
            ringBuffer.append(values, now);
            persist(values);
            String sourceName = usePrometheus && !properties.getMetrics().getPrometheus().getQueries().isEmpty()
                    ? "prometheus" : "builtin";
            lastTick = new TickSnapshot(now, sourceName, degraded);
            publisher.publishEvent(new MetricTickEvent(now, values, degraded));
        } catch (Exception e) {
            // 整体兜底:不让 tick 抛出停摆调度器;常规告警由键级 warn 承担
            log.error("monitor tick 失败: {}", e.getMessage());
        }
    }

    /** 逐条落库(最多 25 行/分钟,DD-09);失败 log.error 后继续,不影响缓冲与端点 */
    private void persist(Map<String, Double> values) {
        LocalDateTime sampleTime = LocalDateTime.now().withNano(0);
        values.forEach((metric, value) -> {
            try {
                SysMonitorMetric row = new SysMonitorMetric();
                row.setInstance("default");
                row.setMetric(metric);
                row.setValue(value);
                row.setSampleTime(sampleTime);
                mapper.insert(row);
            } catch (Exception e) {
                log.error("monitor 指标落库失败(metric={}): {}", metric, e.getMessage());
            }
        });
    }
}
