package com.snail.platform.function.system.role.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 职责:新增角色契约。role_code 是程序判权坐标,租户内唯一且建后不可改,故只出现在新增。
 * 上游:角色管理页面;下游:RoleService.create。is_system 不经接口,只有模板/种子能置 1。
 */
@Schema(description = "新增角色请求")
public record RoleCreateRequest(

        @Schema(description = "角色名称", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入角色名称")
        @Size(max = 30, message = "角色名称过长")
        String roleName,

        @Schema(description = "角色标识,小写字母/数字/下划线,租户内唯一", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入角色标识")
        @Size(min = 2, max = 64, message = "角色标识长度 2-64")
        @Pattern(regexp = "[a-z0-9_]+", message = "角色标识只允许小写字母、数字、下划线")
        String roleCode,

        @Schema(description = "数据权限:1 全部 2 本组织 3 本组织及以下 4 本人", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请选择数据权限")
        @Min(value = 1, message = "数据权限取值 1-4")
        @Max(value = 4, message = "数据权限取值 1-4")
        Integer dataScope,

        @Schema(description = "显示顺序,默认 0")
        @Min(value = 0, message = "顺序不能为负")
        Integer sort,

        @Schema(description = "备注")
        @Size(max = 500, message = "备注过长")
        String remark) {
}
