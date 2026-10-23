package com.snail.platform.function.system.role.dto;

import com.snail.platform.function.system.role.entity.SysRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 职责:角色出参对象。is_system 透出给前端做按钮级禁用(删除键置灰),最终拒绝仍在服务端。
 * 上游:RoleService;下游:角色管理列表与详情。
 */
@Schema(description = "角色信息")
public record RoleVO(

        @Schema(description = "角色ID")
        Long roleId,

        @Schema(description = "角色名称")
        String roleName,

        @Schema(description = "角色标识,建后不可改")
        String roleCode,

        @Schema(description = "数据权限:1 全部 2 本组织 3 本组织及以下 4 本人")
        Integer dataScope,

        @Schema(description = "内置角色:0 否 1 是")
        Integer isSystem,

        @Schema(description = "显示顺序")
        Integer sort,

        @Schema(description = "状态:0 正常 1 停用")
        Integer status,

        @Schema(description = "备注")
        String remark,

        @Schema(description = "创建时间")
        LocalDateTime createTime) {

    public static RoleVO from(SysRole role) {
        return new RoleVO(role.getRoleId(), role.getRoleName(), role.getRoleCode(), role.getDataScope(),
                role.getIsSystem(), role.getSort(), role.getStatus(), role.getRemark(), role.getCreateTime());
    }
}
