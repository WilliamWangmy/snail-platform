package com.snail.platform.function.system.role.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import java.util.Objects;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 职责:角色管理——分页检索、新增、修改、停用/启用、删除。
 * 守护口径:role_code 建后不可改(接口契约里干脆没有它);is_system=1 内置角色不可删;
 * 被 sys_user_role 引用的角色不可删;逻辑删除后 role_code 终身占用不释放(键值不回收口径)。
 * 角色状态/资料变更后 userPerms 与 userSession 整体失效:按角色反查用户不如全清便宜,且不会漏。
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

    public RoleService(SysRoleMapper roleMapper, SysUserRoleMapper userRoleMapper,
                       SysRoleMenuMapper roleMenuMapper) {
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMenuMapper = roleMenuMapper;
    }

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

    @Transactional
    public Long create(RoleCreateRequest request) {
        String roleCode = request.roleCode().trim();
        if (roleMapper.selectCount(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getRoleCode, roleCode)) > 0) {
            throw BizException.badRequest("角色标识已存在");
        }
        SysRole role = new SysRole();
        role.setRoleName(request.roleName().trim());
        role.setRoleCode(roleCode);
        role.setDataScope(request.dataScope());
        role.setIsSystem(0);
        role.setSort(request.sort() == null ? 0 : request.sort());
        role.setStatus(0);
        role.setRemark(request.remark());
        roleMapper.insert(role);
        return role.getRoleId();
    }

    @Transactional
    @CacheEvict(cacheNames = {"userPerms", "userSession"}, allEntries = true)
    public void update(Long roleId, RoleUpdateRequest request) {
        SysRole role = requireRole(roleId);
        roleMapper.update(null, Wrappers.<SysRole>lambdaUpdate()
                .set(SysRole::getRoleName, request.roleName().trim())
                .set(request.dataScope() != null, SysRole::getDataScope, request.dataScope())
                .set(request.sort() != null, SysRole::getSort, request.sort())
                .set(request.status() != null, SysRole::getStatus, request.status())
                .set(SysRole::getRemark, request.remark())
                .eq(SysRole::getRoleId, role.getRoleId()));
    }

    @Transactional
    @CacheEvict(cacheNames = {"userPerms", "userSession"}, allEntries = true)
    public void changeStatus(Long roleId, int status) {
        SysRole role = requireRole(roleId);
        if (Objects.equals(role.getStatus(), status)) {
            return;
        }
        roleMapper.update(null, Wrappers.<SysRole>lambdaUpdate()
                .set(SysRole::getStatus, status)
                .eq(SysRole::getRoleId, roleId));
    }

    @Transactional
    @CacheEvict(cacheNames = {"userPerms", "userSession"}, allEntries = true)
    public void delete(Long roleId) {
        SysRole role = requireRole(roleId);
        if (Objects.equals(role.getIsSystem(), 1)) {
            throw BizException.badRequest("内置角色不可删除");
        }
        if (userRoleMapper.selectCount(Wrappers.<SysUserRole>lambdaQuery()
                .eq(SysUserRole::getRoleId, roleId)) > 0) {
            throw BizException.badRequest("角色下有授权用户,不能删除");
        }
        roleMenuMapper.delete(Wrappers.<SysRoleMenu>lambdaQuery()
                .eq(SysRoleMenu::getRoleId, roleId));
        roleMapper.deleteById(roleId);
    }

    private SysRole requireRole(Long roleId) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw BizException.badRequest("角色不存在");
        }
        return role;
    }
}
