package com.snail.platform.function.auth.dto;

import com.snail.platform.function.auth.service.LoginScene;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 职责:短信验证码登录请求契约——手机号 + 短信码 + 登录端,不含租户(按手机号跨租户定位)。
 * 与 LoginRequest 字段交集为零,不复用:复用只会得到一堆条件必填。
 * scene 是登录端坐标,可缺省按 admin;多账号命中时它会随 pickKey 候选流程由 SmsPickRequest 重传(v1.3 补口)。
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
        String smsCode,

        @Schema(description = "登录端:admin/workflow/app/miniapp/dashboard/portal,缺省 admin;非法值 400「非法的登录端」")
        @Pattern(regexp = LoginScene.PATTERN, message = "非法的登录端")
        String scene) {
}
