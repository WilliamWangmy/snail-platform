package com.snail.platform.function.auth.dto;

import com.snail.platform.function.auth.service.LoginScene;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 职责:多租户账号选择请求契约——pickKey 是"验证码已验证"的一次性凭证,userId 在候选集内选定账号。
 * scene 是登录端坐标(v1.3 补口):pick 是独立二次调用,原 login-sms 的 scene 传不到这里,
 * 必须随选账号请求重传——scene 闸与统计分端在 pick 换令牌链同样生效;可缺省按 admin。
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
        Long userId,

        @Schema(description = "登录端:admin/workflow/app/miniapp/dashboard/portal,缺省 admin;非法值 400「非法的登录端」")
        @Pattern(regexp = LoginScene.PATTERN, message = "非法的登录端")
        String scene) {
}
