package com.snail.platform.function.auth.controller;

import com.snail.platform.framework.core.response.R;
import com.snail.platform.function.auth.dto.LoginSuccessVO;
import com.snail.platform.function.auth.dto.OAuthAuthorizeVO;
import com.snail.platform.function.auth.dto.OAuthLoginRequest;
import com.snail.platform.function.auth.dto.OAuthPickRequest;
import com.snail.platform.function.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:第三方扫码登录四端点——入口列表、发起授权(返回厂商授权页地址与 state)、
 * 授权码登录(多账号命中返回 pickKey 候选列表)、多账号选择换令牌。
 * 全部匿名可达(HTTP 白名单 /system/auth/oauth/** + 方法级 permitAll 双层放行),自身完成全部校验;
 * 登录出参两态与短信登录同构:LoginSuccessVO(直接签发)或 LoginAccountsVO(候选选择)。
 * 上游:两端登录页与 /oauth-callback/:source 回调页;下游:AuthService 的 OAuth 链。
 */
@Tag(name = "第三方单点登录")
@RestController
@RequestMapping("/system/auth/oauth")
public class OAuthLoginController {

    /** 登录校验链 */
    private final AuthService authService;

    public OAuthLoginController(AuthService authService) {
        this.authService = authService;
    }

    @PreAuthorize("permitAll()")
    @Operation(summary = "第三方登录入口列表(仅返回凭证齐备的开放来源,空列表即前端隐藏入口)")
    @GetMapping("/options")
    public R<List<String>> options() {
        return R.ok(authService.oauthOptions());
    }

    @PreAuthorize("permitAll()")
    @Operation(summary = "发起第三方授权(回调地址须与来源白名单精确匹配,返回授权页地址与 state)")
    @GetMapping("/authorize")
    public R<OAuthAuthorizeVO> authorize(@RequestParam("source") String source,
                                         @RequestParam("redirectUri") String redirectUri,
                                         HttpServletRequest http) {
        return R.ok(authService.oauthAuthorize(source, redirectUri, http));
    }

    @PreAuthorize("permitAll()")
    @Operation(summary = "第三方授权码登录(多账号命中返回 pickKey 候选列表,再调 /pick)")
    @PostMapping("/login")
    public R<Object> login(@Valid @RequestBody OAuthLoginRequest request, HttpServletRequest http) {
        // 出参两态:LoginSuccessVO(单账号,直接签发)或 LoginAccountsVO(多账号,含 pickKey 候选列表)
        return R.ok(authService.loginByOauth(request, http));
    }

    @PreAuthorize("permitAll()")
    @Operation(summary = "第三方多账号选择换令牌(仅多账号命中时)")
    @PostMapping("/pick")
    public R<LoginSuccessVO> pick(@Valid @RequestBody OAuthPickRequest request, HttpServletRequest http) {
        return R.ok(authService.pickOauthAccount(request, http));
    }
}
