package com.snail.platform.function.system.role.controller;

import com.snail.platform.framework.core.response.R;
import com.snail.platform.framework.log.BusinessType;
import com.snail.platform.framework.log.OperLog;
import com.snail.platform.function.system.role.dto.AssignMenusRequest;
import com.snail.platform.function.system.role.dto.RoleMenuNodeVO;
import com.snail.platform.function.system.role.service.RoleMenuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:角色菜单授权入口——授权树查询与整包提交;平台面菜单的可见与可授由 RoleMenuService 二次判权。
 * 上游:角色管理页授权弹窗;下游:RoleMenuService。写端点打操作日志。
 */
@Tag(name = "角色菜单授权")
@RestController
@RequestMapping("/system/role")
public class RoleMenuController {

    /** 角色授权服务 */
    private final RoleMenuService roleMenuService;

    public RoleMenuController(RoleMenuService roleMenuService) {
        this.roleMenuService = roleMenuService;
    }

    @PreAuthorize("hasAuthority('system:role:list')")
    @Operation(summary = "角色授权树(含 checked;平台面按平台码显隐)")
    @GetMapping("/{roleId}/menus")
    public R<List<RoleMenuNodeVO>> tree(@PathVariable Long roleId) {
        return R.ok(roleMenuService.roleMenuTree(roleId));
    }

    @PreAuthorize("hasAuthority('system:role:assign-menu')")
    @Operation(summary = "整包授权角色菜单(无平台码时保留平台面既有授权)")
    @OperLog(module = "角色管理", businessType = BusinessType.UPDATE)
    @PutMapping("/{roleId}/menus")
    public R<Void> assign(@PathVariable Long roleId, @Valid @RequestBody AssignMenusRequest request) {
        roleMenuService.assign(roleId, request.menuIds());
        return R.ok(null);
    }
}
