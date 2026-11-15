package com.snail.platform.function.monitor.metric.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 职责:history 端点入参契约——GET 对象绑定(四个字段收敛为 DTO,避免散参被 ArchUnit 拦,§4.5)。
 * 上游:MetricController;下游:MetricService.history(跨度/step 白名单与 2000 点保护校验在 service 首行)。
 */
public record MetricHistoryQuery(

        @Schema(description = "指标键(MetricKey 枚举内)", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请选择指标")
        String metric,

        @Schema(description = "开始时间(ISO,含)", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请选择开始时间")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        LocalDateTime beginTime,

        @Schema(description = "结束时间(ISO,含,跨度≤7 天)", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请选择结束时间")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        LocalDateTime endTime,

        @Schema(description = "聚合步长(秒):60/300/3600,默认 300")
        Long step) {
}
