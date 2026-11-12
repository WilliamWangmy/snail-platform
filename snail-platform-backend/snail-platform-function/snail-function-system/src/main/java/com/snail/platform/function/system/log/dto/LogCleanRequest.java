package com.snail.platform.function.system.log.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 职责:日志清除请求契约——before 三档保留期白名单(1m/6m/1y),tenantId 仅平台侧生效。
 * 上游:OperLogController/LoginLogController 的 POST 清除端点;下游:OperLogService/LoginLogService.clean。
 * 口径:保留期只开三档,正则锁死不开放任意时间——清除是不可逆动作,面越窄越不容易误伤;
 * 不传 tenantId 且持平台跨租户权限码时按跨租户全量清除,与查询口径一致。
 */
@Schema(description = "日志清除请求")
public record LogCleanRequest(

        @Schema(description = "保留期:1m 一个月前 / 6m 半年前 / 1y 一年前,删除该时点之前的全部日志")
        @NotBlank(message = "保留期不能为空")
        @Pattern(regexp = "1m|6m|1y", message = "保留期仅支持 1m/6m/1y")
        String before,

        @Schema(description = "租户 ID,仅持平台跨租户权限码者生效,不传即跨租户全量")
        Long tenantId) {
}
