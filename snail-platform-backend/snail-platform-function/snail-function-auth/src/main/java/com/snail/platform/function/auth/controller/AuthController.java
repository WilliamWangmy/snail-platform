package com.snail.platform.function.auth.controller;

import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.core.response.R;
import com.snail.platform.framework.security.token.SnailPrincipal;
import com.snail.platform.function.auth.dto.CaptchaVO;
import com.snail.platform.function.auth.dto.LoginRequest;
import com.snail.platform.function.auth.dto.LoginSuccessVO;
import com.snail.platform.function.auth.service.AuthService;
import com.snail.platform.function.auth.service.CaptchaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:认证入口三端点——取验证码、登录、查看当前身份(/me 兼作过滤器链的自检点)。
 * 登出即前端丢弃令牌:本期 JWT 无状态、不设黑名单,口径见 JwtTokenService。
 * 上游:登录页;下游:CaptchaService、AuthService。
 */
@Tag(name = "认证")
@RestController
@RequestMapping("/system/auth")
public class AuthController {

    /** 验证码服务 */
    private final CaptchaService captchaService;

    /** 登录校验链 */
    private final AuthService authService;

    public AuthController(CaptchaService captchaService, AuthService authService) {
        this.captchaService = captchaService;
        this.authService = authService;
    }

    @Operation(summary = "获取图形验证码")
    @GetMapping("/captcha")
    public R<CaptchaVO> captcha() {
        return R.ok(captchaService.generate());
    }

    @Operation(summary = "账号密码+验证码登录")
    @PostMapping("/login")
    public R<LoginSuccessVO> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return R.ok(authService.login(request, http));
    }

    @Operation(summary = "当前登录身份")
    @GetMapping("/me")
    public R<SnailPrincipal> me() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof SnailPrincipal principal)) {
            throw BizException.unauthorized("未认证");
        }
        return R.ok(principal);
    }
}
