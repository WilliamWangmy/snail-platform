package com.snail.platform.function.external.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 职责:第三方调用流水清除请求契约——before 三档保留期白名单(1m/6m/1y)。
 * 上游:ExternalCallController 的 POST 清除端点;下游:ExternalCallLogService.clean。
 * 口径:流水是平台面账本,清除即整表按期删除,无租户参数——租户面本就不提供入口。
 */
@Schema(description = "第三方调用流水清除请求")
public record ExternalCallLogCleanRequest(

        @Schema(description = "保留期:1m 一个月前 / 6m 半年前 / 1y 一年前,删除该时点之前的全部流水")
        @NotBlank(message = "保留期不能为空")
        @Pattern(regexp = "1m|6m|1y", message = "保留期仅支持 1m/6m/1y")
        String before) {
}
