package com.snail.platform.function.system.user.service;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.snail.platform.framework.core.context.LoginUserContextHolder;
import com.snail.platform.framework.core.context.TenantContextHolder;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.db.PageAssembler;
import com.snail.platform.framework.db.datascope.DataScopeContextHolder;
import com.snail.platform.framework.db.datascope.DataScopeFilter;
import com.snail.platform.function.system.org.entity.SysOrg;
import com.snail.platform.function.system.org.mapper.SysOrgMapper;
import com.snail.platform.function.system.role.entity.SysRole;
import com.snail.platform.function.system.role.entity.SysUserRole;
import com.snail.platform.function.system.role.mapper.SysRoleMapper;
import com.snail.platform.function.system.role.mapper.SysUserRoleMapper;
import com.snail.platform.function.system.role.service.DataScopeService;
import com.snail.platform.function.system.tenant.entity.SysTenant;
import com.snail.platform.function.system.tenant.mapper.SysTenantMapper;
import com.snail.platform.function.system.user.dto.UserCreateRequest;
import com.snail.platform.function.system.user.dto.UserQuery;
import com.snail.platform.function.system.user.dto.UserUpdateRequest;
import com.snail.platform.function.system.user.dto.UserVO;
import com.snail.platform.function.system.user.entity.SysUser;
import com.snail.platform.function.system.user.mapper.SysUserMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 职责:用户管理——分页检索、新增(含建号即授权)、修改、重置口令、停用/启用、逻辑删除、角色全量覆盖分配。
 * 关键口径:username 全表唯一且建后不可改,查重走无租户窄窗口(全表口径),撞库提示"登录名已存在";
 * 新增受租户 accountLimit 约束(锁 sys_tenant 行后计数,只拦新增不追认存量);
 * 删除同事务物理清 sys_user_role——人不在了授权就该空;
 * 自我保护:不允许停用或删除当前登录者自己,防管理员把自己锁在门外。
 * 数据权限:列表查询按当前登录者 data_scope 开窗过滤;详情、档案修改、停用、删除、重置口令、强制下线、
 * 分配角色七类定点操作必须过 requireUserInScope 闸(目标用户在可见集内);建号与调岗还需目标组织可授权。
 * 分配的角色档位不得宽于操作者自身最宽档(resolveGrantable),防纵向提权。
 * 授权集变化/删号/停用启用会让该用户的 userPerms 与 userSession 缓存失效,停用与收权即时生效,不等令牌过期。
 * 上游:UserController;下游:sys_user/sys_user_role 写读、sys_org/sys_role 前置校验、sys_tenant 上限读取。
 */
@Service
public class UserService {

    /** 用户表读写 */
    private final SysUserMapper userMapper;

    /** 组织校验:归属必须是本租户状态正常的节点 */
    private final SysOrgMapper orgMapper;

    /** 角色校验:授权集必须全部落在本租户 */
    private final SysRoleMapper roleMapper;

    /** 授权关系读写 */
    private final SysUserRoleMapper userRoleMapper;

    /** 租户资料读:accountLimit 校验,ignore 表按当前租户显式取 */
    private final SysTenantMapper tenantMapper;

    /** 列表查询的当前登录者 data_scope 判定 */
    private final DataScopeService dataScopeService;

    public UserService(SysUserMapper userMapper, SysOrgMapper orgMapper, SysRoleMapper roleMapper,
                       SysUserRoleMapper userRoleMapper, SysTenantMapper tenantMapper,
                       DataScopeService dataScopeService) {
        this.userMapper = userMapper;
        this.orgMapper = orgMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.tenantMapper = tenantMapper;
        this.dataScopeService = dataScopeService;
    }

    public PageResult<UserVO> page(UserQuery query) {
        Page<SysUser> page = PageAssembler.from(query);
        DataScopeFilter scope = dataScopeService.currentFilterOrNull();
        IPage<SysUser> result = DataScopeContextHolder.filter(scope, () -> userMapper.selectPage(page,
                Wrappers.<SysUser>lambdaQuery()
                        .eq(query.orgId() != null, SysUser::getOrgId, query.orgId())
                        .like(notBlank(query.username()), SysUser::getUsername, query.username())
                        .like(notBlank(query.nickname()), SysUser::getNickname, query.nickname())
                        .eq(query.status() != null, SysUser::getStatus, query.status())
                        .orderByDesc(SysUser::getUserId)));
        return PageResult.of(result.getRecords().stream().map(u -> UserVO.of(u, null)).toList(),
                result.getTotal());
    }

