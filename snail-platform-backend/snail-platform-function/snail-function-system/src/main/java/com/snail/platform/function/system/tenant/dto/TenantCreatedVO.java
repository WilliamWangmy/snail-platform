package com.snail.platform.function.system.tenant.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 职责:开通租户的出参——新租户ID + 首任管理员口令的密文。
 * password 是 PasswordTransferCipher 加密后的 Base64(AES-256-GCM),由管理端解密后一次性展示;响应体不出现明文。
 * 键名必须保持 password:OperLog 落库走 SensitiveDataMasker 键级脱敏,换名或裸挂 data 都会绕过名单。
 * 上游:TenantController;下游:无。
 */
@Schema(description = "开通租户结果")
public record TenantCreatedVO(

        @Schema(description = "新租户ID")
        Long tenantId,

        @Schema(description = "首任管理员口令密文(AES-256-GCM),仅本次响应可见,前端解密后展示")
        String password) {
}
