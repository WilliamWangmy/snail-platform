package com.snail.platform.function.monitor.metric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.snail.platform.function.monitor.config.MonitorProperties;
import com.snail.platform.function.monitor.datasource.MetricSource;
import com.snail.platform.function.monitor.db.DbServerProbe;
import com.snail.platform.function.monitor.db.slow.SlowSqlCollector;
import com.snail.platform.function.monitor.metric.entity.SysMonitorMetric;
import com.snail.platform.function.monitor.metric.mapper.SysMonitorMetricMapper;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

/**
 * MetricSampler 单测——重点覆盖「按 name() 取源」的装配口径:Spring 注入
 * Map<String, MetricSource> 的 key 是 Bean 名(builtinMetricSource/prometheusMetricSource),
 * 与 name()(builtin/prometheus)是两回事;曾经按 Bean 名 get 永远 miss、被 if(null)
 * 静默跳过,采样空转无日志(总览无数据事故 2026-11-16)。测试 map 一律用 Bean 名做 key,
 * name() 单独 stub——防止回退成 sources.get(key) 的写法。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MetricSamplerTest {

    @Mock
    private MetricRingBuffer ringBuffer;
    @Mock
    private SysMonitorMetricMapper mapper;
    @Mock
    private SlowSqlCollector slowSqlCollector;
    @Mock
    private DbServerProbe dbServerProbe;
    @Mock
    private ApplicationEventPublisher publisher;
    @Mock
    private MetricSource builtinSource;
    @Mock
    private MetricSource prometheusSource;

    private MetricSampler newSampler(MetricSource... sourcesArray) {
        java.util.LinkedHashMap<String, MetricSource> sources = new java.util.LinkedHashMap<>();
        for (MetricSource s : sourcesArray) {
            // key 用真实 Bean 名风格,与 name() 无关——按 Bean 名 get 的回归会立刻失败
            sources.put(s.name() + "MetricSource", s);
        }
        return new MetricSampler(new MonitorProperties(), sources, ringBuffer, mapper,
                slowSqlCollector, dbServerProbe, publisher);
    }

    private void stubBuiltin(Map<String, Double> values) {
        doReturn("builtin").when(builtinSource).name();
        doReturn(values).when(builtinSource).fetch(anySet());
    }

    @Test
    @DisplayName("tick 按 name() 找到 builtin 源:采集、落库、发事件(Bean 名 key 防回归)")
    void tickCollectsBuiltinAndPersists() {
        stubBuiltin(Map.of(MetricKey.JVM_THREADS_LIVE.key(), 42.0,
                MetricKey.DISK_USAGE_PERCENT.key(), 31.5));
        MetricSampler sampler = newSampler(builtinSource, prometheusSource);

        sampler.tick();

        ArgumentCaptor<Map<String, Double>> valuesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(ringBuffer).append(valuesCaptor.capture(), any(long.class));
        assertThat(valuesCaptor.getValue())
                .containsEntry(MetricKey.JVM_THREADS_LIVE.key(), 42.0)
                .containsEntry(MetricKey.DISK_USAGE_PERCENT.key(), 31.5);
        verify(mapper, times(2)).insert(any(SysMonitorMetric.class));
        ArgumentCaptor<MetricTickEvent> eventCaptor = ArgumentCaptor.forClass(MetricTickEvent.class);
        verify(publisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().values()).isEqualTo(valuesCaptor.getValue());
        assertThat(eventCaptor.getValue().degraded()).isFalse();
        assertThat(sampler.lastTick().metricsSource()).isEqualTo("builtin");
    }

    @Test
    @DisplayName("source 配 prometheus 但映射为空:全部键回退 builtin,来源标记 builtin")
    void prometheusWithoutQueriesFallsBackToBuiltin() {
        stubBuiltin(Map.of(MetricKey.SYSTEM_CPU_USAGE.key(), 12.3));
        MonitorProperties properties = new MonitorProperties();
        properties.getMetrics().setSource("prometheus");
        // prometheus 无 queries 映射:usePrometheus=false,应回退 builtin 采集
        doReturn("prometheus").when(prometheusSource).name();
        java.util.LinkedHashMap<String, MetricSource> sources = new java.util.LinkedHashMap<>();
        sources.put("builtinMetricSource", builtinSource);
        sources.put("prometheusMetricSource", prometheusSource);
        MetricSampler sampler = new MetricSampler(properties, sources, ringBuffer, mapper,
                slowSqlCollector, dbServerProbe, publisher);

        sampler.tick();

        verify(builtinSource).fetch(anySet());
        verify(mapper).insert(any(SysMonitorMetric.class));
        assertThat(sampler.lastTick().metricsSource()).isEqualTo("builtin");
    }

    @Test
    @DisplayName("总闸关闭:tick 直接短路,不采集不落库不发事件")
    void disabledShortCircuits() {
        stubBuiltin(Map.of(MetricKey.JVM_THREADS_LIVE.key(), 42.0));
        MonitorProperties properties = new MonitorProperties();
        properties.setEnabled(false);
        java.util.LinkedHashMap<String, MetricSource> sources = new java.util.LinkedHashMap<>();
        sources.put("builtinMetricSource", builtinSource);
        MetricSampler sampler = new MetricSampler(properties, sources, ringBuffer, mapper,
                slowSqlCollector, dbServerProbe, publisher);

        sampler.tick();

        verify(builtinSource, times(0)).fetch(anySet());
        verify(mapper, times(0)).insert(any(SysMonitorMetric.class));
        verify(publisher, times(0)).publishEvent(any(MetricTickEvent.class));
        assertThat(sampler.lastTick().tickEpochMs()).isZero();
    }

    @Test
    @DisplayName("无任何源装配:tick 不抛出,推进空相位(append 空表+发空事件),不落库")
    void noSourcesKeepsSchedulerAlive() {
        MetricSampler sampler = newSampler();

        sampler.tick();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Double>> captor = ArgumentCaptor.forClass(Map.class);
        verify(ringBuffer).append(captor.capture(), any(long.class));
        assertThat(captor.getValue()).isEmpty();
        verify(mapper, times(0)).insert(any(SysMonitorMetric.class));
        ArgumentCaptor<MetricTickEvent> eventCaptor = ArgumentCaptor.forClass(MetricTickEvent.class);
        verify(publisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().values()).isEmpty();
        assertThat(sampler.lastTick().metricsSource()).isEqualTo("builtin");
    }

    @Test
    @DisplayName("外部源部分键失败:degraded=true 仍发布事件(values 取成功子集)")
    void degradedWhenExternalPartial() {
        stubBuiltin(Map.of(MetricKey.SYSTEM_CPU_USAGE.key(), 12.3));
        doReturn("prometheus").when(prometheusSource).name();
        MonitorProperties properties = new MonitorProperties();
        properties.getMetrics().setSource("prometheus");
        properties.getMetrics().getPrometheus().setBaseUrl("http://prom.internal");
        properties.getMetrics().getPrometheus().getQueries()
                .put(MetricKey.SYSTEM_CPU_USAGE.key(), "avg(rate(node_cpu_seconds_total[1m]))*100");
        properties.getMetrics().getPrometheus().getQueries()
                .put(MetricKey.JVM_THREADS_LIVE.key(), "sum(jvm_threads_live)");
        // prometheus 只取回 1/2 个映射键 → degraded
        doReturn(Map.of(MetricKey.SYSTEM_CPU_USAGE.key(), 12.3)).when(prometheusSource)
                .fetch(anySet());
        java.util.LinkedHashMap<String, MetricSource> sources = new java.util.LinkedHashMap<>();
        sources.put("builtinMetricSource", builtinSource);
        sources.put("prometheusMetricSource", prometheusSource);
        MetricSampler sampler = new MetricSampler(properties, sources, ringBuffer, mapper,
                slowSqlCollector, dbServerProbe, publisher);

        sampler.tick();

        ArgumentCaptor<MetricTickEvent> eventCaptor = ArgumentCaptor.forClass(MetricTickEvent.class);
        verify(publisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().degraded()).isTrue();
        // 外部取回的 cpu 键在值里;threads 外部失败后交 builtin 补采,但 builtin 桩不含该键 → 缺席
        assertThat(eventCaptor.getValue().values())
                .containsEntry(MetricKey.SYSTEM_CPU_USAGE.key(), 12.3)
                .doesNotContainKey(MetricKey.JVM_THREADS_LIVE.key());
    }

    @Test
    @DisplayName("差分基线语义由采集器承担:采样器只透传 fetch 结果,不做二次过滤")
    void samplerPassesThroughFetchResult() {
        Map<String, Double> fetched = Map.of(MetricKey.DB_UP.key(), 1.0, MetricKey.VALKEY_UP.key(), 0.0);
        stubBuiltin(fetched);
        MetricSampler sampler = newSampler(builtinSource);

        sampler.tick();

        ArgumentCaptor<Map<String, Double>> captor = ArgumentCaptor.forClass(Map.class);
        verify(ringBuffer).append(captor.capture(), any(long.class));
        assertThat(captor.getValue()).containsAllEntriesOf(fetched);
        assertThat(captor.getValue()).hasSize(2);
    }
}
