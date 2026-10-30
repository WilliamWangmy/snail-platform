package com.snail.platform.function.system.tenant.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 职责:开通租户的出参——新租户ID + 服务端为首任管理员生成的明文口令。
 * 明文必须挂在 password 键下出参:OperLog 落库走 SensitiveDataMasker 键级脱敏,键名在名单内才会打码,
 * 裸字符串直挂 data 会绕过名单,明文就进 sys_oper_log.result 了。
 * 上游:TenantController;下游:无。
 */
@Schema(description = "开通租户结果")
public record TenantCreatedVO(

        @Schema(description = "新租户ID")
        Long tenantId,

        @Schema(description = "首任管理员明文口令,仅本次响应可见")
        String password) {
}
