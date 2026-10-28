package com.snail.platform.function.system.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.snail.platform.framework.core.context.LoginUser;
import com.snail.platform.framework.security.login.LoginUserProvider;
import com.snail.platform.framework.security.token.SnailPrincipal;
import com.snail.platform.function.system.org.entity.SysOrg;
import com.snail.platform.function.system.org.mapper.SysOrgMapper;
import com.snail.platform.function.system.role.entity.SysRole;
import com.snail.platform.function.system.role.entity.SysUserRole;
import com.snail.platform.function.system.role.mapper.SysRoleMapper;
import com.snail.platform.function.system.role.mapper.SysUserRoleMapper;
import com.snail.platform.function.system.tenant.entity.SysTenant;
import com.snail.platform.function.system.tenant.mapper.SysTenantMapper;
import com.snail.platform.function.system.user.entity.SysUser;
import com.snail.platform.function.system.user.mapper.SysUserMapper;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * 职责:登录人富快照的唯一解析实现——人员在 sys_user,组织名在 sys_org,租户名在 sys_tenant,
 * 角色取启用角色并按 sort/roleId 升序;整体按 (租户, 用户) 缓存,常用字段不必每请求回查数据库。
 * 口径:停用角色不进快照;用户已不存在/已停用、租户不存在/已停用/已到期,一律返回令牌身份最小快照。
 * 最小快照没有 tokenVersion,认证过滤器据此判定会话无效——停用/到期不必等令牌自然过期。
 * 失效:user 域写路径按单个用户点清;租户停用/到期/改名按该租户的用户逐个点清,不波及其它租户;
 * 键里的租户段取自令牌身份而非上下文,失效端由 CacheEvictor.tenantKey 拼出同一个字面量。
 * 上游:SnailAuthFilter 经 LoginUserProvider 端口调用;下游:sys_user/sys_org/sys_tenant/sys_user_role/sys_role 读。
 */
@Service
public class UserSessionService implements LoginUserProvider {

    /** 角色/用户启用状态:0 正常 */
    private static final int STATUS_ACTIVE = 0;

    /** 租户停用状态:1 停用 */
    private static final int STATUS_DISABLED = 1;

    /** 用户表读,昵称与组织归属的事实源 */
    private final SysUserMapper userMapper;

    /** 组织表读,组织名称 */
    private final SysOrgMapper orgMapper;

    /** 租户表读,租户名称(ignore 表,按当前租户显式取) */
    private final SysTenantMapper tenantMapper;

    /** 用户-角色授权读 */
    private final SysUserRoleMapper userRoleMapper;

    /** 角色表读,标识与名称 */
    private final SysRoleMapper roleMapper;

    public UserSessionService(SysUserMapper userMapper, SysOrgMapper orgMapper, SysTenantMapper tenantMapper,
                              SysUserRoleMapper userRoleMapper, SysRoleMapper roleMapper) {
        this.userMapper = userMapper;
        this.orgMapper = orgMapper;
        this.tenantMapper = tenantMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
    }

    @Override
    @Cacheable(cacheNames = "userSession", key = "#p0.tenantId() + ':' + #p0.userId()")
    public LoginUser load(SnailPrincipal principal) {
        SysUser user = userMapper.selectById(principal.userId());
        if (user == null || !Integer.valueOf(STATUS_ACTIVE).equals(user.getStatus())) {
            // 已删或已停用:最小快照没有版本,过滤器判定会话无效
            return LoginUserProvider.minimal(principal);
        }
        SysTenant tenant = tenantMapper.selectById(user.getTenantId());
        if (tenant == null || Objects.equals(tenant.getStatus(), STATUS_DISABLED)
                || (tenant.getExpireTime() != null && tenant.getExpireTime().isBefore(LocalDateTime.now()))) {
            // 租户停用/到期与用户停用同口径:存量令牌立即失效,不靠令牌自然过期
            return LoginUserProvider.minimal(principal);
        }
        SysOrg org = user.getOrgId() == null ? null : orgMapper.selectById(user.getOrgId());
        return new LoginUser(user.getUserId(), user.getTenantId(), tenant.getName(),
                user.getUsername(), user.getNickname(), user.getOrgId(), org == null ? null : org.getName(),
                Long.valueOf(user.getTokenVersion()), rolesOf(user.getUserId()));
    }

    /** 只收启用角色;sort 升序、空值垫底,同序按 roleId 稳定排列 */
    private List<LoginUser.RoleInfo> rolesOf(Long userId) {
        List<Long> roleIds = userRoleMapper.selectList(Wrappers.<SysUserRole>lambdaQuery()
                        .eq(SysUserRole::getUserId, userId))
                .stream().map(SysUserRole::getRoleId).distinct().toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return roleMapper.selectBatchIds(roleIds).stream()
                .filter(role -> Integer.valueOf(STATUS_ACTIVE).equals(role.getStatus()))
                .sorted(Comparator.comparing(SysRole::getSort, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(SysRole::getRoleId))
                .map(role -> new LoginUser.RoleInfo(role.getRoleId(), role.getRoleCode(), role.getRoleName()))
                .toList();
    }
}
