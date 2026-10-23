package com.snail.platform.function.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 职责:验证码出参。图片为 data-uri 的 base64,前端 img 直接可用。
 * 上游:CaptchaService;下游:登录表单渲染。
 */
@Schema(description = "验证码")
public record CaptchaVO(

        @Schema(description = "验证码键,登录时原样回传")
        String captchaKey,

        @Schema(description = "图片 data-uri(base64 PNG)")
        String image) {
}
