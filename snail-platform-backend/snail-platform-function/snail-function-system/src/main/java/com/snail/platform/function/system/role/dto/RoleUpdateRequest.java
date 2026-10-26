package com.snail.platform.function.system.role.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 职责:修改角色契约。刻意不含 role_code——建后不可改,接口收不到就不存在被改的路径;
 * 内置角色(is_system=1)对管理端只读,修改/停用/删除在 Service 一律拒绝。
 * 上游:角色管理页面;下游:RoleService.update。
 */
@Schema(description = "修改角色请求")
public record RoleUpdateRequest(

        @Schema(description = "角色名称", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入角色名称")
        @Size(max = 30, message = "角色名称过长")
        String roleName,

        @Schema(description = "数据权限:1 全部 2 本组织 3 本组织及以下 4 本人")
        @Min(value = 1, message = "数据权限取值 1-4")
        @Max(value = 4, message = "数据权限取值 1-4")
        Integer dataScope,

        @Schema(description = "显示顺序")
        @Min(value = 0, message = "顺序不能为负")
        Integer sort,

        @Schema(description = "状态:0 正常 1 停用")
        @Min(value = 0, message = "状态取值 0/1")
        @Max(value = 1, message = "状态取值 0/1")
        Integer status,

        @Schema(description = "备注")
        @Size(max = 500, message = "备注过长")
        String remark) {
}
