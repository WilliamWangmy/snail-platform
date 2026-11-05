package com.snail.platform.function.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 职责:第三方授权跳转出参——厂商授权页地址与本次会话的 state。
 * state 与回调地址在服务端绑定(5 分钟 GETDEL 一次一用),前端整页跳 authorizeUrl 后
 * 把 state 随 code 原样带回。
 * 上游:AuthService.oauthAuthorize;下游:前端登录页。
 */
@Schema(description = "第三方授权跳转")
public record OAuthAuthorizeVO(

        @Schema(description = "厂商授权页地址,浏览器整页跳转", requiredMode = Schema.RequiredMode.REQUIRED)
        String authorizeUrl,

        @Schema(description = "一次性 state,回调时随 code 原样回传", requiredMode = Schema.RequiredMode.REQUIRED)
        String state) {
}
