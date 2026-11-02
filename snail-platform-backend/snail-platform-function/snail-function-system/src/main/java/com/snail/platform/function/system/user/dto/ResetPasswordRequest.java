package com.snail.platform.function.system.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 职责:管理员重置口令契约——新口令由管理员自输(P2-3),服务端按 PasswordPolicy 校验;
 * 不再有"服务端生成口令回传"的密文响应。
 * 上游:用户管理页面;下游:UserService.resetPassword。
 */
@Schema(description = "重置口令请求")
public record ResetPasswordRequest(

        @Schema(description = "新口令,长度 8-20,强度口径见 PasswordPolicy", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入新密码")
        @Size(min = 8, max = 20, message = "密码长度需 8-20 位")
        String password) {
}
