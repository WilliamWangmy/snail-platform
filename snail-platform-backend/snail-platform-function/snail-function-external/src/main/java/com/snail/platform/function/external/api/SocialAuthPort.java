package com.snail.platform.function.external.api;

/**
 * 职责:第三方扫码登录的统一身份端口——消费方(auth 登录链)要"用厂商授权码换平台身份",
 * 不感知钉钉/飞书/企业微信的端点与报文差异;全部厂商 HTTP 收口在本模块实现内,消费方零 HTTP。
 * 契约口径:一次登录 = authorizeUrl(重定向) + exchange(换身份) 两步;免登态(code 由厂商容器
 * 静默授权直接下发,二期)无授权跳转,exchange 的 redirectUri 由调用方按该来源白名单解析兜底后传入。
 * 失败抛 ExternalCallException——认证是登录主链路,由消费方统一转译为对外文案,厂商错因不透出。
 * 留痕由实现经 AbstractExternalClient 落 sys_external_call_log(vendor=来源编码),本端口不做 HTTP。
 * 上游:auth 的 OAuth 登录链;下游:DingTalkAuthClient/FeishuAuthClient/WecomAuthClient/MockSocialAuthClient。
 */
public interface SocialAuthPort {

    /** 来源编码,与 sys_login_log.login_type 同口径:dingtalk/feishu/wecom */
    String source();

    /** 应用凭证是否齐备:ID/密钥任一缺失即 false,消费方按"登录方式未开放"降级,不阻断启动 */
    boolean available();

    /** 组装平台授权页地址(PC 扫码/网页授权),浏览器整页跳转;state 由调用方签发并登记(防 CSRF/重放) */
    String authorizeUrl(String redirectUri, String state);

    /**
     * 授权码换平台身份:code → 平台 token → 平台用户信息,一步完成并映射为统一模型。
     * redirectUri 须与授权时一致(飞书交换强制校验),免登态由调用方按来源白名单解析兜底后传入,
     * 缺失由实现抛 ExternalCallException。失败统一 ExternalCallException。
     */
    SocialIdentity exchange(String code, String redirectUri);

    /** 统一身份模型:平台侧字段映射;mobile 已按平台口径剥码(飞书 +86),平台权限未开通或未返回时为 null */
    record SocialIdentity(
            /** 平台内稳定身份标识,绑定查询键:钉钉 unionId/飞书 openId/企微 userid */
            String openId,
            /** 平台跨应用标识,钉钉与 openId 同值,飞书预留 */
            String unionId,
            /** 平台昵称/姓名快照 */
            String nickname,
            /** 平台头像地址 */
            String avatarUrl,
            /** 平台实名手机号(裸号) */
            String mobile) {
    }
}
