package com.snail.platform.function.monitor.alert.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 职责:静默入参契约——分钟数 1-1440,覆盖式写规则 silence_until(主方案 §5.2)。
 */
@Schema(description = "告警静默参数")
public record SilenceRequest(

        @Schema(description = "静默分钟数,1-1440", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请填写静默分钟数")
        @Min(value = 1, message = "静默时长 1-1440 分钟")
        @Max(value = 1440, message = "静默时长 1-1440 分钟")
        Integer minutes) {
}
