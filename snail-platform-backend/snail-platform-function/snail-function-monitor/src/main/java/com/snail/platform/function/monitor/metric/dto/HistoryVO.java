package com.snail.platform.function.monitor.metric.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 职责:history 端点出参契约——单键聚合曲线(主方案 §5.1)。
 */
public record HistoryVO(

        @Schema(description = "指标键")
        String metric,

        @Schema(description = "展示单位")
        String unit,

        @Schema(description = "聚合步长(秒)")
        long step,

        @Schema(description = "聚合点列(t=epoch 毫秒)")
        List<MetricSeriesVO.Point> points) {
}
