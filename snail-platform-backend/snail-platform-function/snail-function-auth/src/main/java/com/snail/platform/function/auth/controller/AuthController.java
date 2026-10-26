package com.snail.platform.function.auth.controller;

import com.snail.platform.framework.core.context.LoginUser;
import com.snail.platform.framework.core.context.LoginUserContextHolder;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.core.response.R;
import com.snail.platform.framework.security.authority.UserAuthorityProvider;
import com.snail.platform.function.auth.dto.CaptchaVO;
import com.snail.platform.function.auth.dto.LoginRequest;
import com.snail.platform.function.auth.dto.LoginSuccessVO;
import com.snail.platform.function.auth.dto.LoginUserVO;
import com.snail.platform.function.auth.service.AuthService;
import com.snail.platform.function.auth.service.CaptchaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:认证入口四端点——取验证码、登录、查看当前身份(/me 兼作过滤器链的自检点)、登出。
 * 登出由服务端自增令牌版本,当前用户全部设备立即失效;本期仍不设 refresh token 与 jti 黑名单。
 * 上游:登录页与全局登出入口;下游:CaptchaService、AuthService。
 */
@Tag(name = "认证")
@RestController
@RequestMapping("/system/auth")
public class AuthController {

    /** 验证码服务 */
    private final CaptchaService captchaService;

    /** 登录校验链 */
    private final AuthService authService;

    /** 权限码端口,与认证过滤器同源同缓存 */
    private final UserAuthorityProvider authorityProvider;

    public AuthController(CaptchaService captchaService, AuthService authService,
                          UserAuthorityProvider authorityProvider) {
        this.captchaService = captchaService;
        this.authService = authService;
        this.authorityProvider = authorityProvider;
    }

    @PreAuthorize("permitAll()")
    @Operation(summary = "获取图形验证码")
    @GetMapping("/captcha")
    public R<CaptchaVO> captcha() {
        return R.ok(captchaService.generate());
    }

    @PreAuthorize("permitAll()")
    @Operation(summary = "账号密码+验证码登录")
    @PostMapping("/login")
    public R<LoginSuccessVO> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return R.ok(authService.login(request, http));
    }

    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "登出(当前用户全部设备立即失效)")
    @PostMapping("/logout")
    public R<Void> logout() {
        authService.logout(LoginUserContextHolder.userId());
        return R.ok(null);
    }

    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "当前登录人(身份+组织/租户名称+生效角色)")
    @GetMapping("/me")
    public R<LoginUserVO> me() {
        LoginUser loginUser = LoginUserContextHolder.get();
        if (loginUser == null) {
            throw BizException.unauthorized("未认证");
        }
        return R.ok(LoginUserVO.of(loginUser, authorityProvider.authoritiesOf(loginUser.userId())));
    }
}
