package com.snail.platform.function.system.user.service;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.snail.platform.framework.core.context.AuditActorHolder;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 职责:用户管理——分页检索、新增(含建号即授权)、修改、重置口令、停用/启用、逻辑删除、角色全量覆盖分配。
 * 关键口径:username 全表唯一且建后不可改,查重走无租户窄窗口(全表口径),撞库提示"登录名已存在";
 * 新增受租户 accountLimit 约束(只拦新增不追认存量);删除同事务物理清 sys_user_role——人不在了授权就该空;
 * 自我保护:不允许停用或删除当前登录者自己,防管理员把自己锁在门外;列表查询按当前登录者 data_scope 开窗过滤,
 * 详情与前置校验按 ID 走窗口外——权限只约束"检索面",不拦"已知 ID 的定点读写"。
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
        return UserVO.of(requireUser(userId), roleIdsOf(userId));
    }

    @Transactional
    public Long create(UserCreateRequest request) {
        String username = request.username().trim();
        Long taken = TenantContextHolder.withoutTenant(() -> userMapper.selectCount(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username)));
        if (taken > 0) {
            throw BizException.badRequest("登录名已存在");
        }
        requireUsableOrg(request.orgId());
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
        userMapper.insert(user);

        if (request.roleIds() != null && !request.roleIds().isEmpty()) {
            replaceRoles(user.getUserId(), request.roleIds());
        }
        return user.getUserId();
    }

    @Transactional
    public void update(Long userId, UserUpdateRequest request) {
        SysUser user = requireUser(userId);
        requireUsableOrg(request.orgId());
        userMapper.update(null, Wrappers.<SysUser>lambdaUpdate()
                .set(SysUser::getNickname, request.nickname().trim())
                .set(SysUser::getEmail, request.email())
                .set(SysUser::getPhone, request.phone())
                .set(SysUser::getOrgId, request.orgId())
                .set(SysUser::getRemark, request.remark())
                .eq(SysUser::getUserId, user.getUserId()));
    }

    @Transactional
    public void resetPassword(Long userId, String rawPassword) {
        SysUser user = requireUser(userId);
        userMapper.update(null, Wrappers.<SysUser>lambdaUpdate()
                .set(SysUser::getPassword, BCrypt.hashpw(rawPassword))
                .eq(SysUser::getUserId, user.getUserId()));
    }

    @Transactional
    public void changeStatus(Long userId, int status) {
        SysUser user = requireUser(userId);
        if (status == 1 && Objects.equals(AuditActorHolder.get(), userId)) {
            throw BizException.badRequest("不能停用自己的账号");
        }
        if (Objects.equals(user.getStatus(), status)) {
            return;
        }
        userMapper.update(null, Wrappers.<SysUser>lambdaUpdate()
                .set(SysUser::getStatus, status)
                .eq(SysUser::getUserId, userId));
    }

    /** 逻辑删用户 + 物理清授权同事务:授权关系不是历史 */
    @Transactional
    public void delete(Long userId) {
        requireUser(userId);
        if (Objects.equals(AuditActorHolder.get(), userId)) {
            throw BizException.badRequest("不能删除自己的账号");
        }
        userMapper.deleteById(userId);
        userRoleMapper.delete(Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, userId));
    }

    /** 全量覆盖:先删该用户全部授权再按新集插入,提交集即最终态 */
    @Transactional
    public void assignRoles(Long userId, List<Long> roleIds) {
        requireUser(userId);
        replaceRoles(userId, roleIds);
    }

    private void replaceRoles(Long userId, List<Long> roleIds) {
        List<Long> distinct = roleIds == null ? List.of() : roleIds.stream()
                .filter(Objects::nonNull).distinct().toList();
        if (!distinct.isEmpty()) {
            List<SysRole> roles = roleMapper.selectBatchIds(distinct);
            if (roles.size() != distinct.size()) {
                throw BizException.badRequest("包含不存在或跨租户的角色");
            }
        }
        userRoleMapper.delete(Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, userId));
        for (Long roleId : distinct) {
            SysUserRole grant = new SysUserRole();
            grant.setUserId(userId);
            grant.setRoleId(roleId);
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

    /** accountLimit 为 null 即不限;只拦新增,不追认存量 */
    private void checkAccountLimit() {
        SysTenant tenant = tenantMapper.selectById(TenantContextHolder.get());
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
