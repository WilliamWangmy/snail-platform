package com.snail.platform.function.monitor.alert.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 职责:告警规则创建契约(主方案 §5.2 规则字段,冻结)。
 * 上游:AlertRuleController;下游:AlertRuleService.create(重名/键表/comparator 白名单在 service 校验)。
 */
@Schema(description = "告警规则创建参数")
public record RuleCreateRequest(

        @Schema(description = "规则名称,全局唯一", requiredMode = Schema.RequiredMode.REQUIRED)
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

        @Schema(description = "阈值(有限数)", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请填写阈值")
        Double threshold,

        @Schema(description = "连续满足的采样点数,1-10,默认 1")
        @Min(value = 1, message = "持续点数 1-10")
        @Max(value = 10, message = "持续点数 1-10")
        Integer durationPoints,

        @Schema(description = "级别:info/warning/critical", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请选择级别")
        @Pattern(regexp = "info|warning|critical", message = "级别只支持 info/warning/critical")
        String level,

        @Schema(description = "开关:0 启用 1 停用,默认 0", defaultValue = "0")
        @Min(value = 0, message = "开关取值 0/1")
        @Max(value = 1, message = "开关取值 0/1")
        Integer enabled,

        @Schema(description = "备注")
        @Size(max = 500, message = "备注最长 500 字")
        String remark) {

    public RuleCreateRequest {
        durationPoints = durationPoints == null ? 1 : durationPoints;
        enabled = enabled == null ? 0 : enabled;
    }
}
