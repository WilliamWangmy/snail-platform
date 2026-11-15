package com.snail.platform.function.monitor.datasource;

import java.util.Map;
import java.util.Set;

/**
 * 职责:指标源适配接口——采样器按 name() 取当前配置的来源,内置(builtin)与外部(prometheus)
 * 同一签名;Bean 图恒定,不随配置条件化(DD-01/DD-06)。
 * 上游:MetricSampler.tick();下游:BuiltinMetricSource(委托 MetricCollector)、PrometheusMetricSource(Step 7)。
 * 口径:查询失败的键缺席(采样器对缺席键回退内置/不产样本);fetch 永不抛出。
 */
public interface MetricSource {

    /**
     * 按请求键组取值;查询失败的键缺席。
     *
     * @param keys 请求的指标键集合(MetricKey.key)
     * @return key → 值
     */
    Map<String, Double> fetch(Set<String> keys);

    /** 来源名:builtin / prometheus */
    String name();
}
