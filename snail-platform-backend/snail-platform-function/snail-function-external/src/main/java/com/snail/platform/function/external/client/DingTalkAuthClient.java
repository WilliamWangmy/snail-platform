package com.snail.platform.function.external.client;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.snail.platform.function.external.api.SocialAuthPort;
import com.snail.platform.function.external.config.SnailExternalProperties;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 职责:钉钉适配(企业内部应用,统一新授权端点):PC 扫码整页跳转;二期客户端内免登为
 * JSAPI 取 authCode 后复用同一交换链路。出站链路:v1.0/oauth2/userAccessToken(JSON POST)
 * → contact/users/me(header 携令牌),unionId 为平台唯一身份。
 * 注意:mobile 仅在应用勾选「个人手机号信息(corpid scope)」时返回,scope=openid 不返回手机号
 * (snail 联调实证)——手机号 JIT 绑定依赖该权限。
 * 上游:external.api 的 SocialAuthPort;下游:AbstractSocialAuthClient 出站收口。
 */
@Component
@ConditionalOnProperty(name = "snail.external.oauth.mock", havingValue = "false", matchIfMissing = true)
public class DingTalkAuthClient extends AbstractSocialAuthClient {

    /** 统一授权端点(PC 扫码) */
    private static final String AUTHORIZE = "https://login.dingtalk.com/oauth2/auth";

    /** 授权码换用户访问令牌 */
    private static final String TOKEN = "https://api.dingtalk.com/v1.0/oauth2/userAccessToken";

    /** 读取登录用户信息 */
    private static final String USERINFO = "https://api.dingtalk.com/v1.0/contact/users/me";

    /** 用户信息接口的令牌请求头 */
    private static final String TOKEN_HEADER = "x-acs-dingtalk-access-token";

    /** 凭据与开关的类型化配置单点,取 snail.external.oauth.dingtalk 段 */
    private final SnailExternalProperties props;

    public DingTalkAuthClient(ExternalCallLedger ledger, SnailExternalProperties props) {
        super(ledger);
        this.props = props;
    }

    /** 钉钉凭据;凭据缺失时 available()=false 即入口不可用,不阻断启动 */
    private String appId() {
        return props.getOauth().getDingtalk().getAppId();
    }

    private String appSecret() {
        return props.getOauth().getDingtalk().getAppSecret();
    }

    @Override
    protected String vendor() {
        return "dingtalk";
    }

    @Override
    public String source() {
        return "dingtalk";
    }

    @Override
    public boolean available() {
        return notBlank(appId()) && notBlank(appSecret());
    }

    @Override
    public String authorizeUrl(String redirectUri, String state) {
        return AUTHORIZE
                + "?redirect_uri=" + urlEncode(redirectUri)
                + "&client_id=" + urlEncode(appId())
                + "&response_type=code"
                + "&scope=openid"
                + "&state=" + urlEncode(state)
                + "&prompt=consent";
    }

    @Override
    public SocialIdentity exchange(String code, String redirectUri) {
        // 1. 授权码换用户访问令牌:clientSecret 只在请求体,快照不落密钥
        String tokenBody = JSONUtil.toJsonStr(Map.of(
                "clientId", appId(),
                "clientSecret", appSecret(),
                "grantType", "authorization_code",
                "code", code));
        JSONObject tokenResp = parse(postJson(TOKEN, tokenBody, "oauth-token"), "oauth-token");
        String accessToken = requireField(tokenResp, "accessToken", "oauth-token", "钉钉返回的访问令牌为空");

        // 2. 读取登录用户信息
        JSONObject user = parse(getJson(USERINFO, null, Map.of(TOKEN_HEADER, accessToken), "oauth-userinfo"),
                "oauth-userinfo");
        String unionId = requireField(user, "unionId", "oauth-userinfo", "钉钉返回的用户标识为空");
        return new SocialIdentity(unionId, unionId, user.getStr("nick"),
                user.getStr("avatarUrl"), user.getStr("mobile"));
    }
}
