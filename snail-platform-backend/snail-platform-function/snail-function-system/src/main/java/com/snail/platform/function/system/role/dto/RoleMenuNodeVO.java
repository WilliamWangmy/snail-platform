package com.snail.platform.function.system.role.dto;

import com.snail.platform.function.system.menu.entity.SysMenu;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;

/**
 * 职责:角色授权树的节点出参——带 checked 标记与归属面,前端按此渲染勾选与平台面禁选。
 * 上游:RoleMenuService.roleMenuTree;下游:角色管理页的授权弹窗。
 */
@Schema(description = "角色授权树节点")
public record RoleMenuNodeVO(

        @Schema(description = "菜单ID")
        Long menuId,

        @Schema(description = "父菜单ID,根为 0")
        Long parentId,

        @Schema(description = "菜单名称")
        String menuName,

        @Schema(description = "类型:1 目录 2 菜单 3 按钮")
        Integer menuType,

        @Schema(description = "展示端:1 站内 2 数据大屏 3 外链")
        Integer target,

        @Schema(description = "归属面:1 平台 2 租户")
        Integer scope,

        @Schema(description = "权限码")
        String permCode,

        @Schema(description = "状态:0 正常 1 停用")
        Integer status,

        @Schema(description = "是否已授权")
        boolean checked,

        @Schema(description = "子节点")
        List<RoleMenuNodeVO> children) {

    public static RoleMenuNodeVO of(SysMenu menu, boolean checked) {
        return new RoleMenuNodeVO(menu.getMenuId(), menu.getParentId(), menu.getMenuName(), menu.getMenuType(),
                menu.getTarget(), menu.getScope(), menu.getPermCode(), menu.getStatus(), checked,
                new ArrayList<>());
    }
}
