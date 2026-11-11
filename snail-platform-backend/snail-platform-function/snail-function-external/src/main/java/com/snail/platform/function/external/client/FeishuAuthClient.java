package com.snail.platform.function.external.client;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.snail.platform.function.external.config.SnailExternalProperties;
import com.snail.platform.function.external.api.ExternalCallException;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 职责:飞书适配(企业自建应用):PC 扫码整页跳转;二期工作台内为 h5sdk 取 authCode 后
 * 复用同一交换链路。出站链路:authen/v2/oauth/token(JSON POST,交换必须回传与授权一致的
 * redirect_uri)→ authen/v1/user_info(Bearer),open_id 为平台唯一身份。
 * 注意:mobile 带 +86 国家码,入库前剥成裸号(snail 联调实录);手机号需单独申请权限。
 * 上游:external.api 的 SocialAuthPort;下游:AbstractSocialAuthClient 出站收口。
 */
@Component
@ConditionalOnProperty(name = "snail.external.oauth.mock", havingValue = "false", matchIfMissing = true)
public class FeishuAuthClient extends AbstractSocialAuthClient {

    /** 凭据与 SSO 端点的类型化配置单点,取 snail.external.oauth.feishu 段(端点默认值住 external.yml) */
    private final SnailExternalProperties props;

    public FeishuAuthClient(ExternalCallLedger ledger, SnailExternalProperties props) {
        super(ledger);
        this.props = props;
    }

    /** 飞书凭据与端点;凭据缺失时 available()=false 即入口不可用,不阻断启动 */
    private SnailExternalProperties.Vendor config() {
        return props.getOauth().getFeishu();
    }

    private String appId() {
        return config().getAppId();
    }

    private String appSecret() {
        return config().getAppSecret();
    }

    @Override
    protected String vendor() {
        return "feishu";
    }

    @Override
    public String source() {
        return "feishu";
    }

    @Override
    public boolean available() {
        return notBlank(appId()) && notBlank(appSecret());
    }

    @Override
    public String authorizeUrl(String redirectUri, String state) {
        return config().getAuthorizeUrl()
                + "?app_id=" + urlEncode(appId())
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&response_type=code"
                + "&state=" + urlEncode(state);
    }

    @Override
    public SocialIdentity exchange(String code, String redirectUri) {
        if (!notBlank(redirectUri)) {
            throw new ExternalCallException(vendor(), "oauth-token", "飞书交换必须回传与授权一致的 redirect_uri");
        }
        // 1. 授权码换用户访问令牌:clientSecret 只在请求体,快照不落密钥
        String tokenBody = JSONUtil.toJsonStr(Map.of(
                "grant_type", "authorization_code",
                "client_id", appId(),
                "client_secret", appSecret(),
                "code", code,
                "redirect_uri", redirectUri));
        JSONObject tokenResp = parse(postJson(config().getTokenUrl(), tokenBody, "oauth-token"), "oauth-token");
        String accessToken = requireField(tokenResp, "access_token", "oauth-token", "飞书返回的访问令牌为空");

        // 2. 读取登录用户信息
        JSONObject user = parse(getJson(config().getUserInfoUrl(), null, Map.of("Authorization", "Bearer " + accessToken),
                "oauth-userinfo"), "oauth-userinfo");
        String bizCode = user.getStr("code");
        if (bizCode != null && !"0".equals(bizCode)) {
            throw new ExternalCallException(vendor(), "oauth-userinfo",
                    "飞书调用失败:code=" + bizCode + ", msg=" + user.getStr("msg"));
        }
        JSONObject data = user.getJSONObject("data");
        if (data == null) {
            data = new JSONObject();
        }
        String openId = requireField(data, "open_id", "oauth-userinfo", "飞书返回的用户标识为空");
        return new SocialIdentity(openId, data.getStr("union_id"), data.getStr("name"),
                data.getStr("avatar_url"), stripCountryCode(data.getStr("mobile")));
    }

    /** 平台手机号带国家码,绑定匹配按裸号口径,与 sys_user.phone 对齐 */
    private static String stripCountryCode(String mobile) {
        if (mobile != null && mobile.startsWith("+86")) {
            return mobile.substring(3);
        }
        return mobile;
    }
}
