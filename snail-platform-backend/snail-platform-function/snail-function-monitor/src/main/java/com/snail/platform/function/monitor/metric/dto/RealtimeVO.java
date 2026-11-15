package com.snail.platform.function.monitor.metric.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 职责:realtime 端点出参契约——多键曲线 + 来源标识(主方案 §5.1)。
 */
public record RealtimeVO(

        @Schema(description = "曲线列表(只含有数据的键)")
        List<MetricSeriesVO> series,

        @Schema(description = "指标来源:builtin / prometheus")
        String metricsSource,

        @Schema(description = "外部源部分失败已回退内置")
        boolean degraded) {
}
