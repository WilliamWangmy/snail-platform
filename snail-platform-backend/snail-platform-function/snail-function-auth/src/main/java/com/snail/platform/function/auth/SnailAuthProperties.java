package com.snail.platform.function.auth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 职责:登录鉴权域开关与白名单的配置单点——密码/短信登录开关与验证码模板、第三方扫码登录
 * 总开关、手机号 JIT 绑定开关、各来源授权回调地址白名单。此前散落在 AuthService 构造器的
 * 7 个 @Value,现收口为类型化配置;字符串 key 与嵌套结构只在这里声明一次。
 * 上游:application*.yml 的 snail.auth 段;下游:AuthService。
 * 口径:redirect-uris 保留逗号分隔字符串原样入库,切分与去空白仍由 AuthService.parseRedirectUris
 * 收口,与原 @Value 时代逐字一致,白名单精确匹配口径不变。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "snail.auth")
public class SnailAuthProperties {

    /** 短信登录配置 */
    private final SmsLogin smsLogin = new SmsLogin();

    /** 第三方扫码登录(钉钉/飞书/企业微信)配置 */
    private final Oauth oauth = new Oauth();

    /** 短信登录 */
    @Getter
    @Setter
    public static class SmsLogin {

        /** 短信登录总开关,灰度/回滚用;false 时短信三端点报"功能未开放" */
        private boolean enabled = true;

        /** 登录验证码模板编码,阿里云报备后回填;缺省空 = 短信登录不可用;mock 通道忽略该值 */
        private String loginTemplateCode;
    }

    /** 第三方扫码登录 */
    @Getter
    @Setter
    public static class Oauth {

        /** 总开关:回滚第一级,false 时入口列表返回空、端点按"登录方式未开放"拒绝 */
        private boolean enabled = true;

        /** 手机号 JIT 绑定 */
        private final PhoneBind phoneBind = new PhoneBind();

        /** 各来源回调白名单 */
        private final Sources sources = new Sources();
    }

    /** 手机号 JIT 绑定:未绑定身份按厂商实名手机号自动关联唯一可用平台账号并落绑定 */
    @Getter
    @Setter
    public static class PhoneBind {

        /** false 则一律走报错引导 */
        private boolean enabled = true;
    }

    /** 各来源授权回调地址白名单(逗号分隔,精确匹配);凭证缺失的来源天然不可用 */
    @Getter
    @Setter
    public static class Sources {

        private final Source dingtalk = new Source();

        private final Source feishu = new Source();

        private final Source wecom = new Source();
    }

    /** 单个来源的白名单:逗号分隔,精确匹配;空 = 该来源回调一律拒绝 */
    @Getter
    @Setter
    public static class Source {

        /** 授权回调地址白名单 */
        private String redirectUris;
    }
}
