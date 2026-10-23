package com.snail.platform.function.system.role.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.security.authority.AuthorityChecker;
import com.snail.platform.function.system.menu.entity.SysMenu;
import com.snail.platform.function.system.menu.entity.SysRoleMenu;
import com.snail.platform.function.system.menu.mapper.SysMenuMapper;
import com.snail.platform.function.system.menu.mapper.SysRoleMenuMapper;
import com.snail.platform.function.system.role.dto.MenuRouteVO;
import com.snail.platform.function.system.role.dto.RoleMenuNodeVO;
import com.snail.platform.function.system.role.entity.SysRole;
import com.snail.platform.function.system.role.entity.SysUserRole;
import com.snail.platform.function.system.role.mapper.SysRoleMapper;
import com.snail.platform.function.system.role.mapper.SysUserRoleMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 职责:角色与菜单的授权面——角色授权树、整包授权(平台面二次判权+分面合并)、当前用户菜单树。
 * 当前用户菜单树住在 role 子域:它以"用户被授了哪些菜单"为事实源,菜单子域不得反向依赖角色。
 * 授权口径:持有 platform:role:assign-menu 才可提交 platform 面菜单;否则只替换租户面授权,
 * 平台面既有授权原样保留,租户管理员无法顺手抹掉平台授权。
 * 缓存:授权变更清空 userPerms;菜单树本身不缓存,授权立即反映到导航。
 * 上游:RoleMenuController、MenuRouteController;下游:sys_role/sys_user_role/sys_menu/sys_role_menu 读,授权写。
 */
@Service
public class RoleMenuService {

    /** 平台面菜单的授予码;无此码只能授租户面 */
    private static final String PLATFORM_ASSIGN = "platform:role:assign-menu";

    /** 角色启用状态:0 正常 */
    private static final int STATUS_ACTIVE = 0;

    /** 类型:目录 */
    private static final int TYPE_DIR = 1;

    /** 类型:按钮 */
    private static final int TYPE_BUTTON = 3;

    /** 展示端:数据大屏 */
    private static final int TARGET_DASHBOARD = 2;

    /** 归属面:平台 */
    private static final int SCOPE_PLATFORM = 1;

    /** 归属面:租户 */
    private static final int SCOPE_TENANT = 2;

    /** 角色表读,授权前确认角色存在 */
    private final SysRoleMapper roleMapper;

    /** 用户-角色读,解析当前用户 */
    private final SysUserRoleMapper userRoleMapper;

    /** 角色-菜单授权读写 */
    private final SysRoleMenuMapper roleMenuMapper;

    /** 菜单目录读 */
    private final SysMenuMapper menuMapper;

    public RoleMenuService(SysRoleMapper roleMapper, SysUserRoleMapper userRoleMapper,
                           SysRoleMenuMapper roleMenuMapper, SysMenuMapper menuMapper) {
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMenuMapper = roleMenuMapper;
        this.menuMapper = menuMapper;
    }

