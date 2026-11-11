package com.snail.platform.function.external.client;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.snail.platform.function.external.api.ExternalCallException;
import com.snail.platform.function.external.api.SocialAuthPort;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 职责:三家厂商适配器的公共中转基类——统一出站超时、快照口径(只落 URL 基址,
 * 密钥/令牌/授权码根本不进流水)、响应解析与字段校验;协议差异全部留在子类。
 * public:mock 子类住 client.mock 子包(开发态 mock 归拢),跨包继承需要公开基类。
 * 上游:三家 SocialAuthPort 实现;下游:AbstractExternalClient 模板方法与 ExternalCallLedger。
 */
public abstract class AbstractSocialAuthClient extends AbstractExternalClient implements SocialAuthPort {

    /** 连接超时:认证是登录主链路,短超时快速失败,文案由消费方统一转译 */
    static final int CONNECT_TIMEOUT_MS = 3000;

    /** 读超时:同上 */
    static final int READ_TIMEOUT_MS = 5000;

    /** 流水 caller:调用方恒为 auth 登录链;protected 供跨包的 mock 子类复用 */
    protected static final String CALLER = "auth";

    /** 流水 biz_type;protected 供跨包的 mock 子类复用 */
    protected static final String BIZ_TYPE = "sso-login";

    protected AbstractSocialAuthClient(ExternalCallLedger ledger) {
        super(ledger);
    }

    /** POST JSON 并返回响应体;快照只落 URL 基址,body 里的密钥/授权码不进流水 */
    String postJson(String url, String jsonBody, String action) {
        return invoke(new ExternalInvocation(action, CALLER, BIZ_TYPE, null,
                        "{\"url\":\"" + jsonString(url) + "\"}"),
                () -> HttpRequest.post(url)
                        .header("Content-Type", "application/json;charset=UTF-8")
                        .body(jsonBody)
                        .setConnectionTimeout(CONNECT_TIMEOUT_MS)
                        .setReadTimeout(READ_TIMEOUT_MS)
                        .execute()
                        .body());
    }

    /** GET(查询参数/请求头在内部拼装)并返回响应体;快照只落 URL 基址,凭据类查询参数与令牌头不进流水 */
    String getJson(String url, Map<String, String> queryParams, Map<String, String> headers, String action) {
        return invoke(new ExternalInvocation(action, CALLER, BIZ_TYPE, null,
                        "{\"url\":\"" + jsonString(url) + "\"}"),
                () -> {
                    HttpRequest request = HttpRequest.get(appendQuery(url, queryParams));
                    if (headers != null) {
                        headers.forEach(request::header);
                    }
                    return request.setConnectionTimeout(CONNECT_TIMEOUT_MS)
                            .setReadTimeout(READ_TIMEOUT_MS)
                            .execute()
                            .body();
                });
    }

    /** 解析平台响应;坏报文按厂商动作抛 ExternalCallException,消费方不透出厂商错因 */
    JSONObject parse(String body, String action) {
        if (body == null || body.isBlank()) {
            throw new ExternalCallException(vendor(), action, "平台返回空响应");
        }
        try {
            return JSONUtil.parseObj(body);
        } catch (Exception e) {
            throw new ExternalCallException(vendor(), action, "平台响应不是合法 JSON", e);
        }
    }

    /** 字段校验单点:缺失/空白即按厂商动作抛 ExternalCallException */
    String requireField(JSONObject json, String field, String action, String message) {
        String value = json.getStr(field);
        if (value == null || value.isBlank()) {
            throw new ExternalCallException(vendor(), action, message);
        }
        return value;
    }

    static String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String appendQuery(String url, Map<String, String> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return url;
        }
        StringBuilder joined = new StringBuilder(url).append('?');
        boolean first = true;
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            if (!first) {
                joined.append('&');
            }
            first = false;
            joined.append(urlEncode(entry.getKey())).append('=').append(urlEncode(entry.getValue()));
        }
        return joined.toString();
    }
}
