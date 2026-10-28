package com.snail.platform.function.system.role.controller;

import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.core.response.R;
import com.snail.platform.framework.security.token.SnailPrincipal;
import com.snail.platform.function.system.role.dto.MenuRouteVO;
import com.snail.platform.function.system.role.service.RoleMenuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:当前用户菜单树入口——按展示端取本人在角色授权内的目录与菜单,供侧栏与路由表消费。
 * 住在 role 子域:菜单子域不得反向依赖角色授权;URL 仍归 /system/menu 语义面。
 * 上游:admin 与 dashboard 的启动引导;下游:RoleMenuService.currentRoutes。
 */
@Tag(name = "当前用户菜单")
@Validated
@RestController
@RequestMapping("/system/menu")
public class MenuRouteController {

    /** 角色授权服务 */
    private final RoleMenuService roleMenuService;

    public MenuRouteController(RoleMenuService roleMenuService) {
        this.roleMenuService = roleMenuService;
    }

    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "当前用户菜单树(target=1 站内含外链,target=2 数据大屏)")
    @GetMapping("/current")
    public R<List<MenuRouteVO>> current(
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "展示端取值 1/2")
            @Max(value = 2, message = "展示端取值 1/2") int target) {
        // userId 只从登录主体取,不做请求参数:换一个人的 ID 就看到一个人的导航,这个口子不能留
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof SnailPrincipal principal)) {
            throw BizException.unauthorized("未认证");
        }
        return R.ok(roleMenuService.currentRoutes(principal.userId(), target));
    }
}
