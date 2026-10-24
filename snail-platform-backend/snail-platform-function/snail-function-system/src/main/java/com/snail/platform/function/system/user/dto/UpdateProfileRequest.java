package com.snail.platform.function.system.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 职责:自助修改档案契约——只准改 nickname/email/phone,username/org/status 不在契约里,
 * 服务层再按白名单字段硬拼 UPDATE,契约外字段天然改不到。
 * 上游:个人中心页面;下游:ProfileService.update。
 */
@Schema(description = "修改个人资料请求")
public record UpdateProfileRequest(

        @Schema(description = "显示名", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入显示名")
        @Size(max = 30, message = "显示名过长")
        String nickname,

        @Schema(description = "邮箱")
        @Email(message = "邮箱格式不正确")
        @Size(max = 50, message = "邮箱过长")
        String email,

        @Schema(description = "手机号")
        @Size(max = 20, message = "手机号过长")
        String phone) {
}
