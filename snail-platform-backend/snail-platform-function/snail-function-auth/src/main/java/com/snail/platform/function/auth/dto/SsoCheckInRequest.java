package com.snail.platform.function.auth.dto;

import com.snail.platform.function.auth.service.LoginScene;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 职责:单点跳转签到请求——落地端以 ssotoken 建立会话后,上报本端的 scene 坐标。
 * 上游:AuthController 的 POST /system/auth/sso;下游:AuthService.ssoCheckIn。
 * 口径:scene 白名单与登录链同一份正则(LoginScene.PATTERN);本端点是给
 * workflow/dashboard/portal 这类"共用会话免登跳转"的落地端用的,admin 直登链自己就有日志,不必调。
 */
@Schema(description = "单点跳转签到请求")
public record SsoCheckInRequest(

        @Schema(description = "落地端坐标:workflow/dashboard/portal 等,与登录端白名单同一份口径")
        @NotBlank(message = "登录端不能为空")
        @Pattern(regexp = LoginScene.PATTERN, message = "非法的登录端")
        String scene) {
}
