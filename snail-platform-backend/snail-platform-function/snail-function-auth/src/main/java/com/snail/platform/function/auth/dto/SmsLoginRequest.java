package com.snail.platform.function.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 职责:短信验证码登录请求契约——手机号 + 短信码,不含租户(按手机号跨租户定位)。
 * 与 LoginRequest 字段交集为零,不复用:复用只会得到一堆条件必填。
 * 上游:登录页手机号 tab;下游:AuthService.loginBySms。
 */
@Schema(description = "短信验证码登录请求")
public record SmsLoginRequest(

        @Schema(description = "手机号", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入手机号")
        @Pattern(regexp = "1\\d{10}", message = "手机号格式不正确")
        String phone,

        @Schema(description = "短信验证码", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入短信验证码")
        @Size(max = 8, message = "验证码过长")
        String smsCode) {
}
