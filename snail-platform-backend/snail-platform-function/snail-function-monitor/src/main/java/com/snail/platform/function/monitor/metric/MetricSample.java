package com.snail.platform.function.monitor.metric;

import java.time.LocalDateTime;

/**
 * 职责:单键采样点值对象——采样器合并后的一个键值 + 采样时刻。
 * 上游:MetricSampler.tick();下游:落库实体与环形缓冲的中间形态。
 */
public record MetricSample(
        /** 指标键 */
        String metric,
        /** 采样值 */
        double value,
        /** 采样时间(秒精度) */
        LocalDateTime sampleTime) {
}
