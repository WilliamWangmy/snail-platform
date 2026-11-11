package com.snail.platform.function.external.client.mock;

import com.snail.platform.function.external.api.SocialAuthPort;
import com.snail.platform.function.external.client.AbstractSocialAuthClient;
import com.snail.platform.function.external.client.ExternalCallLedger;
import com.snail.platform.function.external.client.ExternalInvocation;

/**
 * 职责:开发态厂商适配——authorizeUrl 直接造成本地回调(?code=mock-{source}&state=...),
 * exchange 返回固定 mock 身份,使第三方扫码登录在无厂商应用时也能端到端联调;
 * 生产 profile 缺省缺席,由三家真实客户端顶位。与真实通道同一留痕口径:
 * 经模板方法落 sys_external_call_log。无 @Component:实例由 start 的 mock 装配类
 * MockSocialAuthConfig 按来源注册三个(mock 要同时顶替三家,单实例没法用 source() 区分)。
 * mock-mobile 决定手机号 JIT 绑定命中:改成与本地库某用户 sys_user.phone 一致即可验证
 * 绑定与多租户 pick。上游:external.api 的 SocialAuthPort;下游:ExternalCallLedger 流水。
 */
public class MockSocialAuthClient extends AbstractSocialAuthClient {

    /** mock 身份的实名手机号,驱动手机号 JIT 绑定与多租户 pick 的本地验证 */
    private final String mockMobile;

    /** 顶替的来源编码:dingtalk/feishu/wecom,与登录页入口一一对应 */
    private final String source;

    public MockSocialAuthClient(ExternalCallLedger ledger, String mockMobile, String source) {
        super(ledger);
        this.mockMobile = mockMobile;
        this.source = source;
    }

    @Override
    protected String vendor() {
        return "mock-sso";
    }

    @Override
    public String source() {
        return source;
    }

    @Override
    public boolean available() {
        return true;
    }

    /** 本地回路:授权页即前端回调页本身,授权码固定 mock-{source},state 原样带回 */
    @Override
    public String authorizeUrl(String redirectUri, String state) {
        return redirectUri + "?code=mock-" + source + "&state=" + state;
    }

    @Override
    public SocialIdentity exchange(String code, String redirectUri) {
        SocialIdentity identity = new SocialIdentity("mock-" + source, "mock-union-" + source,
                "Mock用户", null, mockMobile);
        invoke(new ExternalInvocation("sso-login", CALLER, BIZ_TYPE, null,
                        "{\"source\":\"" + jsonString(source) + "\",\"code\":\"" + jsonString(code) + "\"}"),
                () -> identity.openId());
        return identity;
    }
}
