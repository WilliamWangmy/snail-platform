package com.snail.platform.function.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 职责:登录成功出参。令牌 + 身份快照 + 租户信息,前端据此完成一次跳转无需再拉用户详情。
 * 上游:AuthService;下游:前端会话存储。
 */
@Schema(description = "登录结果")
public record LoginSuccessVO(

        @Schema(description = "访问令牌")
        String token,

        @Schema(description = "令牌类型,固定 Bearer")
        String tokenType,

        @Schema(description = "有效期(秒)")
        long expiresIn,

        @Schema(description = "用户 ID")
        Long userId,

        @Schema(description = "登录名")
        String username,

        @Schema(description = "显示名")
        String nickname,

        @Schema(description = "租户 ID")
        Long tenantId,

        @Schema(description = "租户名称")
        String tenantName,

        @Schema(description = "是否待强制改密:true=本次登录后须先完成强制改密")
        boolean passwordChangeRequired) {
}
