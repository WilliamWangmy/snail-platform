package com.snail.platform.function.monitor.metric;

import java.util.Map;

/**
 * 职责:采样 tick 的进程内事件载荷——MetricSampler 每 tick 发布一次,驱动 AlertEvaluator(评估)
 * 与 DataSourceStatusService(状态刷新)按 @Order 顺序消费同一 tick,替代两个独立 @Scheduled 的先后不确定(DD-05)。
 * 上游:MetricSampler.tick() 采样完成后发布;下游:alert/datasource 的 @EventListener。
 * 口径:values 是本 tick 合并后的可用键值(缺失键缺席,消费方自行跳过);degraded=true 表示
 * 外部源部分失败已回退内置。事件是进程内同步调用——监听方必须自吞异常,不得让整 tick 停摆(§2.4)。
 */
public record MetricTickEvent(
        /** 本 tick 的 epoch 毫秒,与样本 sample_time 同源 */
        long tickEpochMs,
        /** 本 tick 可用键值(缺失键缺席) */
        Map<String, Double> values,
        /** 外部源部分失败已回退内置 */
        boolean degraded) {
}
