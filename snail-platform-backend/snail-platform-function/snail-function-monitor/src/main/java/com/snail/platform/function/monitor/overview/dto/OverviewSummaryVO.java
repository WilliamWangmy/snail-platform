package com.snail.platform.function.monitor.overview.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/**
 * 职责:总览 summary 出参契约(主方案 §5.1)——实例信息、健康芯片、8 个指标当前值、
 * 开放告警计数与来源标识。
 */
public record OverviewSummaryVO(

        @Schema(description = "实例信息")
        Instance instance,

        @Schema(description = "健康芯片:db/valkey/disk/agent")
        Map<String, HealthItem> health,

        @Schema(description = "8 个指标当前值(缺失为 null,前端显示 --)")
        Map<String, Double> metrics,

        @Schema(description = "开放告警计数(按级别)")
        Map<String, Long> activeAlertCount,

        @Schema(description = "指标来源:builtin / prometheus")
        String metricsSource,

        @Schema(description = "外部指标源降级标识")
        boolean degraded) {

    /**
     * 实例信息:名称/版本/profile/启动时刻/运行秒数。
     */
    public record Instance(
            /** 实例名(spring.application.name) */
            String name,
            /** 构建版本(不可用为 unknown) */
            String version,
            /** 激活 profile */
            List<String> profile,
            /** 启动时刻(ISO) */
            String startTime,
            /** 运行秒数 */
            long uptimeSeconds) {
    }

    /**
     * 健康芯片项:status(up/down/skip) + latencyMs(skip 为 null)。
     */
    public record HealthItem(
            /** up/down/skip */
            String status,
            /** 探测耗时(ms);skip 为 null */
            Long latencyMs) {
    }
}
