package com.snail.platform.function.monitor.alert.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 职责:规则启停入参契约——0 启用/1 停用(平台口径);停用时开放告警置 resolved + 恢复通知一次(DD-17)。
 */
@Schema(description = "规则启停参数")
public record RuleToggleRequest(

        @Schema(description = "开关:0 启用 1 停用", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请填写开关值")
        @Min(value = 0, message = "开关取值 0/1")
        @Max(value = 1, message = "开关取值 0/1")
        Integer enabled) {
}
