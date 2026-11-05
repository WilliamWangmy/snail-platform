package com.snail.platform.function.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 职责:第三方授权码登录入参。state 仅重定向流携带(免登态缺省,二期);
 * redirectUri 为前端回调地址回显,state 存在时必须与服务端登记一致,
 * state 缺省时按来源白名单解析兜底(仅飞书交换强制需要)。
 * 与 LoginRequest/SmsLoginRequest 字段交集为零,不复用(同既有口径)。
 * 上游:前端回调页/厂商容器免登;下游:AuthService.loginByOauth。
 */
@Schema(description = "第三方授权码登录请求")
public record OAuthLoginRequest(

        @Schema(description = "来源编码:dingtalk/feishu/wecom", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "来源不能为空")
        @Size(max = 20, message = "来源编码过长")
        String source,

        @Schema(description = "厂商授权码", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "缺少授权码")
        @Size(max = 256, message = "授权码过长")
        String code,

        @Schema(description = "一次性 state,重定向流必带,免登态缺省")
        @Size(max = 64, message = "state 过长")
        String state,

        @Schema(description = "前端回调地址回显,免登态可空")
        @Size(max = 512, message = "回调地址过长")
        String redirectUri) {
}
