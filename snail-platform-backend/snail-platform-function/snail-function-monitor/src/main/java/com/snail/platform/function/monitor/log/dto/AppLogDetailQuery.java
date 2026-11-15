package com.snail.platform.function.monitor.log.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 职责:日志行上下文入参契约(主方案 §5.3,冻结)——fileId 白名单 + 行号定位;loki 模式 400。
 */
@Schema(description = "日志行上下文参数")
public record AppLogDetailQuery(

        @Schema(description = "文件 ID(=文件名,白名单)", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "缺少 fileId")
        String fileId,

        @Schema(description = "目标行号(1 起)", requiredMode = Schema.RequiredMode.REQUIRED)
        @Min(value = 1, message = "行号从 1 起")
        Long lineNo,

        @Schema(description = "上下文行数(前后各 N),默认 50,≤200")
        @Min(value = 1, message = "context 1-200")
        @Max(value = 200, message = "context 1-200")
        Integer context) {

    public AppLogDetailQuery {
        context = context == null ? 50 : context;
    }
}
