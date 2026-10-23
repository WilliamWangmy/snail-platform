package com.snail.platform.function.system.menu.controller;

import com.snail.platform.framework.core.response.R;
import com.snail.platform.framework.log.BusinessType;
import com.snail.platform.framework.log.OperLog;
import com.snail.platform.function.system.menu.dto.MenuCreateRequest;
import com.snail.platform.function.system.menu.dto.MenuNodeVO;
import com.snail.platform.function.system.menu.dto.MenuUpdateRequest;
import com.snail.platform.function.system.menu.service.MenuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:平台菜单目录管理入口——目录树、新增、修改、删除;租户侧不建菜单,只在角色授权里选菜单。
 * 上游:平台菜单管理页面;下游:MenuService。四个端点全挂 platform:menu:* 权限码。
 */
@Tag(name = "菜单管理")
@RestController
@RequestMapping("/system/menu")
public class MenuController {

    /** 菜单目录服务 */
    private final MenuService menuService;

    public MenuController(MenuService menuService) {
        this.menuService = menuService;
    }

    @PreAuthorize("hasAuthority('platform:menu:list')")
    @Operation(summary = "全量菜单目录树")
    @GetMapping("/tree")
    public R<List<MenuNodeVO>> tree() {
        return R.ok(menuService.tree());
    }

    @PreAuthorize("hasAuthority('platform:menu:create')")
    @Operation(summary = "新增菜单节点")
    @OperLog(module = "菜单管理", businessType = BusinessType.INSERT)
    @PostMapping
    public R<Long> create(@Valid @RequestBody MenuCreateRequest request) {
        return R.ok(menuService.create(request));
    }

    @PreAuthorize("hasAuthority('platform:menu:update')")
    @Operation(summary = "修改菜单节点(类型/展示端/归属面/权限码不可改)")
    @OperLog(module = "菜单管理", businessType = BusinessType.UPDATE)
    @PutMapping("/{menuId}")
    public R<Void> update(@PathVariable Long menuId, @Valid @RequestBody MenuUpdateRequest request) {
        menuService.update(menuId, request);
        return R.ok(null);
    }

    @PreAuthorize("hasAuthority('platform:menu:delete')")
    @Operation(summary = "删除菜单节点(内置或存在下级即拒)")
    @OperLog(module = "菜单管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{menuId}")
    public R<Void> delete(@PathVariable Long menuId) {
        menuService.delete(menuId);
        return R.ok(null);
    }
}
