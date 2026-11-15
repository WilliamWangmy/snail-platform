package com.snail.platform.function.monitor.datasource;

import com.snail.platform.function.monitor.metric.MetricCollector;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 职责:内置指标源——委托 MetricCollector,供采样器统一两种来源的装配口径(DD-01)。
 * 上游:MetricSampler;下游:MetricCollector(按键组取值)。
 */
@Component
public class BuiltinMetricSource implements MetricSource {

    /** 内置采集器 */
    private final MetricCollector collector;

    public BuiltinMetricSource(MetricCollector collector) {
        this.collector = collector;
    }

    @Override
    public Map<String, Double> fetch(Set<String> keys) {
        return collector.collect(keys);
    }

    @Override
    public String name() {
        return "builtin";
    }
}
