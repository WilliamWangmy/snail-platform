package com.snail.platform.function.monitor.alert.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 职责:告警规则修改契约——内置规则 name/metric/comparator 与现值不同即 400(service 校验,DD-17 前置口径)。
 */
@Schema(description = "告警规则修改参数")
public record RuleUpdateRequest(

        @Schema(description = "规则名称")
        @NotBlank(message = "请填写规则名称")
        @Size(max = 50, message = "规则名称最长 50 字")
        String name,

        @Schema(description = "指标键(MetricKey 键表内)", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请选择指标")
        String metric,

        @Schema(description = "比较符:gt/gte/lt/lte/eq", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请选择比较符")
        @Pattern(regexp = "gt|gte|lt|lte|eq", message = "比较符只支持 gt/gte/lt/lte/eq")
        String comparator,

        @Schema(description = "阈值", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请填写阈值")
        Double threshold,

        @Schema(description = "连续满足的采样点数,1-10")
        @Min(value = 1, message = "持续点数 1-10")
        @Max(value = 10, message = "持续点数 1-10")
        Integer durationPoints,

        @Schema(description = "级别:info/warning/critical", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请选择级别")
        @Pattern(regexp = "info|warning|critical", message = "级别只支持 info/warning/critical")
        String level,

        @Schema(description = "备注")
        @Size(max = 500, message = "备注最长 500 字")
        String remark) {
}
