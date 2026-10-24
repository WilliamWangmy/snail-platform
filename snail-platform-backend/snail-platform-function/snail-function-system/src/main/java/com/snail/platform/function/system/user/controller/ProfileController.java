package com.snail.platform.function.system.user.controller;

import com.snail.platform.framework.core.context.LoginUserContextHolder;
import com.snail.platform.framework.core.response.R;
import com.snail.platform.framework.log.BusinessType;
import com.snail.platform.framework.log.OperLog;
import com.snail.platform.function.system.user.dto.ChangePasswordRequest;
import com.snail.platform.function.system.user.dto.ProfileVO;
import com.snail.platform.function.system.user.dto.UpdateProfileRequest;
import com.snail.platform.function.system.user.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:个人中心入口——本人档案/自助改资料/自助改密/强制下线自己,全部只要求登录:
 * 操作对象恒为当前登录人自己,按钮权限码对"改自己"没有增量价值。
 * 上游:个人中心页面;下游:ProfileService。
 */
@Tag(name = "个人中心")
@RestController
@RequestMapping("/system/profile")
public class ProfileController {

    /** 个人中心服务 */
    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "本人档案")
    @GetMapping
    public R<ProfileVO> me() {
        return R.ok(profileService.me(LoginUserContextHolder.userId()));
    }

    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "修改个人资料(昵称/邮箱/手机号)")
    @OperLog(module = "个人中心", businessType = BusinessType.UPDATE)
    @PutMapping
    public R<Void> update(@Valid @RequestBody UpdateProfileRequest request) {
        profileService.update(LoginUserContextHolder.userId(), request);
        return R.ok(null);
    }

    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "修改口令(成功后全部设备重新登录)")
    @OperLog(module = "个人中心", businessType = BusinessType.UPDATE)
    @PostMapping("/password")
    public R<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        profileService.changePassword(LoginUserContextHolder.userId(), request);
        return R.ok(null);
    }

    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "强制下线自己的全部会话")
    @PostMapping("/force-logout")
    public R<Void> forceLogout() {
        profileService.forceLogout(LoginUserContextHolder.userId());
        return R.ok(null);
    }
}
