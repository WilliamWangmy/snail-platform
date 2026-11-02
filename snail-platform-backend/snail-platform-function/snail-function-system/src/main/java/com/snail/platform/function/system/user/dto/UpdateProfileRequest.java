package com.snail.platform.function.system.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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

        @Schema(description = "手机号,可空;非空须为 11 位大陆手机号或 3-20 位含区号的座机")
        @Size(max = 20, message = "手机号过长")
        @Pattern(regexp = "^(?:$|1[3-9]\\d{9}|0\\d{2,3}-?[1-9]\\d{6,7})$", message = "手机号格式不正确")
        String phone) {
}
