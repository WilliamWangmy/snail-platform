package com.snail.platform.function.external.client;

import cn.hutool.json.JSONObject;
import com.snail.platform.function.external.api.ExternalCallException;
import com.snail.platform.function.external.api.SocialAuthPort;
import com.snail.platform.function.external.config.SnailExternalProperties;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 职责:企业微信适配(自建应用):PC 扫码走 wwopen/sso/qrConnect(snail 联调验证,可信域名为
 * 域名级且需归属验证);二期企微容器内为 open.weixin.qq.com OAuth2 snsapi_base 静默授权
 * 复用同一交换链路。出站链路:gettoken(corpid+secret 换应用令牌,首版每次现取,频控实证后
 * 再加缓存)→ auth/getuserinfo(授权码换 userid)→ user/get(成员详情,失败降级不影响登录身份)。
 * userid 为平台唯一身份。
 * 上游:external.api 的 SocialAuthPort;下游:AbstractSocialAuthClient 出站收口。
 */
@Component
@ConditionalOnProperty(name = "snail.external.oauth.mock", havingValue = "false", matchIfMissing = true)
public class WecomAuthClient extends AbstractSocialAuthClient {

    /** PC 扫码授权页 */
    private static final String AUTHORIZE = "https://login.work.weixin.qq.com/wwopen/sso/qrConnect";

    /** 企业凭证:corpid+secret 换应用令牌 */
    private static final String GETTOKEN = "https://qyapi.weixin.qq.com/cgi-bin/gettoken";

    /** 授权码换用户 userid */
    private static final String GETUSERINFO = "https://qyapi.weixin.qq.com/cgi-bin/auth/getuserinfo";

    /** 成员详情(姓名/头像/手机号) */
    private static final String GETUSER = "https://qyapi.weixin.qq.com/cgi-bin/user/get";

    /** 凭据与开关的类型化配置单点,取 snail.external.oauth.wecom 段 */
    private final SnailExternalProperties props;

    public WecomAuthClient(ExternalCallLedger ledger, SnailExternalProperties props) {
        super(ledger);
        this.props = props;
    }

    /** 企业微信凭据;任一缺失时 available()=false 即入口不可用,不阻断启动 */
    private String corpId() {
        return props.getOauth().getWecom().getCorpId();
    }

    private String agentId() {
        return props.getOauth().getWecom().getAgentId();
    }

    private String corpSecret() {
        return props.getOauth().getWecom().getCorpSecret();
    }

    @Override
    protected String vendor() {
        return "wecom";
    }

    @Override
    public String source() {
        return "wecom";
    }

    @Override
    public boolean available() {
        return notBlank(corpId()) && notBlank(agentId()) && notBlank(corpSecret());
    }

    @Override
    public String authorizeUrl(String redirectUri, String state) {
        return AUTHORIZE
                + "?appid=" + urlEncode(corpId())
                + "&agentid=" + urlEncode(agentId())
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&state=" + urlEncode(state);
    }

    @Override
    public SocialIdentity exchange(String code, String redirectUri) {
        // 1. 企业凭证换应用令牌:corpsecret 只在查询串,快照只落基址;首版每次现取不缓存(NFR-008)
        JSONObject tokenResp = parse(getJson(GETTOKEN,
                Map.of("corpid", corpId(), "corpsecret", corpSecret()), null, "oauth-corp-token"),
                "oauth-corp-token");
        assertWxworkOk(tokenResp, "oauth-corp-token");
        String accessToken = requireField(tokenResp, "access_token", "oauth-corp-token", "企业微信返回的应用令牌为空");

        // 2. 授权码换 userid
        JSONObject userinfo = parse(getJson(GETUSERINFO,
                Map.of("access_token", accessToken, "code", code), null, "oauth-userid"), "oauth-userid");
        assertWxworkOk(userinfo, "oauth-userid");
        String userid = requireField(userinfo, "userid", "oauth-userid", "企业微信返回的用户标识为空");

        // 3. 成员详情:非关键步骤,失败降级为仅有 userid,不影响登录身份判定(snail 实证口径)
        JSONObject detail;
        try {
            detail = parse(getJson(GETUSER,
                    Map.of("access_token", accessToken, "userid", userid), null, "oauth-user-detail"),
                    "oauth-user-detail");
            assertWxworkOk(detail, "oauth-user-detail");
        } catch (ExternalCallException degraded) {
            detail = new JSONObject();
        }
        return new SocialIdentity(userid, null, detail.getStr("name"),
                detail.getStr("avatar"), detail.getStr("mobile"));
    }

    /** 企微报文口径:errcode 存在且非 0 即失败 */
    private void assertWxworkOk(JSONObject resp, String action) {
        String errcode = resp.getStr("errcode");
        if (errcode != null && !"0".equals(errcode)) {
            throw new ExternalCallException(vendor(), action,
                    "企业微信调用失败:errcode=" + errcode + ", errmsg=" + resp.getStr("errmsg"));
        }
    }
}
