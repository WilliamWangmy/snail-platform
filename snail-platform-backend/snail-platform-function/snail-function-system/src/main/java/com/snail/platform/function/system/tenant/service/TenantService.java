package com.snail.platform.function.system.tenant.service;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.snail.platform.framework.core.context.TenantContextHolder;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.db.PageAssembler;
import com.snail.platform.function.system.org.entity.SysOrg;
import com.snail.platform.function.system.org.mapper.SysOrgMapper;
import com.snail.platform.function.system.role.entity.SysRole;
import com.snail.platform.function.system.role.entity.SysUserRole;
import com.snail.platform.function.system.role.mapper.SysRoleMapper;
import com.snail.platform.function.system.role.mapper.SysUserRoleMapper;
import com.snail.platform.function.system.tenant.dto.TenantCreateRequest;
import com.snail.platform.function.system.tenant.dto.TenantQuery;
import com.snail.platform.function.system.tenant.dto.TenantUpdateRequest;
import com.snail.platform.function.system.tenant.dto.TenantVO;
import com.snail.platform.function.system.tenant.entity.SysTenant;
import com.snail.platform.function.system.tenant.mapper.SysTenantMapper;
import com.snail.platform.function.system.user.entity.SysUser;
import com.snail.platform.function.system.user.mapper.SysUserMapper;
import com.snail.platform.function.system.user.service.PasswordPolicy;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 职责:租户全生命周期——分页检索、开通(模板复制)、资料修改、停用/启用。
 * sys_tenant 在拦截器 ignore 清单,本服务天然跨租户;开通是新租户数据的写入点,
 * 模板复制段用 TenantContextHolder.asTenant 把身份切到新租户,让拦截器把新租户写对。
 * actor 口径(整改线 Phase 5 定版):asTenant 只切租户不切操作者,新租户根组织与首任管理员的 create_by
 * 是执行开通的平台管理员——留痕指向开通人比指向"虚构的自创建"更有价值;首任管理员复制的是内置超管模板
 * (data_scope=1 全部档),可见集按整租户算,不因该归属而看不到自己的组织与账号。
 * 销户(deleted)是平台侧运维动作,本服务不提供入口。
 * 上游:TenantController;下游:sys_tenant 写读 + 开通事务内的 sys_org/sys_role/sys_user/sys_user_role。
 */
@Service
public class TenantService {

    /** 租户表读写,ignore 表 */
    private final SysTenantMapper tenantMapper;

    /** 开通模板:根组织 */
    private final SysOrgMapper orgMapper;

    /** 开通模板:内置角色 */
    private final SysRoleMapper roleMapper;

    /** 开通模板:管理员账号 */
    private final SysUserMapper userMapper;

    /** 开通模板:管理员-角色绑定 */
    private final SysUserRoleMapper userRoleMapper;

    public TenantService(SysTenantMapper tenantMapper, SysOrgMapper orgMapper, SysRoleMapper roleMapper,
                         SysUserMapper userMapper, SysUserRoleMapper userRoleMapper) {
        this.tenantMapper = tenantMapper;
        this.orgMapper = orgMapper;
        this.roleMapper = roleMapper;
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
    }

    /**
     * 分页检索:sys_tenant 在拦截器 ignore 清单,本查询天然覆盖全平台租户,无需额外摘条件。
     * 按 tenantId 倒序,新开通的租户排在最前。
     * name 判空要连 blank 一起收:管理页清空输入框传的是空串而非 null,放过它就拼出 LIKE '%%' 全表命中。
     */
    public PageResult<TenantVO> page(TenantQuery query) {
        Page<SysTenant> page = PageAssembler.from(query);
        IPage<SysTenant> result = tenantMapper.selectPage(page, Wrappers.<SysTenant>lambdaQuery()
                .like(query.name() != null && !query.name().isBlank(), SysTenant::getName, query.name())
                .eq(query.status() != null, SysTenant::getStatus, query.status())
                .orderByDesc(SysTenant::getTenantId));
        return PageResult.of(result.getRecords().stream().map(TenantVO::from).toList(), result.getTotal());
    }

