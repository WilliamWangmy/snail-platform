package com.snail.platform.function.system.user.controller;

import com.snail.platform.framework.core.context.LoginUserContextHolder;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.core.response.R;
import com.snail.platform.framework.log.BusinessType;
import com.snail.platform.framework.log.OperLog;
import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.function.system.user.dto.AssignRolesRequest;
import com.snail.platform.function.system.user.dto.ResetPasswordRequest;
import com.snail.platform.function.system.user.dto.UserCreateRequest;
import com.snail.platform.function.system.user.dto.UserQuery;
import com.snail.platform.function.system.user.dto.UserUpdateRequest;
import com.snail.platform.function.system.user.dto.UserVO;
import com.snail.platform.function.system.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Objects;
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
 * 职责:用户管理入口——分页、详情、新增、修改、重置口令、停用/启用、删除、角色全量覆盖分配;作用域本租户。
 * 上游:用户管理页面;下游:UserService。出参一律走 UserVO,密文在类型层面出不了门。
 */
@Tag(name = "用户管理")
@Validated
@RestController
@RequestMapping("/system/user")
public class UserController {

    /** 用户业务服务 */
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PreAuthorize("hasAuthority('system:user:list')")
    @Operation(summary = "用户分页列表")
    @GetMapping("/page")
    public R<PageResult<UserVO>> page(@Valid UserQuery query) {
        return R.ok(userService.page(query));
    }

    @PreAuthorize("hasAuthority('system:user:list')")
    @Operation(summary = "用户详情(含授权角色集)")
    @GetMapping("/{userId}")
    public R<UserVO> detail(@PathVariable Long userId) {
        return R.ok(userService.detail(userId));
    }

    @PreAuthorize("hasAuthority('system:user:create')")
    @Operation(summary = "新增用户")
    @OperLog(module = "用户管理", businessType = BusinessType.INSERT)
    @PostMapping
    public R<Long> create(@Valid @RequestBody UserCreateRequest request) {
        return R.ok(userService.create(request));
    }

    @PreAuthorize("hasAuthority('system:user:update')")
    @Operation(summary = "修改用户")
    @OperLog(module = "用户管理", businessType = BusinessType.UPDATE)
    @PutMapping("/{userId}")
    public R<Void> update(@PathVariable Long userId, @Valid @RequestBody UserUpdateRequest request) {
        userService.update(userId, request);
        return R.ok(null);
    }

    @PreAuthorize("hasAuthority('system:user:reset-password')")
    @Operation(summary = "重置密码")
    @OperLog(module = "用户管理", businessType = BusinessType.UPDATE)
    @PutMapping("/{userId}/password")
    public R<Void> resetPassword(@PathVariable Long userId,
                                        @Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(userId, request.password());
        return R.ok(null);
    }

    @PreAuthorize("hasAuthority('system:user:update')")
    @Operation(summary = "停用/启用用户")
    @OperLog(module = "用户管理", businessType = BusinessType.UPDATE)
    @PutMapping("/{userId}/status")
    public R<Void> changeStatus(@PathVariable Long userId,
                                       @RequestParam @Min(value = 0, message = "状态取值 0/1")
                                       @Max(value = 1, message = "状态取值 0/1") int status) {
        userService.changeStatus(userId, status);
        return R.ok(null);
    }

    @PreAuthorize("hasAuthority('system:user:kick')")
    @Operation(summary = "强制下线(令牌版本自增,全设备生效)")
    @OperLog(module = "用户管理", businessType = BusinessType.UPDATE)
    @PostMapping("/{userId}/force-logout")
    public R<Void> forceLogout(@PathVariable Long userId) {
        if (Objects.equals(LoginUserContextHolder.userId(), userId)) {
            throw BizException.badRequest("不能强制下线自己");
        }
        userService.forceLogout(userId);
        return R.ok(null);
    }

    @PreAuthorize("hasAuthority('system:user:delete')")
    @Operation(summary = "删除用户(逻辑删,同事务清授权)")
    @OperLog(module = "用户管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{userId}")
    public R<Void> delete(@PathVariable Long userId) {
        userService.delete(userId);
        return R.ok(null);
    }

    @PreAuthorize("hasAuthority('system:user:assign-role')")
    @Operation(summary = "分配角色(全量覆盖)")
    @OperLog(module = "用户管理", businessType = BusinessType.UPDATE)
    @PutMapping("/{userId}/roles")
    public R<Void> assignRoles(@PathVariable Long userId, @Valid @RequestBody AssignRolesRequest request) {
        userService.assignRoles(userId, request.roleIds());
        return R.ok(null);
    }
}
