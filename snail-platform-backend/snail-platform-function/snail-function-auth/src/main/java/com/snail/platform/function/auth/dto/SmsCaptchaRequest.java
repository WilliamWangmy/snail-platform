package com.snail.platform.function.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 职责:发送短信验证码请求契约——手机号 + 图形验证码(发送前的人机闸,复用取码接口)。
 * 上游:登录页手机号 tab;下游:AuthService 发送链。
 */
@Schema(description = "发送短信验证码请求")
public record SmsCaptchaRequest(

        @Schema(description = "手机号", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入手机号")
        @Pattern(regexp = "1\\d{10}", message = "手机号格式不正确")
        String phone,

        @Schema(description = "图形验证码键,来自验证码接口", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "缺少验证码键")
        String captchaKey,

        @Schema(description = "图形验证码字符", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入验证码")
        @Size(max = 8, message = "验证码过长")
        String captchaCode) {
}
