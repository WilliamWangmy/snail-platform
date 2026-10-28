package com.snail.platform.function.system.menu.dto;

import com.snail.platform.function.system.menu.entity.SysMenu;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;

/**
 * 职责:平台菜单目录树节点出参,含启用状态与内置标记;children 由服务层组好。
 * 上游:MenuService.tree;下游:平台菜单管理页面。
 */
@Schema(description = "菜单目录树节点")
public record MenuNodeVO(

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

        @Schema(description = "路由/地址")
        String path,

        @Schema(description = "前端组件路径")
        String component,

        @Schema(description = "权限码")
        String permCode,

        @Schema(description = "图标名")
        String icon,

        @Schema(description = "同级显示顺序")
        Integer sort,

        @Schema(description = "显示:0 显示 1 隐藏")
        Integer visible,

        @Schema(description = "状态:0 正常 1 停用")
        Integer status,

        @Schema(description = "内置节点:0 否 1 是")
        Integer isSystem,

        @Schema(description = "备注")
        String remark,

        @Schema(description = "子节点")
        List<MenuNodeVO> children) {

    /** PO 转节点:children 先给可变空列表,由 MenuService.tree 按 parentId 逐个挂入 */
    public static MenuNodeVO of(SysMenu menu) {
        return new MenuNodeVO(menu.getMenuId(), menu.getParentId(), menu.getMenuName(), menu.getMenuType(),
                menu.getTarget(), menu.getScope(), menu.getPath(), menu.getComponent(), menu.getPermCode(),
                menu.getIcon(), menu.getSort(), menu.getVisible(), menu.getStatus(), menu.getIsSystem(),
                menu.getRemark(), new ArrayList<>());
    }
}
