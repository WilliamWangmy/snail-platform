package com.snail.platform.function.system.role.controller;

import com.snail.platform.framework.core.response.R;
import com.snail.platform.framework.log.BusinessType;
import com.snail.platform.framework.log.OperLog;
import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.function.system.role.dto.RoleCreateRequest;
import com.snail.platform.function.system.role.dto.RoleQuery;
import com.snail.platform.function.system.role.dto.RoleUpdateRequest;
import com.snail.platform.function.system.role.dto.RoleVO;
import com.snail.platform.function.system.role.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:角色管理入口——分页、新增、修改、停用/启用、删除;作用域为本租户(拦截器圈定)。
 * 上游:角色管理页面;下游:RoleService。修改接口收不到 role_code,建后不可改由契约保证。
 */
@Tag(name = "角色管理")
@Validated
@RestController
@RequestMapping("/system/role")
public class RoleController {

    /** 角色业务服务 */
    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @PreAuthorize("hasAuthority('system:role:list')")
    @Operation(summary = "角色分页列表")
    @GetMapping("/page")
    public R<PageResult<RoleVO>> page(@Valid RoleQuery query) {
        return R.ok(roleService.page(query));
    }

    @PreAuthorize("hasAuthority('system:role:create')")
    @Operation(summary = "新增角色")
    @OperLog(module = "角色管理", businessType = BusinessType.INSERT)
    @PostMapping
    public R<Long> create(@Valid @RequestBody RoleCreateRequest request) {
        return R.ok(roleService.create(request));
    }

    @PreAuthorize("hasAuthority('system:role:update')")
    @Operation(summary = "修改角色")
    @OperLog(module = "角色管理", businessType = BusinessType.UPDATE)
    @PutMapping("/{roleId}")
    public R<Void> update(@PathVariable Long roleId, @Valid @RequestBody RoleUpdateRequest request) {
        roleService.update(roleId, request);
        return R.ok(null);
    }

    @PreAuthorize("hasAuthority('system:role:update')")
    @Operation(summary = "停用/启用角色")
    @OperLog(module = "角色管理", businessType = BusinessType.UPDATE)
    @PutMapping("/{roleId}/status")
    public R<Void> changeStatus(@PathVariable Long roleId,
                                       @RequestParam @Min(value = 0, message = "状态取值 0/1")
                                       @Max(value = 1, message = "状态取值 0/1") int status) {
        roleService.changeStatus(roleId, status);
        return R.ok(null);
    }

    @PreAuthorize("hasAuthority('system:role:delete')")
    @Operation(summary = "删除角色(内置或被授权引用即拒)")
    @OperLog(module = "角色管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{roleId}")
    public R<Void> delete(@PathVariable Long roleId) {
        roleService.delete(roleId);
        return R.ok(null);
    }
}