    /**
     * 当前用户菜单树:target=1 站内(含外链),target=2 数据大屏。
     * 口径:内置 admin 隐式持有租户面全部菜单,其余人只看显式授权;按钮不入口径;
     * 父目录未授权但子菜单已授权时,目录作为容器补入;空目录与停用链整枝剪掉。
     */
    public List<MenuRouteVO> currentRoutes(Long userId, int target) {
        List<Long> roleIds = userRoleMapper.selectList(Wrappers.<SysUserRole>lambdaQuery()
                        .eq(SysUserRole::getUserId, userId))
                .stream().map(SysUserRole::getRoleId).distinct().toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        List<SysRole> activeRoles = roleMapper.selectBatchIds(roleIds).stream()
                .filter(role -> Integer.valueOf(STATUS_ACTIVE).equals(role.getStatus()))
                .toList();
        if (activeRoles.isEmpty()) {
            return List.of();
        }

        List<SysMenu> all = menuMapper.selectList(Wrappers.<SysMenu>lambdaQuery()
                .orderByAsc(SysMenu::getSort).orderByAsc(SysMenu::getMenuId));
        Map<Long, SysMenu> menuById = new LinkedHashMap<>();
        for (SysMenu menu : all) {
            menuById.put(menu.getMenuId(), menu);
        }
        Set<Long> activeIds = new HashSet<>();
        for (SysMenu menu : all) {
            if (MenuChains.active(menu, menuById)) {
                activeIds.add(menu.getMenuId());
            }
        }
        Set<Long> granted = new HashSet<>();
        for (SysRoleMenu grant : roleMenuMapper.selectList(Wrappers.<SysRoleMenu>lambdaQuery()
                .in(SysRoleMenu::getRoleId, activeRoles.stream().map(SysRole::getRoleId).toList()))) {
            granted.add(grant.getMenuId());
        }

        boolean superAdmin = activeRoles.stream().anyMatch(this::isSuperAdmin);
        Set<Long> selected = new LinkedHashSet<>();
        for (SysMenu menu : all) {
            if (!activeIds.contains(menu.getMenuId())) {
                continue;
            }
            boolean eligible = superAdmin && Integer.valueOf(SCOPE_TENANT).equals(menu.getScope());
            if (eligible || granted.contains(menu.getMenuId())) {
                selected.add(menu.getMenuId());
            }
        }

        Set<Long> kept = new LinkedHashSet<>();
        for (Long menuId : selected) {
            SysMenu menu = menuById.get(menuId);
            if (menu == null || !planeMatches(menu.getTarget(), target)
                    || Objects.equals(menu.getMenuType(), TYPE_BUTTON)) {
                continue;
            }
            kept.add(menuId);
            Long parentId = menu.getParentId();
            while (parentId != null && parentId != 0L) {
                SysMenu parent = menuById.get(parentId);
                if (parent == null || !activeIds.contains(parentId)
                        || !planeMatches(parent.getTarget(), target)) {
                    break;
                }
                if (Objects.equals(parent.getMenuType(), TYPE_DIR)) {
                    kept.add(parentId);
                }
                parentId = parent.getParentId();
            }
        }

        Map<Long, MenuRouteVO> nodes = new LinkedHashMap<>();
        for (SysMenu menu : all) {
            if (kept.contains(menu.getMenuId())) {
                nodes.put(menu.getMenuId(), MenuRouteVO.of(menu));
            }
        }
        List<MenuRouteVO> roots = new ArrayList<>();
        for (MenuRouteVO node : nodes.values()) {
            MenuRouteVO parent = node.parentId() == null ? null : nodes.get(node.parentId());
            if (parent != null && !Objects.equals(parent.menuId(), node.menuId())) {
                parent.children().add(node);
            } else {
                roots.add(node);
            }
        }
        roots.removeIf(node -> !prune(node));
        return roots;
    }

    /** 角色授权树:平台面仅对持 platform:role:assign-menu 者可见;checked 反映当前授权行 */
    public List<RoleMenuNodeVO> roleMenuTree(Long roleId) {
        requireRole(roleId);
        boolean platformVisible = AuthorityChecker.has(PLATFORM_ASSIGN);
        Set<Long> granted = new HashSet<>();
        for (SysRoleMenu grant : roleMenuMapper.selectList(Wrappers.<SysRoleMenu>lambdaQuery()
                .eq(SysRoleMenu::getRoleId, roleId))) {
            granted.add(grant.getMenuId());
        }
        List<SysMenu> all = menuMapper.selectList(Wrappers.<SysMenu>lambdaQuery()
                .orderByAsc(SysMenu::getSort).orderByAsc(SysMenu::getMenuId));
        Map<Long, RoleMenuNodeVO> nodes = new LinkedHashMap<>();
        for (SysMenu menu : all) {
            if (!platformVisible && Integer.valueOf(SCOPE_PLATFORM).equals(menu.getScope())) {
                continue;
            }
            nodes.put(menu.getMenuId(), RoleMenuNodeVO.of(menu, granted.contains(menu.getMenuId())));
        }
        List<RoleMenuNodeVO> roots = new ArrayList<>();
        for (SysMenu menu : all) {
            RoleMenuNodeVO node = nodes.get(menu.getMenuId());
            if (node == null) {
                continue;
            }
            RoleMenuNodeVO parent = menu.getParentId() == null ? null : nodes.get(menu.getParentId());
            if (parent != null && !Objects.equals(parent.menuId(), node.menuId())) {
                parent.children().add(node);
            } else {
                roots.add(node);
            }
        }
        return roots;
    }

