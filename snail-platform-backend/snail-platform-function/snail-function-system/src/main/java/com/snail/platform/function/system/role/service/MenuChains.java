package com.snail.platform.function.system.role.service;

import com.snail.platform.function.system.menu.entity.SysMenu;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 职责:菜单生效链判定——节点自身与全部祖先都正常才算生效;父节点缺失、被逻辑删或成环一律失效。
 * 上游:UserPermissionService 权限解析、RoleMenuService 当前用户菜单树;下游:sys_menu 只读快照。
 */
final class MenuChains {

    private static final int STATUS_ACTIVE = 0;

    private MenuChains() {
    }

    static boolean active(SysMenu menu, Map<Long, SysMenu> menuById) {
        Set<Long> visited = new HashSet<>();
        SysMenu current = menu;
        while (current != null) {
            if (!Integer.valueOf(STATUS_ACTIVE).equals(current.getStatus())) {
                return false;
            }
            if (!visited.add(current.getMenuId())) {
                return false;
            }
            Long parentId = current.getParentId();
            if (parentId == null || parentId == 0L) {
                return true;
            }
            current = menuById.get(parentId);
            if (current == null) {
                return false;
            }
        }
        return false;
    }
}
