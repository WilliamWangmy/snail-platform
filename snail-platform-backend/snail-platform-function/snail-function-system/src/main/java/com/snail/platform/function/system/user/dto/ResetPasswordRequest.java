package com.snail.platform.function.system.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 职责:管理员重置口令契约。重置后由前端引导用户自行改密(本期无强制改密字段,口径见设计文档 §3)。
 * 上游:用户列表"重置密码";下游:UserService.resetPassword。
 */
@Schema(description = "重置密码请求")
public record ResetPasswordRequest(

        @Schema(description = "新口令明文,服务端 BCrypt 落库,不落任何日志;强度口径见 PasswordPolicy", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入新密码")
        @Size(min = 8, max = 20, message = "密码长度需 8-20 位")
        String password) {
}