    /**
     * 整包授权:持平台码者全量替换;否则只替换租户面,平台面既有行保留。
     * 提交平台面菜单但无平台码 → 403,不静默丢弃。
     */
    @Transactional
    @CacheEvict(cacheNames = "userPerms", allEntries = true)
    public void assign(Long roleId, List<Long> menuIds) {
        requireRole(roleId);
        Set<Long> distinct = new LinkedHashSet<>(menuIds);
        List<SysMenu> menus = distinct.isEmpty() ? List.of() : menuMapper.selectBatchIds(distinct);
        if (menus.size() != distinct.size()) {
            throw BizException.badRequest("菜单不存在");
        }
        boolean canAssignPlatform = AuthorityChecker.has(PLATFORM_ASSIGN);
        boolean touchesPlatform = menus.stream()
                .anyMatch(menu -> Integer.valueOf(SCOPE_PLATFORM).equals(menu.getScope()));
        if (touchesPlatform && !canAssignPlatform) {
            throw new AccessDeniedException("无权限授予平台菜单");
        }

        if (canAssignPlatform) {
            roleMenuMapper.delete(Wrappers.<SysRoleMenu>lambdaQuery()
                    .eq(SysRoleMenu::getRoleId, roleId));
        } else {
            List<Long> platformMenuIds = menuMapper.selectList(Wrappers.<SysMenu>lambdaQuery()
                            .eq(SysMenu::getScope, SCOPE_PLATFORM))
                    .stream().map(SysMenu::getMenuId).toList();
            if (platformMenuIds.isEmpty()) {
                roleMenuMapper.delete(Wrappers.<SysRoleMenu>lambdaQuery()
                        .eq(SysRoleMenu::getRoleId, roleId));
            } else {
                roleMenuMapper.delete(Wrappers.<SysRoleMenu>lambdaQuery()
                        .eq(SysRoleMenu::getRoleId, roleId)
                        .notIn(SysRoleMenu::getMenuId, platformMenuIds));
            }
        }
        for (Long menuId : distinct) {
            SysRoleMenu grant = new SysRoleMenu();
            grant.setRoleId(roleId);
            grant.setMenuId(menuId);
            roleMenuMapper.insert(grant);
        }
    }

    private SysRole requireRole(Long roleId) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw BizException.badRequest("角色不存在");
        }
        return role;
    }

    private boolean isSuperAdmin(SysRole role) {
        return "admin".equals(role.getRoleCode()) && Integer.valueOf(1).equals(role.getIsSystem());
    }

    /** 站内端要站内(1)与外链(3),数据大屏端只要大屏(2) */
    private static boolean planeMatches(int menuTarget, int requestedTarget) {
        return requestedTarget == TARGET_DASHBOARD
                ? menuTarget == TARGET_DASHBOARD
                : menuTarget != TARGET_DASHBOARD;
    }

    /** 自底向上剪枝:空目录不成导航;页面与外链即使无子也保留 */
    private static boolean prune(MenuRouteVO node) {
        node.children().removeIf(child -> !prune(child));
        return !Objects.equals(node.menuType(), TYPE_DIR) || !node.children().isEmpty();
    }
}
