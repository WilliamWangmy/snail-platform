package com.snail.platform.function.system.role.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.snail.platform.framework.core.context.LoginUserContextHolder;
import com.snail.platform.framework.db.datascope.DataScopeFilter;
import com.snail.platform.function.system.org.service.OrgService;
import com.snail.platform.function.system.role.entity.SysRole;
import com.snail.platform.function.system.role.entity.SysUserRole;
import com.snail.platform.function.system.role.mapper.SysRoleMapper;
import com.snail.platform.function.system.role.mapper.SysUserRoleMapper;
import com.snail.platform.function.system.user.entity.SysUser;
import com.snail.platform.function.system.user.mapper.SysUserMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 职责:数据权限判定——把当前登录者的多角色 data_scope 折成一条 DataScopeFilter,口径是"多角色 OR 并集":
 * 任一角色为 1(全部)即返回 null 不设限;否则组织臂收集 2(本组织)/3(本组织及以下)命中的 org 集,
 * 本人臂在角色 4 或"无任何可用角色"时置为当前用户(无授权的人退回只看自己,不给他开组织口子)。
 * 停用角色(status=1)一律不参与并集——权限包被收走就是收走。
 * 同时对外提供两个判定口:操作者最宽档(角色配置不得宽于它)与目标组织可授权性(建号/调岗用)。
 * 上游:各业务 Service 的列表查询(经 DataScopeContextHolder.filter 开窗);下游:sys_user_role/sys_role 读、
 * sys_user 读、orgSubtree 缓存(OrgService.subtreeIds)。本服务只产谓词对象,不改 SQL——注入在 framework-db 拦截器。
 */
@Service
public class DataScopeService {

    /** 档位:全部数据,不设限 */
    private static final int SCOPE_ALL = 1;

    /** 档位:本组织 */
    private static final int SCOPE_OWN_ORG = 2;

    /** 档位:本组织及以下 */
    private static final int SCOPE_ORG_SUBTREE = 3;

    /** 档位:仅本人 */
    private static final int SCOPE_SELF = 4;

    /** 用户表读:取当前登录者的 org 归属 */
    private final SysUserMapper userMapper;

    /** 授权关系读 */
    private final SysUserRoleMapper userRoleMapper;

    /** 角色表读:取各授权角色的 data_scope 与状态 */
    private final SysRoleMapper roleMapper;

    /** 子树 ID 清单,档位三消费 orgSubtree 缓存 */
    private final OrgService orgService;

    public DataScopeService(SysUserMapper userMapper, SysUserRoleMapper userRoleMapper,
                            SysRoleMapper roleMapper, OrgService orgService) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.orgService = orgService;
    }

    /**
     * 返回 null 即"全部档,不设限";返回列表型谓词即"有限可见"。
     * 拿不到当前登录者(未认证上下文的内部调用)同样返回 null——权限只在有人格的前提下成立。
     */
    public DataScopeFilter currentFilterOrNull() {
        Long userId = LoginUserContextHolder.userId();
        if (userId == null) {
            return null;
        }
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            return null;
        }

        List<Long> roleIds = userRoleMapper.selectList(Wrappers.<SysUserRole>lambdaQuery()
                        .eq(SysUserRole::getUserId, userId))
                .stream().map(SysUserRole::getRoleId).toList();
        List<SysRole> roles = roleIds.isEmpty() ? List.of() : roleMapper.selectBatchIds(roleIds);

        Set<Long> orgIds = new LinkedHashSet<>();
        boolean selfOnly = false;
        boolean usableRole = false;
        for (SysRole role : roles) {
            if (role.getStatus() != null && role.getStatus() == 1) {
                continue;
            }
            if (role.getDataScope() == null) {
                continue;
            }
            usableRole = true;
            switch (role.getDataScope()) {
                case SCOPE_ALL -> {
                    return null;
                }
                case SCOPE_OWN_ORG -> {
                    if (user.getOrgId() != null) {
                        orgIds.add(user.getOrgId());
                    }
                }
                case SCOPE_ORG_SUBTREE -> {
                    if (user.getOrgId() != null) {
                        orgIds.addAll(orgService.subtreeIds(user.getOrgId()));
                    }
                }
                case SCOPE_SELF -> selfOnly = true;
                default -> { /* 未知档位不参与并集,判定时已拒落库 */ }
            }
        }
        // 无任何可用角色:退回只看本人,与"角色全被停用"同形
        if (!usableRole) {
            selfOnly = true;
        }

        if (orgIds.isEmpty() && !selfOnly) {
            // 只挂了组织档但人没有组织归属:可见集为空,给一个永假组织 ID 表达"看不到任何行"
            return DataScopeFilter.of(List.of(0L), null);
        }
        return DataScopeFilter.of(orgIds.isEmpty() ? null : List.copyOf(orgIds),
                selfOnly ? userId : null);
    }

    /**
     * 操作者自身最宽数据档位:启用角色里最小的 data_scope(1 全部最宽)。
     * 无登录上下文或无可用启用角色返回 null,调用方按"不可配置数据权限"处理。
     */
    public Integer operatorBroadestDataScope() {
        Long userId = LoginUserContextHolder.userId();
        if (userId == null) {
            return null;
        }
        List<Long> roleIds = userRoleMapper.selectList(Wrappers.<SysUserRole>lambdaQuery()
                        .eq(SysUserRole::getUserId, userId))
                .stream().map(SysUserRole::getRoleId).distinct().toList();
        if (roleIds.isEmpty()) {
            return null;
        }
        // 停用角色不构成操作者的权限凭据,不参与档位折算
        return roleMapper.selectBatchIds(roleIds).stream()
                .filter(role -> Integer.valueOf(0).equals(role.getStatus()))
                .map(SysRole::getDataScope)
                .filter(Objects::nonNull)
                .min(Integer::compareTo)
                .orElse(null);
    }

    /**
     * 目标组织是否可用于授权/建号:全部档或仅本人档(无组织臂)放行,组织档必须命中可见组织集。
     * 仅本人档不构成组织边界——写出的行仍由 create_by 收在本人臂内,不越权;
     * 有组织臂却把用户挂到看不见的组织,才是真正需要拦住的口子。
     */
    public boolean canAssignOrg(Long orgId) {
        DataScopeFilter filter = currentFilterOrNull();
        if (filter == null || filter.orgIds() == null || filter.orgIds().isEmpty()) {
            return true;
        }
        return filter.orgIds().contains(orgId);
    }
}
