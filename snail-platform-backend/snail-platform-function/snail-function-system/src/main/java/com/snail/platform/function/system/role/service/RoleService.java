package com.snail.platform.function.system.role.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.snail.platform.framework.core.context.TenantContextHolder;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.db.PageAssembler;
import com.snail.platform.function.system.menu.entity.SysRoleMenu;
import com.snail.platform.function.system.menu.mapper.SysRoleMenuMapper;
import com.snail.platform.function.system.role.dto.RoleCreateRequest;
import com.snail.platform.function.system.role.dto.RoleQuery;
import com.snail.platform.function.system.role.dto.RoleUpdateRequest;
import com.snail.platform.function.system.role.dto.RoleVO;
import com.snail.platform.function.system.role.entity.SysRole;
import com.snail.platform.function.system.role.entity.SysUserRole;
import com.snail.platform.function.system.role.mapper.SysRoleMapper;
import com.snail.platform.function.system.role.mapper.SysUserRoleMapper;
import com.snail.platform.function.system.support.CacheEvictor;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 职责:角色管理——分页检索、新增、修改、停用/启用、删除。
 * 守护口径:role_code 建后不可改(接口契约里干脆没有它);is_system=1 内置角色对管理端只读,
 * 修改/停用/删除一律拒绝;被 sys_user_role 引用的角色不可删;逻辑删除后 role_code 终身占用不释放。
 * 数据档位:新增或维护后的档位不得宽于操作者自身最宽档,防"窄档管理员"造出宽档角色。
 * 角色资料/状态变更只点该角色的持有人:快照里的角色名与权限码只对这批复盘,别的人与别的租户不受牵连。
 * 上游:RoleController;下游:sys_role 读写(拦截器圈定本租户)、sys_user_role 引用计数、sys_role_menu 随删清理。
 */
@Service
public class RoleService {

    /** 角色表读写 */
    private final SysRoleMapper roleMapper;

    /** 授权关系读,删除前置检查用 */
    private final SysUserRoleMapper userRoleMapper;

    /** 角色-菜单授权,删角色时同事务清行 */
    private final SysRoleMenuMapper roleMenuMapper;

    /** 操作者最宽数据档位判定 */
    private final DataScopeService dataScopeService;

    /** 持有人权限码与快照缓存的点清出口 */
    private final CacheEvictor cacheEvictor;

    public RoleService(SysRoleMapper roleMapper, SysUserRoleMapper userRoleMapper,
                       SysRoleMenuMapper roleMenuMapper, DataScopeService dataScopeService,
                       CacheEvictor cacheEvictor) {
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMenuMapper = roleMenuMapper;
        this.dataScopeService = dataScopeService;
        this.cacheEvictor = cacheEvictor;
    }

    /**
     * 分页检索:keyword 一名或一码命中即算(括号成组,免得 or 溢出到 status 条件上)。
     * 角色查询刻意不开 data_scope 窗口:谓词要打在 org_id 上,而 sys_role 没有这一列,开窗等于拼出坏 SQL;
     * 角色本就是租户级配置,create_by 只当审计列不做归属过滤,可见性全交给 system:role:* 权限码管。
     */
    public PageResult<RoleVO> page(RoleQuery query) {
        Page<SysRole> page = PageAssembler.from(query);
        String keyword = query.keyword();
        IPage<SysRole> result = roleMapper.selectPage(page, Wrappers.<SysRole>lambdaQuery()
                .and(keyword != null && !keyword.isBlank(),
                        w -> w.like(SysRole::getRoleName, keyword).or().like(SysRole::getRoleCode, keyword))
                .eq(query.status() != null, SysRole::getStatus, query.status())
                .orderByAsc(SysRole::getSort).orderByAsc(SysRole::getRoleId));
        return PageResult.of(result.getRecords().stream().map(RoleVO::from).toList(), result.getTotal());
    }