    /**
     * 开通租户:同事务落租户行,再以新租户身份复制 V4 种子模板(根组织 + admin/common 内置角色 + 管理员并绑定)。
     * 租户行必须先 insert 拿到自增 tenantId,才能进 asTenant 窗口——后续每条 insert 的 tenant_id 由拦截器从这个上下文里取。
     * 管理员登录名先在全局口径下查重——username 全表唯一是"登录不选租户"的代价。
     * 模板不复制 sys_role_menu:新租户开箱没有任何角色授权记录,见 insertBuiltinRole 的口径。
     */
    @Transactional
    public Long provision(TenantCreateRequest request) {
        String tenantCode = request.tenantCode().trim();
        String adminUsername = request.adminUsername().trim();
        PasswordPolicy.validate(request.adminPassword());

        if (tenantMapper.selectCount(Wrappers.<SysTenant>lambdaQuery()
                .eq(SysTenant::getTenantCode, tenantCode)) > 0) {
            throw BizException.badRequest("租户标识已存在");
        }
        // sys_user 是租户管理表,不摘条件的话 selectCount 只圈在当前平台管理员自己的租户里,
        // 别的租户已占用的登录名看不见;username 的唯一键是全表口径,查重必须同口径
        Long usernameTaken = TenantContextHolder.withoutTenant(() -> userMapper.selectCount(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, adminUsername)));
        if (usernameTaken > 0) {
            throw BizException.badRequest("管理员登录名已存在");
        }

        SysTenant tenant = new SysTenant();
        tenant.setTenantCode(tenantCode);
        tenant.setName(request.name().trim());
        tenant.setContactUser(request.contactUser());
        tenant.setContactPhone(request.contactPhone());
        tenant.setExpireTime(request.expireTime());
        tenant.setAccountLimit(request.accountLimit());
        tenant.setStatus(0);
        tenant.setRemark(request.remark());
        try {
            tenantMapper.insert(tenant);
        } catch (DuplicateKeyException e) {
            // 唯一键不含 deleted:已销户租户仍终身占用 tenant_code,同码重开会被数据库拦下;口径统一走业务拒绝
            throw BizException.badRequest("租户标识已存在");
        }

