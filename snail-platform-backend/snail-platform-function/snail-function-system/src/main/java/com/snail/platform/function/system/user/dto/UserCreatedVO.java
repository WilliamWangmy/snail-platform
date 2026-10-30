package com.snail.platform.function.system.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 职责:建号与重置口令的出参——新用户ID + 服务端生成口令的密文。
 * password 是 PasswordTransferCipher 加密后的 Base64(AES-256-GCM),由管理端解密后一次性展示;响应体不出现明文。
 * 键名必须保持 password:OperLog 落库走 SensitiveDataMasker 键级脱敏,换名或裸挂 data 都会绕过名单。
 * 上游:UserController;下游:无。
 */
@Schema(description = "建号/重置口令结果")
public record UserCreatedVO(

        @Schema(description = "新用户ID")
        Long userId,

        @Schema(description = "口令密文(AES-256-GCM),仅本次响应可见,前端解密后展示")
        String password) {
}
