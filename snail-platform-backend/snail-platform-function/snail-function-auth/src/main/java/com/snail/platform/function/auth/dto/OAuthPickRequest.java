package com.snail.platform.function.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 职责:第三方多账号选择入参,与 SmsPickRequest 同形态——pickKey 是"厂商身份已验证"的
 * 唯一证明,userId 必须在候选集内。
 * 上游:前端多账号选择弹窗;下游:AuthService.pickOauthAccount。
 */
@Schema(description = "第三方多账号选择请求")
public record OAuthPickRequest(

        @Schema(description = "一次性选择凭证", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "缺少选择凭证")
        @Size(max = 64, message = "选择凭证过长")
        String pickKey,

        @Schema(description = "选定的用户 ID", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请选择账号")
        Long userId) {
}
