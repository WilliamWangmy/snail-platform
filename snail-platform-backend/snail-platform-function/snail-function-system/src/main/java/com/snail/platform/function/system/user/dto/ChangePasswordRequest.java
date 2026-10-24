package com.snail.platform.function.system.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 职责:自助修改口令契约——原密码校验失败有计数锁定(见 ProfileService),新密码强度在服务层经
 * PasswordPolicy 与管理员建号/重置同口径兜底。
 * 上游:个人中心页面;下游:ProfileService.changePassword。
 */
@Schema(description = "修改口令请求")
public record ChangePasswordRequest(

        @Schema(description = "原密码", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入原密码")
        String oldPassword,

        @Schema(description = "新密码,长度 8-30", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入新密码")
        @Size(min = 8, max = 30, message = "密码长度 8-30")
        String newPassword) {
}
