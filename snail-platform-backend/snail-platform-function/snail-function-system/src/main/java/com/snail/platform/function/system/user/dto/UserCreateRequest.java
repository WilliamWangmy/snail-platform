package com.snail.platform.function.system.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 职责:新增用户契约。username 全局唯一(登录不选租户的代价),查重在服务端按全表口径做;
 * roleIds 可选,提交即在建号同事务内完成授权。
 * 上游:用户管理页面;下游:UserService.create。
 */
@Schema(description = "新增用户请求")
public record UserCreateRequest(

        @Schema(description = "登录名,全表唯一,建后不可改", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入登录名")
        @Size(min = 3, max = 30, message = "登录名长度 3-30")
        @Pattern(regexp = "[A-Za-z0-9_.@-]+", message = "登录名只允许字母、数字、下划线、点、@、连字符")
        String username,

        @Schema(description = "初始口令,BCrypt 落库;强度口径见 PasswordPolicy", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入初始密码")
        @Size(min = 8, max = 20, message = "密码长度需 8-20 位")
        String password,

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
        String phone,

        @Schema(description = "所属组织,须为本租户内状态正常的节点", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请选择所属组织")
        Long orgId,

        @Schema(description = "备注")
        @Size(max = 500, message = "备注过长")
        String remark,

        @Schema(description = "授权角色ID集,可空")
        List<Long> roleIds) {
}