    /**
     * 新增:查重不写 tenant_id 条件——租户拦截器已把 selectCount 圈在本租户,作用域正好对齐唯一键
     * (tenant_id, role_code);用户登录名那条是全表唯一,别照抄这边的写法。
     * is_system 恒为 0——内置角色只有种子与租户开通模板能造,接口连这个字段都收不到;
     * status 一律建为正常,档位先过 requireGrantableDataScope 再落库。
     */
    @Transactional
    public Long create(RoleCreateRequest request) {
        String roleCode = request.roleCode().trim();
        if (roleMapper.selectCount(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getRoleCode, roleCode)) > 0) {
            throw BizException.badRequest("角色标识已存在");
        }
        requireGrantableDataScope(request.dataScope());
        SysRole role = new SysRole();
        role.setRoleName(request.roleName().trim());
        role.setRoleCode(roleCode);
        role.setDataScope(request.dataScope());
        role.setIsSystem(0);
        role.setSort(request.sort() == null ? 0 : request.sort());
        role.setStatus(0);
        role.setRemark(request.remark());
        try {
            roleMapper.insert(role);
        } catch (DuplicateKeyException e) {
            // 唯一键不含 deleted:已删角色仍终身占用 role_code,同码重开会被数据库拦下;口径统一走业务拒绝
            throw BizException.badRequest("角色标识已存在");
        }
        return role.getRoleId();
    }

    /**
     * 修改:可改名称/档位/排序/状态/备注,role_code 不在契约里(建后不可改由接口收不到保证)。
     * 档位闸用的是"提交值,没提交就用角色现值",所以档位比你宽的角色,你连只改名字都改不动——
     * 否则改名就成了窄档管理员接管宽档角色的侧门。内置角色在这之前已被 requireBuiltinReadOnly 挡下。
     */
    @Transactional
    public void update(Long roleId, RoleUpdateRequest request) {
        SysRole role = requireRole(roleId);
        requireBuiltinReadOnly(role);
        requireGrantableDataScope(request.dataScope() != null ? request.dataScope() : role.getDataScope());
        roleMapper.update(new SysRole(), Wrappers.<SysRole>lambdaUpdate()
                .set(SysRole::getRoleName, request.roleName().trim())
                .set(request.dataScope() != null, SysRole::getDataScope, request.dataScope())
                .set(request.sort() != null, SysRole::getSort, request.sort())
                .set(request.status() != null, SysRole::getStatus, request.status())
                .set(SysRole::getRemark, request.remark())
                .eq(SysRole::getRoleId, role.getRoleId()));
        evictHolders(roleId);
    }

    /**
     * 停用/启用:与 update 同一把档位尺,比你宽的角色的状态开关你也按不动。
     * 同状态提前返回只省掉那次写,也省掉一次点清——状态没变,持有人的权限码与快照就不变。
     * 停用让该角色的权限码在下次鉴权时整体消失(见 UserPermissionService 对停用角色的剔除)。
     */
    @Transactional
    public void changeStatus(Long roleId, int status) {
        SysRole role = requireRole(roleId);
        requireBuiltinReadOnly(role);
        requireGrantableDataScope(role.getDataScope());
        if (Objects.equals(role.getStatus(), status)) {
            return;
        }
        roleMapper.update(new SysRole(), Wrappers.<SysRole>lambdaUpdate()
                .set(SysRole::getStatus, status)
                .eq(SysRole::getRoleId, roleId));
        evictHolders(roleId);
    }

    /**
     * 删除:内置拒、档位宽于自己拒(requireGrantableDataScope 按角色现档位判,与 update/changeStatus 同口径)、
     * 还有人挂着拒,三道都过才动手。
     * sys_role_menu 是同事务物理删——纯关系表,角色没了授权行就是垃圾,不留"曾经授过"的历史。
     * 逻辑删后 (tenant_id, role_code) 唯一键仍占位,同码重开会被拒,见 create 的兜底分支。
     */
    @Transactional
    public void delete(Long roleId) {
        SysRole role = requireRole(roleId);
        if (Objects.equals(role.getIsSystem(), 1)) {
            throw BizException.badRequest("内置角色不可删除");
        }
        requireGrantableDataScope(role.getDataScope());
        if (userRoleMapper.selectCount(Wrappers.<SysUserRole>lambdaQuery()
                .eq(SysUserRole::getRoleId, roleId)) > 0) {
            throw BizException.badRequest("角色下有授权用户,不能删除");
        }
        roleMenuMapper.delete(Wrappers.<SysRoleMenu>lambdaQuery()
                .eq(SysRoleMenu::getRoleId, roleId));
        roleMapper.deleteById(roleId);
        // 不点缓存:上面第三道闸已确认没有任何持有人,没人快照/权限码引用了这个角色
    }

    /**
     * 只点这个角色的持有人:权限码键是 userId,会话快照键是 租户:userId。
     * 角色名、档位、状态都进了快照和权限码,所以两者都要清;同租户的其他人与他租户一概不碰。
     */
    private void evictHolders(Long roleId) {
        List<Long> holders = userRoleMapper.selectList(Wrappers.<SysUserRole>lambdaQuery()
                        .eq(SysUserRole::getRoleId, roleId))
                .stream().map(SysUserRole::getUserId).distinct().toList();
        cacheEvictor.evictEach("userPerms", holders);
        cacheEvictor.evictEach("userSession", holders.stream()
                .map(id -> CacheEvictor.tenantKey(TenantContextHolder.get(), id)).toList());
    }

    /** 内置角色由开通模板维护,管理端只读;放开改档/停用会让租户把自己锁在门外 */
    private void requireBuiltinReadOnly(SysRole role) {
        if (Objects.equals(role.getIsSystem(), 1)) {
            throw BizException.badRequest("内置角色不可修改");
        }
    }

    /** 角色档位不得宽于操作者自身最宽档;无可用启用角色一律拒绝配置 */
    private void requireGrantableDataScope(Integer dataScope) {
        if (dataScope == null) {
            return;
        }
        Integer broadest = dataScopeService.operatorBroadestDataScope();
        if (broadest == null) {
            throw BizException.forbidden("当前登录人没有可用角色,不能配置数据权限");
        }
        if (dataScope < broadest) {
            throw BizException.forbidden("不能配置数据档位宽于自身权限的角色");
        }
    }

    /** 存在性闸:租户窗口内查不到即拒(他租户角色与本租户不存在的 ID 同形,不额外区分) */
    private SysRole requireRole(Long roleId) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw BizException.badRequest("角色不存在");
        }
        return role;
    }
}
