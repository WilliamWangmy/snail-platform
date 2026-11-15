package com.snail.platform.function.monitor.metric.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 职责:realtime/history 端点出参契约(主方案 §5.1)——series 每项带单位与点列,点 t=epochMs。
 */
public record MetricSeriesVO(

        @Schema(description = "指标键")
        String metric,

        @Schema(description = "展示单位")
        String unit,

        @Schema(description = "点列(t=epoch 毫秒)")
        List<Point> points) {

    /**
     * 单个曲线点:t=epoch 毫秒,v=值。
     */
    public record Point(
            /** epoch 毫秒 */
            long t,
            /** 值 */
            double v) {
    }
}
