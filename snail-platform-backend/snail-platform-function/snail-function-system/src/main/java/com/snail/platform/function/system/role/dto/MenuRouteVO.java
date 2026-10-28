package com.snail.platform.function.system.role.dto;

import com.snail.platform.function.system.menu.entity.SysMenu;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;

/**
 * 职责:当前用户导航/路由节点出参——只含目录与菜单,不含按钮与权限码;visible 交给前端决定是否入侧栏。
 * 上游:RoleMenuService.currentRoutes;下游:admin 侧栏与路由表、dashboard 侧栏与路由表。
 */
@Schema(description = "当前用户菜单节点")
public record MenuRouteVO(

        @Schema(description = "菜单ID")
        Long menuId,

        @Schema(description = "父菜单ID,根为 0")
        Long parentId,

        @Schema(description = "菜单名称")
        String menuName,

        @Schema(description = "类型:1 目录 2 菜单")
        Integer menuType,

        @Schema(description = "展示端:1 站内 2 数据大屏 3 外链")
        Integer target,

        @Schema(description = "路由/地址;外链为完整 URL")
        String path,

        @Schema(description = "前端组件路径;外链为空")
        String component,

        @Schema(description = "图标名")
        String icon,

        @Schema(description = "同级显示顺序")
        Integer sort,

        @Schema(description = "显示:0 显示 1 隐藏")
        Integer visible,

        @Schema(description = "子节点")
        List<MenuRouteVO> children) {

    /** PO 转节点:children 先给可变空列表,由 RoleMenuService.currentRoutes 按 parentId 逐个挂入 */
    public static MenuRouteVO of(SysMenu menu) {
        return new MenuRouteVO(menu.getMenuId(), menu.getParentId(), menu.getMenuName(), menu.getMenuType(),
                menu.getTarget(), menu.getPath(), menu.getComponent(), menu.getIcon(), menu.getSort(),
                menu.getVisible(), new ArrayList<>());
    }
}
