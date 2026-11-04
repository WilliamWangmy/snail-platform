package com.snail.platform.function.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 职责:多租户账号选择请求契约——pickKey 是"验证码已验证"的一次性凭证,userId 在候选集内选定账号。
 * 上游:登录页租户选择对话框;下游:AuthService.pickSmsAccount。
 */
@Schema(description = "多租户账号选择请求")
public record SmsPickRequest(

        @Schema(description = "一次性选择凭证,来自登录接口的候选列表", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "缺少选择凭证")
        @Size(max = 64, message = "选择凭证过长")
        String pickKey,

        @Schema(description = "选定的账号用户 ID", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请选择要登录的账号")
        Long userId) {
}
