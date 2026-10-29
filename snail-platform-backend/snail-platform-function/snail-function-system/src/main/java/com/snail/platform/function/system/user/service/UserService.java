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
import com.snail.platform.function.system.role.service.UserPermissionService;
import com.snail.platform.function.system.support.CacheEvictor;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 职责:用户管理——分页检索、新增(含建号即授权)、修改、重置口令、停用/启用、逻辑删除、角色全量覆盖分配。
 * 关键口径:username 全表唯一且建后不可改,查重走无租户窄窗口(全表口径),撞库提示"登录名已存在";
 * 新增受租户 accountLimit 约束(锁 sys_tenant 行后计数,只拦新增不追认存量);
 * 删除同事务物理清 sys_user_role——人不在了授权就该空;
 * 自我保护:不允许停用或删除当前登录者自己,防管理员把自己锁在门外;
 * 也不允许停用或删除本租户最后一个启用的管理员——个人自锁只是自己进不来,整租户失去管理员就没人能授权、能把人加回来。
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

    /** 缓存点清出口:只在写真的发生之后、事务提交之后清 */
    private final CacheEvictor cacheEvictor;

    public UserService(SysUserMapper userMapper, SysOrgMapper orgMapper, SysRoleMapper roleMapper,
                       SysUserRoleMapper userRoleMapper, SysTenantMapper tenantMapper,
                       DataScopeService dataScopeService, CacheEvictor cacheEvictor) {
        this.userMapper = userMapper;
        this.orgMapper = orgMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.tenantMapper = tenantMapper;
        this.dataScopeService = dataScopeService;
        this.cacheEvictor = cacheEvictor;
    }

    /**
     * 分页检索:四个筛选条件都是"没传即不限",按 userId 倒序让新建的在前。
     * 列表投影的 roleIds 恒传 null——授权集逐行回查就是 N+1,要看在详情里看。
     */
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

    /** 详情比列表多带授权角色集:存在闸在前、可见闸在后,与写入口同序;回查后要拿到最终态 */
    public UserVO detail(Long userId) {
        SysUser user = requireUser(userId);
        requireUserInScope(userId);
        return UserVO.of(user, roleIdsOf(userId));
    }

    /**
     * 建号即授权:口令与角色的校验都排在写库之前,失败不留半截用户行。
     * 查重刻意走无租户窄窗口——登录名全表唯一(登录不选租户的代价),本租户查不出他租的占用。
     * 两道组织闸分工不同:requireUsableOrg 管"这个组织还在不在、开不开",
     * requireAssignableOrg 管"操作者有没有资格把人挂进去";后者只对组织档及以下有意义。
     * 明文口令只走到 BCrypt 这一行,之后任何地方都不再可逆;status 一律建为正常。
     */
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

    /**
     * 改档案:五列点改,username/password/status 不在射程——口令走重置、状态走 changeStatus,各有独立权限码。
     * 调岗同样过两道组织闸,与建号同口径。
     * 失效只按该用户一点清 userSession:登录快照里带着 nickname 与 orgName,不改这两样前端就会显示旧值;
     * 前推 token_version 与清 userPerms 都不必——资料变更既不影响会话资格,也不影响授权集。
     */
    @Transactional
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
        cacheEvictor.evict("userSession", sessionKey(userId));
    }

    /**
     * 管理员重置口令:与自助改密的差别只在"不验原密码",强度口径仍由 PasswordPolicy 单点把。
     * 前推 token_version 是刻意的——重置等于把该用户全部存量令牌一次作废,新口令只在新登录里生效。
     * 不清 userPerms:重置口令不动授权。
     */
    @Transactional
    public void resetPassword(Long userId, String rawPassword) {
        SysUser user = requireUser(userId);
        requireUserInScope(userId);
        PasswordPolicy.validate(rawPassword);
        userMapper.update(new SysUser(), Wrappers.<SysUser>lambdaUpdate()
                .set(SysUser::getPassword, BCrypt.hashpw(rawPassword))
                .set(SysUser::getPasswordModifyTime, null)
                .setSql("token_version = token_version + 1")
                .eq(SysUser::getUserId, user.getUserId()));
        cacheEvictor.evict("userSession", sessionKey(userId));
    }

    /** 强制下线:令牌版本自增 + 快照失效,该用户存量令牌下一次请求即 401;不判自我,由调用方决定 */
    @Transactional
    public void forceLogout(Long userId) {
        requireUser(userId);
        requireUserInScope(userId);
        userMapper.update(new SysUser(), Wrappers.<SysUser>lambdaUpdate()
                .setSql("token_version = token_version + 1")
                .eq(SysUser::getUserId, userId));
        cacheEvictor.evict("userSession", sessionKey(userId));
    }

    /**
     * 停用/启用:状态翻转必然前推令牌版本,停用即刻切断存量会话,不等令牌自然过期。
     * 自我停用只能在这里显式拦:本人恒在可见集内( requireUserInScope 对自己放行),权限闸挡不住"停自己"。
     * 同状态提前返回,省掉一次无谓的写与令牌前推——否则重复点同一个状态会白踢人下线;
     * 它刻意排在可见闸之后,不可见的目标不能被"本来就是这状态"顺带放过。
     * 最后一个启用管理员的判断排在短路之后:已经是停用态再点停用,既无写入也无损失,不该被守卫拦成报错。
     * userPerms 与 delete/assignRoles 同面清掉:状态翻转后的鉴权一律重读授权集,不靠上一次的缓存。
     */
    @Transactional
    public void changeStatus(Long userId, int status) {
        SysUser user = requireUser(userId);
        if (status == 1 && Objects.equals(LoginUserContextHolder.userId(), userId)) {
            throw BizException.badRequest("不能停用自己的账号");
        }
        requireUserInScope(userId);
        if (Objects.equals(user.getStatus(), status)) {
            return;
        }
        if (status == 1) {
            requireNotLastEnabledAdmin(user, "停用");
        }
        userMapper.update(new SysUser(), Wrappers.<SysUser>lambdaUpdate()
                .set(SysUser::getStatus, status)
                .setSql("token_version = token_version + 1")
                .eq(SysUser::getUserId, userId));
        cacheEvictor.evict("userPerms", userId);
        cacheEvictor.evict("userSession", sessionKey(userId));
    }

    /**
     * 逻辑删用户 + 物理清授权同事务:授权关系不是历史。
     * 自己删自己与"删到只剩零个管理员"都在写之前拦:删完再校验没有回滚以外的退路。
     */
    @Transactional
    public void delete(Long userId) {
        SysUser user = requireUser(userId);
        if (Objects.equals(LoginUserContextHolder.userId(), userId)) {
            throw BizException.badRequest("不能删除自己的账号");
        }
        requireUserInScope(userId);
        requireNotLastEnabledAdmin(user, "删除");
        // 逻辑删后旧令牌仍可能落在其他节点缓存里,版本前推让最小快照之外的残留也失去会话资格
        userMapper.update(new SysUser(), Wrappers.<SysUser>lambdaUpdate()
                .setSql("token_version = token_version + 1")
                .eq(SysUser::getUserId, userId));
        userMapper.deleteById(userId);
        userRoleMapper.delete(Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, userId));
        cacheEvictor.evict("userPerms", userId);
        cacheEvictor.evict("userSession", sessionKey(userId));
    }

    /** 全量覆盖:先删该用户全部授权再按新集插入,提交集即最终态 */
    @Transactional
    public void assignRoles(Long userId, List<Long> roleIds) {
        requireUser(userId);
        requireUserInScope(userId);
        replaceRoles(userId, resolveGrantable(roleIds));
        cacheEvictor.evict("userPerms", userId);
        cacheEvictor.evict("userSession", sessionKey(userId));
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

    /**
     * 最后一个启用的管理员不许删/停:丢掉的不是一个账号,而是整租户的管理入口——再没人能分配角色,
     * 也没人能把人加回来,只能改库。
     * 判定沿用语义 A 那把尺(isSuperAdmin)加"本人启用";别人的启用状态每次现查,不信缓存。
     * 数之前先锁租户行(与 checkAccountLimit 同一把锁):两个管理员同时互相停用时,不锁会各自数到
     * "还有一个"而双双放行,结果整租户零管理员。
     */
    private void requireNotLastEnabledAdmin(SysUser target, String action) {
        if (!Integer.valueOf(0).equals(target.getStatus())) {
            return;
        }
        // 先只看目标自己的角色:绝大多数删号/停号与管理员无关,不必为此把整张角色表读一遍
        List<Long> targetRoleIds = roleIdsOf(target.getUserId());
        if (targetRoleIds.isEmpty()) {
            return;
        }
        List<Long> adminRoleIds = roleMapper.selectBatchIds(targetRoleIds).stream()
                .filter(UserPermissionService::isSuperAdmin)
                .map(SysRole::getRoleId)
                .toList();
        if (adminRoleIds.isEmpty()) {
            return;
        }
        Long tenantId = TenantContextHolder.get();
        if (tenantId != null) {
            tenantMapper.selectOne(Wrappers.<SysTenant>lambdaQuery()
                    .eq(SysTenant::getTenantId, tenantId)
                    .last("FOR UPDATE"));
        }
        List<Long> others = userRoleMapper.selectList(Wrappers.<SysUserRole>lambdaQuery()
                        .in(SysUserRole::getRoleId, adminRoleIds))
                .stream().map(SysUserRole::getUserId)
                .filter(id -> !id.equals(target.getUserId()))
                .distinct()
                .toList();
        if (others.isEmpty() || userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .in(SysUser::getUserId, others)
                .eq(SysUser::getStatus, 0)) == 0) {
            throw BizException.badRequest("不能" + action + "最后一个启用的管理员");
        }
    }

    /** 建号/调岗的组织闸:组织档操作者只能把人挂进自己可见的组织 */
    private void requireAssignableOrg(Long orgId) {
        if (!dataScopeService.canAssignOrg(orgId)) {
            throw BizException.forbidden("目标组织不在当前数据权限范围内");
        }
    }

    /**
     * 授权全量覆盖:先删干净再逐行插,单事务里完成,不会留下"删了没插"的空档。
     * 逐行 insert 而非批量:一个用户的角色是个位数,BaseMapper 也没有批量入口,不值得为此多开一层。
     * 入参必须已过 resolveGrantable,这里不再判租户与档位。
     */
    private void replaceRoles(Long userId, List<SysRole> roles) {
        userRoleMapper.delete(Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, userId));
        for (SysRole role : roles) {
            SysUserRole grant = new SysUserRole();
            grant.setUserId(userId);
            grant.setRoleId(role.getRoleId());
            userRoleMapper.insert(grant);
        }
    }

    /** 只回查角色 ID 集(UserVO 的 roleIds),名称由前端按角色字典映射,免得为展示再联表 */
    private List<Long> roleIdsOf(Long userId) {
        return userRoleMapper.selectList(Wrappers.<SysUserRole>lambdaQuery()
                        .eq(SysUserRole::getUserId, userId))
                .stream().map(SysUserRole::getRoleId).toList();
    }

    /** 存在性闸:每个定点入口的第一道,不存在即业务拒绝(跨租户的行本就查不到) */
    private SysUser requireUser(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.badRequest("用户不存在");
        }
        return user;
    }

    /** 归属闸:组织要存在且启用;他租户的组织在租户窗口里查不到,统一按"组织不存在"拒 */
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

    /** 快照键:(租户, 用户),与 UserSessionService 的 @Cacheable 同拼法;租户取自请求上下文,即目标用户所在租户 */
    private static String sessionKey(Long userId) {
        return CacheEvictor.tenantKey(TenantContextHolder.get(), userId);
    }

    /** 空白与 null 同义,都不参与拼句——否则一个空格会被当成真实的 like 条件 */
    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
