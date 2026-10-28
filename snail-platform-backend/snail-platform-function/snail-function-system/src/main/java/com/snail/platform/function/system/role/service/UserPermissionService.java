package com.snail.platform.function.system.role.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.snail.platform.framework.security.authority.UserAuthorityProvider;
import com.snail.platform.function.system.menu.entity.SysMenu;
import com.snail.platform.function.system.menu.entity.SysRoleMenu;
import com.snail.platform.function.system.menu.mapper.SysMenuMapper;
import com.snail.platform.function.system.menu.mapper.SysRoleMenuMapper;
import com.snail.platform.function.system.role.entity.SysRole;
import com.snail.platform.function.system.role.entity.SysUserRole;
import com.snail.platform.function.system.role.mapper.SysRoleMapper;
import com.snail.platform.function.system.role.mapper.SysUserRoleMapper;
import com.snail.platform.function.system.user.entity.SysUser;
import com.snail.platform.function.system.user.mapper.SysUserMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * 职责:把"用户 -> 启用角色 -> 角色菜单授权 + admin 隐式规则"折成一组权限码,交给认证过滤器放进 SecurityContext。
 * 语义 A:role_code=admin 且 is_system=1 的启用角色隐式持有全部 scope=2 权限码;平台面(scope=1)永远只认 sys_role_menu。
 * 解析口径:用户本人已删或停用,权限码整体清空——停用立即生效,不等令牌过期;停用角色整体剔除;
 * 菜单自身或任一祖先停用即退出解析;目录/外链无码不产权限。
 * 缓存:userPerms 按用户 ID 缓存;用户状态/角色状态变更、用户角色分配/删除、菜单与授权变更都是失效点。
 * 上游:SnailAuthFilter 经 UserAuthorityProvider 端口调用;下游:sys_user/sys_user_role/sys_role/sys_menu/sys_role_menu 读。
 */
@Service
public class UserPermissionService implements UserAuthorityProvider {

    /** 租户内超级管理员的角色标识,与 V4 种子和租户开通模板同口径 */
    private static final String ADMIN_ROLE_CODE = "admin";

    /** 角色与菜单通用启用状态:0 正常 */
    private static final int STATUS_ACTIVE = 0;

    /** 菜单归属面:2 租户 */
    private static final int SCOPE_TENANT = 2;

    /** 用户-角色授权读 */
    private final SysUserRoleMapper userRoleMapper;

    /** 用户表读:权限码发放前先核对用户本人状态 */
    private final SysUserMapper userMapper;

    /** 角色表读 */
    private final SysRoleMapper roleMapper;

    /** 菜单目录读,权限码与状态都在这里 */
    private final SysMenuMapper menuMapper;

    /** 角色-菜单授权读 */
    private final SysRoleMenuMapper roleMenuMapper;

    public UserPermissionService(SysUserRoleMapper userRoleMapper, SysUserMapper userMapper,
                                 SysRoleMapper roleMapper, SysMenuMapper menuMapper,
                                 SysRoleMenuMapper roleMenuMapper) {
        this.userRoleMapper = userRoleMapper;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.menuMapper = menuMapper;
        this.roleMenuMapper = roleMenuMapper;
    }

    @Override
    @Cacheable(cacheNames = "userPerms", key = "#userId")
    public Set<String> authoritiesOf(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null || !Integer.valueOf(STATUS_ACTIVE).equals(user.getStatus())) {
            // 用户已删或已停用:权限码整体清空,停用动作即时生效,不等令牌过期
            return Set.of();
        }
        List<Long> roleIds = userRoleMapper.selectList(Wrappers.<SysUserRole>lambdaQuery()
                        .eq(SysUserRole::getUserId, userId))
                .stream().map(SysUserRole::getRoleId).distinct().toList();
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        List<SysRole> activeRoles = roleMapper.selectBatchIds(roleIds).stream()
                .filter(role -> Integer.valueOf(STATUS_ACTIVE).equals(role.getStatus()))
                .toList();
        if (activeRoles.isEmpty()) {
            return Set.of();
        }

        // 菜单目录整表取回一次:生效链要逐层上溯祖先,按节点回查库会把一次解析变成一串查询
        List<SysMenu> menus = menuMapper.selectList(null);
        Map<Long, SysMenu> menuById = menus.stream()
                .collect(Collectors.toMap(SysMenu::getMenuId, Function.identity()));
        Set<Long> activeMenuIds = menus.stream()
                .filter(menu -> MenuChains.active(menu, menuById))
                .map(SysMenu::getMenuId)
                .collect(Collectors.toSet());

        Set<String> authorities = new LinkedHashSet<>();
        // 语义 A 的落点:超管吞掉租户面所有带码菜单,平台面(scope=1)仍要靠下面的显式授权行
        if (activeRoles.stream().anyMatch(UserPermissionService::isSuperAdmin)) {
            menus.stream()
                    .filter(menu -> activeMenuIds.contains(menu.getMenuId()))
                    .filter(menu -> Integer.valueOf(SCOPE_TENANT).equals(menu.getScope()))
                    .map(SysMenu::getPermCode)
                    .filter(UserPermissionService::hasText)
                    .forEach(authorities::add);
        }

        Set<Long> activeRoleIds = activeRoles.stream().map(SysRole::getRoleId).collect(Collectors.toSet());
        List<SysRoleMenu> grants = roleMenuMapper.selectList(Wrappers.<SysRoleMenu>lambdaQuery()
                .in(SysRoleMenu::getRoleId, activeRoleIds));
        for (SysRoleMenu grant : grants) {
            // 授权行指向停用链上或已消失的菜单:静默作废,不为脏数据把整次解析掀翻
            if (!activeMenuIds.contains(grant.getMenuId())) {
                continue;
            }
            SysMenu menu = menuById.get(grant.getMenuId());
            if (menu != null && hasText(menu.getPermCode())) {
                authorities.add(menu.getPermCode());
            }
        }
        return Set.copyOf(authorities);
    }

    /**
     * 内置超管的唯一判定源:码对且必须 is_system=1。
     * 语义 A 的权限码侧、RoleMenuService 的导航侧、UserService 的"最后一个管理员"守卫都走这把尺,
     * 避免三处口径各自漂移。
     */
    public static boolean isSuperAdmin(SysRole role) {
        return ADMIN_ROLE_CODE.equals(role.getRoleCode()) && Integer.valueOf(1).equals(role.getIsSystem());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