    public UserVO detail(Long userId) {
        requireUserInScope(userId);
        return UserVO.of(requireUser(userId), roleIdsOf(userId));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Long create(UserCreateRequest request) {
        String username = request.username().trim();
        Long taken = TenantContextHolder.withoutTenant(() -> userMapper.selectCount(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username)));
        if (taken > 0) {
            throw BizException.badRequest("登录名已存在");
        }
        PasswordPolicy.validate(request.password());
        requireUsableOrg(request.orgId());
        requireAssignableOrg(request.orgId());
        checkAccountLimit();

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setNickname(request.nickname().trim());
        user.setPassword(BCrypt.hashpw(request.password()));
        user.setEmail(request.email());
        user.setPhone(request.phone());
        user.setOrgId(request.orgId());
        user.setStatus(0);
        user.setRemark(request.remark());
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // deleted 行仍占唯一键,登录名终身占用;数据库拦下时统一转业务拒绝
            throw BizException.badRequest("登录名已存在");
        }

        if (request.roleIds() != null && !request.roleIds().isEmpty()) {
            replaceRoles(user.getUserId(), resolveGrantable(request.roleIds()));
        }
        return user.getUserId();
    }

    @Transactional
    @CacheEvict(cacheNames = "userSession", key = "#userId")
    public void update(Long userId, UserUpdateRequest request) {
        SysUser user = requireUser(userId);
        requireUserInScope(userId);
        requireUsableOrg(request.orgId());
        requireAssignableOrg(request.orgId());
        userMapper.update(new SysUser(), Wrappers.<SysUser>lambdaUpdate()
                .set(SysUser::getNickname, request.nickname().trim())
                .set(SysUser::getEmail, request.email())
                .set(SysUser::getPhone, request.phone())
                .set(SysUser::getOrgId, request.orgId())
                .set(SysUser::getRemark, request.remark())
                .eq(SysUser::getUserId, user.getUserId()));
    }

    @Transactional
    @CacheEvict(cacheNames = "userSession", key = "#userId")
    public void resetPassword(Long userId, String rawPassword) {
        SysUser user = requireUser(userId);
        requireUserInScope(userId);
        PasswordPolicy.validate(rawPassword);
        userMapper.update(new SysUser(), Wrappers.<SysUser>lambdaUpdate()
                .set(SysUser::getPassword, BCrypt.hashpw(rawPassword))
                .setSql("token_version = token_version + 1")
                .eq(SysUser::getUserId, user.getUserId()));
    }

    /** 强制下线:令牌版本自增 + 快照失效,该用户存量令牌下一次请求即 401;不判自我,由调用方决定 */
    @Transactional
    @CacheEvict(cacheNames = "userSession", key = "#userId")
    public void forceLogout(Long userId) {
        requireUser(userId);
        requireUserInScope(userId);
        userMapper.update(new SysUser(), Wrappers.<SysUser>lambdaUpdate()
                .setSql("token_version = token_version + 1")
                .eq(SysUser::getUserId, userId));
    }

    @Transactional
    @CacheEvict(cacheNames = {"userPerms", "userSession"}, key = "#userId")
    public void changeStatus(Long userId, int status) {
        SysUser user = requireUser(userId);
        if (status == 1 && Objects.equals(LoginUserContextHolder.userId(), userId)) {
            throw BizException.badRequest("不能停用自己的账号");
        }
        if (Objects.equals(user.getStatus(), status)) {
            return;
        }
        requireUserInScope(userId);
        userMapper.update(new SysUser(), Wrappers.<SysUser>lambdaUpdate()
                .set(SysUser::getStatus, status)
                .setSql("token_version = token_version + 1")
                .eq(SysUser::getUserId, userId));
    }

    /** 逻辑删用户 + 物理清授权同事务:授权关系不是历史 */
    @Transactional
    @CacheEvict(cacheNames = {"userPerms", "userSession"}, key = "#userId")
    public void delete(Long userId) {
        requireUser(userId);
        if (Objects.equals(LoginUserContextHolder.userId(), userId)) {
            throw BizException.badRequest("不能删除自己的账号");
        }
        requireUserInScope(userId);
        // 逻辑删后旧令牌仍可能落在其他节点缓存里,版本前推让最小快照之外的残留也失去会话资格
        userMapper.update(new SysUser(), Wrappers.<SysUser>lambdaUpdate()
                .setSql("token_version = token_version + 1")
                .eq(SysUser::getUserId, userId));
        userMapper.deleteById(userId);
        userRoleMapper.delete(Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, userId));
    }

    /** 全量覆盖:先删该用户全部授权再按新集插入,提交集即最终态 */
    @Transactional
    @CacheEvict(cacheNames = {"userPerms", "userSession"}, key = "#userId")
    public void assignRoles(Long userId, List<Long> roleIds) {
        requireUser(userId);
        requireUserInScope(userId);
        replaceRoles(userId, resolveGrantable(roleIds));
    }

    /**
     * 解析并校验授权集:角色必须全部落在本租户,且档位不得宽于操作者自身最宽档——
     * 防"仅本人档"的操作者借分配角色授出全部档角色,给自己或他人开纵向提权口子。
     */
    private List<SysRole> resolveGrantable(List<Long> roleIds) {
        List<Long> distinct = roleIds == null ? List.of() : roleIds.stream()
                .filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return List.of();
        }
        List<SysRole> roles = roleMapper.selectBatchIds(distinct);
        if (roles.size() != distinct.size()) {
            throw BizException.badRequest("包含不存在或跨租户的角色");
        }
        Integer broadest = dataScopeService.operatorBroadestDataScope();
        if (broadest == null) {
            throw BizException.forbidden("当前登录人没有可用角色,不能分配角色");
        }
        for (SysRole role : roles) {
            if (role.getDataScope() == null || role.getDataScope() < broadest) {
                throw BizException.forbidden("不能分配数据档位宽于自身权限的角色: " + role.getRoleName());
            }
        }
        return roles;
    }

    /**
     * 定点写的数据权限闸:目标用户必须在当前登录者 data_scope 的可见集内。
     * 自己天然在本人臂内,不受 create_by 口径限制(否则"仅本人档"会被自己不是自己创建而挡下);
     * 其余目标复用列表检索的同一口径(开窗计数),全部档返回 null 不设限。
     */
    private void requireUserInScope(Long userId) {
        if (Objects.equals(LoginUserContextHolder.userId(), userId)) {
            return;
        }
        DataScopeFilter scope = dataScopeService.currentFilterOrNull();
        if (scope == null) {
            return;
        }
        Long visible = DataScopeContextHolder.filter(scope, () -> userMapper.selectCount(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUserId, userId)));
        if (visible == null || visible == 0) {
            throw BizException.forbidden("目标用户不在当前数据权限范围内");
        }
    }

    /** 建号/调岗的组织闸:组织档操作者只能把人挂进自己可见的组织 */
    private void requireAssignableOrg(Long orgId) {
        if (!dataScopeService.canAssignOrg(orgId)) {
            throw BizException.forbidden("目标组织不在当前数据权限范围内");
        }
    }

    private void replaceRoles(Long userId, List<SysRole> roles) {
        userRoleMapper.delete(Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, userId));
        for (SysRole role : roles) {
            SysUserRole grant = new SysUserRole();
            grant.setUserId(userId);
            grant.setRoleId(role.getRoleId());
            userRoleMapper.insert(grant);
        }
    }

    private List<Long> roleIdsOf(Long userId) {
        return userRoleMapper.selectList(Wrappers.<SysUserRole>lambdaQuery()
                        .eq(SysUserRole::getUserId, userId))
                .stream().map(SysUserRole::getRoleId).toList();
    }

    private SysUser requireUser(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.badRequest("用户不存在");
        }
        return user;
    }

    private void requireUsableOrg(Long orgId) {
        SysOrg org = orgMapper.selectById(orgId);
        if (org == null) {
            throw BizException.badRequest("组织不存在");
        }
        if (Objects.equals(org.getStatus(), 1)) {
            throw BizException.badRequest("组织已停用,不能作为用户归属");
        }
    }

    /**
     * accountLimit 为 null 即不限;只拦新增,不追认存量。
     * 锁 sys_tenant 行再计数:selectCount 与 insert 之间不串行,并发建号能一起看到"还差一个"从而突破上限
     * (与存储配额同一口径);READ_COMMITTED 让拿锁方必见前一笔已提交的用户行。
     */
    private void checkAccountLimit() {
        Long tenantId = TenantContextHolder.get();
        if (tenantId == null) {
            return;
        }
        SysTenant tenant = tenantMapper.selectOne(Wrappers.<SysTenant>lambdaQuery()
                .eq(SysTenant::getTenantId, tenantId)
                .last("FOR UPDATE"));
        if (tenant == null || tenant.getAccountLimit() == null) {
            return;
        }
        long current = userMapper.selectCount(null);
        if (current >= tenant.getAccountLimit()) {
            throw BizException.badRequest("账号数已达租户上限");
        }
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
