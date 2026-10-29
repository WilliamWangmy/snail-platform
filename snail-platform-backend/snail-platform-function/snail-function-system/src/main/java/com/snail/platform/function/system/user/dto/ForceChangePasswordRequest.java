package com.snail.platform.function.system.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 职责:首次登录强制改密契约——只录新密码,不录原密码(登录态已证身份,原密码校验换成"新密码不得与当前密码相同");
 * 非标记用户调用在服务层被 403 拒,防止借本端点绕过原密码校验改密。
 * 上游:强制改密页面;下游:ProfileService.forceChangePassword。
 */
@Schema(description = "强制修改口令请求")
public record ForceChangePasswordRequest(

        @Schema(description = "新口令明文,长度 8-20,强度口径见 PasswordPolicy", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入新密码")
        @Size(min = 8, max = 20, message = "密码长度需 8-20 位")
        String newPassword) {
}
