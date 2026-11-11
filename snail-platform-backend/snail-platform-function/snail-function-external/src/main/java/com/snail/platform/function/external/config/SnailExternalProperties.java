package com.snail.platform.function.external.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 职责:第三方出站凭据的配置单点——OAuth 扫码登录(钉钉/飞书/企业微信)的应用凭据、
 * 短信通道 mock 开关、邮件发件人。所有密钥仍由环境变量经 application*.yml 注入,
 * 本类只做类型化收口:字符串 key 与嵌套结构只在这里声明一次,业务客户端不再散落 @Value。
 * 注意:登录验证码模板编码属登录域,住 snail-function-auth 的 SnailAuthProperties(架构执法:
 * auth 模块跨模块依赖只准落本模块 api 包,故 auth 不直接引用本类)。
 * 上游:application*.yml 的 snail.external 段;下游:三家 AuthClient、AliyunSmsClient、
 * SmtpMailClient、client.mock 子包的开发态 mock 客户端与 start 层 mock 装配类。
 * 口径:凭据缺失不阻断启动,由各端口的 available() 运行期判空降级,与原 @Value 时代一致。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "snail.external")
public class SnailExternalProperties {

    /** OAuth 扫码登录(钉钉/飞书/企业微信)凭据与开发态 mock */
    private final Oauth oauth = new Oauth();

    /** 短信通道:开发态 mock 开关与业务模板编码 */
    private final Sms sms = new Sms();

    /** 邮件通道:发件人地址(SMTP 连接参数仍走 spring.mail.*,由 Boot 自动配置消费) */
    private final Mail mail = new Mail();

    /** OAuth 扫码登录配置 */
    @Getter
    @Setter
    public static class Oauth {

        /** 开发态 mock 总开关:授权与交换不触达厂商,直接造本地回调;生产 profile 无此键即 false */
        private boolean mock;

        /** mock 身份的实名手机号,验证 JIT 绑定与多租户 pick 时改成与本地库 sys_user.phone 一致 */
        private String mockMobile = "13800000000";

        /** 钉钉企业内部应用凭据 */
        private final Vendor dingtalk = new Vendor();

        /** 飞书企业自建应用凭据 */
        private final Vendor feishu = new Vendor();

        /** 企业微信自建应用凭据 */
        private final Wecom wecom = new Wecom();
    }

    /**
     * 钉钉/飞书共通凭据与端点形态:appId + appSecret + 三段 SSO 端点。
     * 端点默认值住共享 yml(config/external.yml),代码不写死;专有钉钉/私有化网关
     * 等非公网部署时在 profile 覆盖即可,客户端零改动。
     */
    @Getter
    @Setter
    public static class Vendor {

        /** 应用 AppId(client_id) */
        private String appId;

        /** 应用 AppSecret:仅服务端交换请求体使用,快照与日志不落 */
        private String appSecret;

        /** 授权页端点(PC 扫码整页跳转的基址,查询串由客户端拼装) */
        private String authorizeUrl;

        /** 授权码换用户访问令牌端点 */
        private String tokenUrl;

        /** 读取登录用户信息端点 */
        private String userInfoUrl;
    }

    /** 企业微信凭据与端点形态:corpid + agentid + corpsecret + 四段 SSO 端点 */
    @Getter
    @Setter
    public static class Wecom {

        /** 企业 CorpId(扫码授权页的 appid 参数) */
        private String corpId;

        /** 自建应用 AgentId(扫码授权页的 agentid 参数) */
        private String agentId;

        /** 应用 Secret:仅服务端 gettoken 使用,快照只落基址 */
        private String corpSecret;

        /** PC 扫码授权页端点 */
        private String authorizeUrl;

        /** 企业凭证端点:corpid+secret 换应用令牌;字段名避开 get 前缀(Lombok getter 命名陷阱) */
        private String corpTokenUrl;

        /** 授权码换用户 userid 端点 */
        private String userIdUrl;

        /** 成员详情端点(姓名/头像/手机号) */
        private String userDetailUrl;
    }

    /** 短信通道配置 */
    @Getter
    @Setter
    public static class Sms {

        /** 开发态 mock 通道开关:发送动作同走网关留痕,手机号打码;生产 profile 无此键即 false */
        private boolean mock;

        /** 开发自测便利开关:打开后验证码随日志回显,仅限开发环境,默认关闭守住"码不落日志" */
        private boolean mockEchoCode;

        /** 阿里云 AccessKeyId;与 accessKeySecret 均空时走默认凭证链(环境变量 ALIBABA_CLOUD_ACCESS_KEY_ID/SECRET) */
        private String accessKeyId;

        /** 阿里云 AccessKeySecret:仅进 SDK 凭证,不进快照与日志 */
        private String accessKeySecret;

        /** 阿里云 Region(如 cn-chengdu):装配开关,共享 yml 注释态缺省 → 不装配 AsyncClient,短信通道降级 */
        private String region;

        /** 号码认证服务端点,缺省官方公网端点 */
        private String regionEndpoint = "dypnsapi.aliyuncs.com";

        /** 短信签名,须与阿里云报备一致 */
        private String signName;

        /** 验证码有效分钟数,报备模板含 ${min} 占位符时随参数下发 */
        private String codeValidMinutes = "5";
    }

    /** 邮件通道配置 */
    @Getter
    @Setter
    public static class Mail {

        /** 发件人地址,缺省回退 SMTP 账号自身(spring.mail.username) */
        private String from;
    }
}
