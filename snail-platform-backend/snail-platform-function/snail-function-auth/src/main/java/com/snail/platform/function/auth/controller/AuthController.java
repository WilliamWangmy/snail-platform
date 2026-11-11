package com.snail.platform.function.auth.controller;

import cn.hutool.extra.servlet.JakartaServletUtil;
import com.snail.platform.framework.core.context.LoginUser;
import com.snail.platform.framework.core.context.LoginUserContextHolder;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.core.response.R;
import com.snail.platform.framework.security.authority.UserAuthorityProvider;
import com.snail.platform.function.auth.dto.CaptchaVO;
import com.snail.platform.function.auth.dto.LoginRequest;
import com.snail.platform.function.auth.dto.LoginSuccessVO;
import com.snail.platform.function.auth.dto.LoginUserVO;
import com.snail.platform.function.auth.dto.SmsCaptchaRequest;
import com.snail.platform.function.auth.dto.SmsLoginRequest;
import com.snail.platform.function.auth.dto.SmsPickRequest;
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
 * 职责:认证入口七端点——取验证码、密码登录、发短信验证码、短信登录、多账号选择、
 * 查看当前身份(/me 兼作过滤器链的自检点)、登出。
 * 登出由服务端自增令牌版本,当前用户全部设备立即失效;本期仍不设 refresh token 与 jti 黑名单。
 * 上游:登录页(密码/手机号两个 tab)与全局登出入口;下游:CaptchaService、AuthService。
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
    public R<CaptchaVO> captcha(HttpServletRequest http) {
        return R.ok(captchaService.generate(JakartaServletUtil.getClientIP(http)));
    }

    @PreAuthorize("permitAll()")
    @Operation(summary = "账号密码+验证码登录")
    @PostMapping("/login")
    public R<LoginSuccessVO> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return R.ok(authService.login(request, http));
    }

    @PreAuthorize("permitAll()")
    @Operation(summary = "发送短信验证码(图形验证码前置,四道限流闸)")
    @PostMapping("/sms-captcha")
    public R<Void> sendSmsCaptcha(@Valid @RequestBody SmsCaptchaRequest request, HttpServletRequest http) {
        authService.sendSmsCaptcha(request, http);
        return R.ok(null);
    }

    @PreAuthorize("permitAll()")
    @Operation(summary = "短信验证码登录(多租户账号时返回 pickKey 候选列表,再调 /login-sms/pick)")
    @PostMapping("/login-sms")
    public R<Object> loginSms(@Valid @RequestBody SmsLoginRequest request, HttpServletRequest http) {
        // 出参两态:LoginSuccessVO(单账号,直接签发)或 LoginAccountsVO(多账号,含 pickKey 候选列表)
        return R.ok(authService.loginBySms(request, http));
    }

    @PreAuthorize("permitAll()")
    @Operation(summary = "多租户账号选择换令牌(仅多账号命中时)")
    @PostMapping("/login-sms/pick")
    public R<LoginSuccessVO> pickSmsAccount(@Valid @RequestBody SmsPickRequest request,
                                            HttpServletRequest http) {
        return R.ok(authService.pickSmsAccount(request, http));
    }

    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "登出(当前用户全部设备立即失效)")
    @PostMapping("/logout")
    public R<Void> logout() {
        authService.logout(LoginUserContextHolder.userId());
        return R.ok(null);
    }

    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "当前登录人(身份+组织/租户名称+生效角色+租户五系统开通态)")
    @GetMapping("/me")
    public R<LoginUserVO> me() {
        LoginUser loginUser = LoginUserContextHolder.get();
        // 防御分支:认证通过的请求过滤器必已注入快照,这里兜住端口装配缺失等异常态
        if (loginUser == null) {
            throw BizException.unauthorized("未认证");
        }
        // tenantFeatures 实时查 sys_tenant(设计 v1.3 落点:me 组装在控制器,查询经 AuthService):
        // 不进 JWT、不进登录快照,开关改动对下一次 /me 即时生效,workflow/dashboard 守卫据此踢会话
        return R.ok(LoginUserVO.of(loginUser, authorityProvider.authoritiesOf(loginUser.userId()))
                .withTenantFeatures(authService.tenantFeaturesOf(loginUser.tenantId())));
    }
}