        Long newTenantId = tenant.getTenantId();
        // 窗口内每条 insert 由拦截器补 tenant_id,org_id/role_id/user_id 的回填才落得对新租户
        TenantContextHolder.asTenant(newTenantId, () -> {
            // 根组织名直接沿用租户名:新租户还没有任何组织结构,根即租户本身;parentId=0 与 ancestors="0" 是顶级节点的两个标记,与组织域同口径
            SysOrg root = new SysOrg();
            root.setParentId(0L);
            root.setName(tenant.getName());
            root.setAncestors("0");
            root.setSort(0);
            root.setStatus(0);
            orgMapper.insert(root);

            SysRole adminRole = insertBuiltinRole("超级管理员", "admin", 1, 1);
            insertBuiltinRole("普通角色", "common", 4, 2);

            SysUser admin = new SysUser();
            admin.setUsername(adminUsername);
            admin.setNickname("租户管理员");
            admin.setPassword(BCrypt.hashpw(request.adminPassword()));
            admin.setOrgId(root.getOrgId());
            admin.setStatus(0);
            userMapper.insert(admin);

            SysUserRole binding = new SysUserRole();
            binding.setUserId(admin.getUserId());
            binding.setRoleId(adminRole.getRoleId());
            userRoleMapper.insert(binding);
            return null;
        });
        return newTenantId;
    }

    /**
     * 修改资料:整份覆盖。六个业务字段无条件 set,表单留空即清空;status 是唯一"不传即不动"的字段,
     * 停用/启用的正路仍是 changeStatus 端点(带幂等短路)。
     * userSession 以用户 ID 为键,租户维度没有可点清的那一点,注解只能 allEntries 全清(每次修改都清,
     * 与下面是否作废令牌是两件事);令牌前推只在"正常转停用"或"到期时间变更"时做,改名字备注不摘登录态。
     */
    @Transactional
    @CacheEvict(cacheNames = "userSession", allEntries = true)
    public void update(Long tenantId, TenantUpdateRequest request) {
        SysTenant existing = requireTenant(tenantId);
        // disabling 判的是"变更进入停用态"而非"目标是停用":整表单重复提交同一个停用状态不再推一遍版本号
        boolean disabling = Integer.valueOf(1).equals(request.status())
                && !Integer.valueOf(1).equals(existing.getStatus());
        // 到期时间一变就推版本号:往回收口时,已登录的存量令牌要当场作废,不能等它自然过期
        boolean expireChanged = !Objects.equals(existing.getExpireTime(), request.expireTime());
        // 收口只能收到未来:把到期时间写成过去,等于绕开停用端点做一次无留痕的软下线。
        // 判的是"变更",存量已过期的租户原样回传现值不算改,不然它连备注都改不动
        if (expireChanged && request.expireTime() != null
                && request.expireTime().isBefore(LocalDateTime.now())) {
            throw BizException.badRequest("到期时间必须晚于当前时间");
        }
        // updateById 会跳过 null,而"清空到期时间/账号上限"恰是合法意图,故逐字段显式 set
        tenantMapper.update(new SysTenant(), Wrappers.<SysTenant>lambdaUpdate()
                .set(SysTenant::getName, request.name().trim())
                .set(SysTenant::getContactUser, request.contactUser())
                .set(SysTenant::getContactPhone, request.contactPhone())
                .set(SysTenant::getExpireTime, request.expireTime())
                .set(SysTenant::getAccountLimit, request.accountLimit())
                .set(SysTenant::getRemark, request.remark())
                .set(request.status() != null, SysTenant::getStatus, request.status())
                .eq(SysTenant::getTenantId, tenantId));
        if (disabling || expireChanged) {
            invalidateTenantSessions(tenantId);
        }
    }

    /**
     * 停用/启用:状态未变直接返回,幂等短路省掉一次写,但方法上的 @CacheEvict 在正常返回时同样执行,
     * 那次短路请求仍会把 userSession 整体清掉。
     * 只有停用推 token_version;启用不回填,停用期间被作废的令牌不会因为重新启用而复活,该租户用户需重新登录。
     */
    @Transactional
    @CacheEvict(cacheNames = "userSession", allEntries = true)
    public void changeStatus(Long tenantId, int status) {
        SysTenant tenant = requireTenant(tenantId);
        if (Objects.equals(tenant.getStatus(), status)) {
            return;
        }
        tenantMapper.update(new SysTenant(), Wrappers.<SysTenant>lambdaUpdate()
                .set(SysTenant::getStatus, status)
                .eq(SysTenant::getTenantId, tenantId));
        if (status == 1) {
            invalidateTenantSessions(tenantId);
        }
    }

    /**
     * 租户级会话失效:停用/到期变更时把该租户全部用户的令牌版本前推,旧令牌即使缓存未及时失效也过不了版本比对。
     * 用 asTenant 把拦截器圈到目标租户,平台管理员自身的租户上下文在窗口结束后恢复;
     * 语句里的显式 eq 与拦截器注入的是同一个条件,两处都写是让"只动这一户"在眼前就可确认。
     */
    private void invalidateTenantSessions(Long tenantId) {
        TenantContextHolder.asTenant(tenantId, () -> userMapper.update(new SysUser(),
                Wrappers.<SysUser>lambdaUpdate()
                        .setSql("token_version = token_version + 1")
                        .eq(SysUser::getTenantId, tenantId)));
    }

    private SysTenant requireTenant(Long tenantId) {
        SysTenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw BizException.badRequest("租户不存在");
        }
        return tenant;
    }

    /**
     * 复制内置角色,取值与 V4 种子逐列对齐(admin data_scope=1/sort=1,common data_scope=4/sort=2)。
     * is_system 恒为 1:内置角色只有种子与本处能造,RoleService 的写接口连这个字段都收不到。
     * 只落角色行、不配 sys_role_menu——新租户管理员靠语义 A(admin 且 is_system=1)隐式持有全部 scope=2 权限,
     * 平台面(scope=1)菜单本就不归租户管理员;common 能看什么,由新租户管理员自己勾。
     */
    private SysRole insertBuiltinRole(String roleName, String roleCode, int dataScope, int sort) {
        SysRole role = new SysRole();
        role.setRoleName(roleName);
        role.setRoleCode(roleCode);
        role.setDataScope(dataScope);
        role.setIsSystem(1);
        role.setSort(sort);
        role.setStatus(0);
        roleMapper.insert(role);
        return role;
    }
}
