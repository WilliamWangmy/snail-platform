package com.snail.platform.function.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 职责:登录请求契约。账号 + 密码 + 验证码,不含租户(用户名全局唯一,自动定位)。
 * 上游:前端登录表单;下游:AuthService 校验链。
 */
@Schema(description = "登录请求")
public record LoginRequest(

        @Schema(description = "登录名", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入登录名")
        @Size(max = 30, message = "登录名过长")
        String username,

        @Schema(description = "口令明文,服务端只做比对不落日志", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入密码")
        @Size(max = 64, message = "密码过长")
        String password,

        @Schema(description = "验证码键,来自验证码接口", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "缺少验证码键")
        String captchaKey,

        @Schema(description = "验证码字符", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入验证码")
        @Size(max = 8, message = "验证码过长")
        String captchaCode) {
}
