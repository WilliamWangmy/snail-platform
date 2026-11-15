package com.snail.platform.function.monitor.diag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 职责:诊断报告出参契约(主方案 §5.4,冻结)——items 为检查项结果列表(落库为 JSON,出参结构化)。
 */
@Schema(description = "诊断报告")
public record ReportVO(

        @Schema(description = "报告 ID")
        Long reportId,

        @Schema(description = "触发人显示名快照")
        String triggerBy,

        @Schema(description = "触发时间")
        LocalDateTime triggerTime,

        @Schema(description = "总耗时(ms)")
        Long costMs,

        @Schema(description = "总体结论:pass/warn/fail")
        String overall,

        @Schema(description = "检查项结果")
        List<Item> items) {

    /**
     * 单项结果:key/name/status/latencyMs/detail/suggestion。
     */
    public record Item(
            /** 契约 key */
            String key,
            /** 中文名 */
            String name,
            /** pass/warn/fail/skip */
            String status,
            /** 耗时(ms) */
            Long latencyMs,
            /** 结论详情 */
            String detail,
            /** 排障建议 */
            String suggestion) {
    }
}
