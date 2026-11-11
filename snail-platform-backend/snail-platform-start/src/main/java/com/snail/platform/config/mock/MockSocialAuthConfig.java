package com.snail.platform.config.mock;

import com.snail.platform.function.external.config.SnailExternalProperties;
import com.snail.platform.function.external.client.ExternalCallLedger;
import com.snail.platform.function.external.client.mock.MockSocialAuthClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 职责:开发态第三方扫码登录的 mock 装配——snail.external.oauth.mock=true 时按来源注册
 * 钉钉/飞书/企业微信三个 mock 端口实例,登录页三个入口在无厂商应用时全链可跑;
 * 生产 profile 缺省不装配,由三家真实客户端顶位。装配层住 start(与 CacheSpecConfig 同层),
 * external 模块自身不持 @Configuration(模块惯例);mock 客户端统一住 external 的
 * client.mock 子包,本类住 config.mock 子包——mock 相关代码全部归拢到各自的 mock 文件夹。
 * 上游:application-dev.yml 的 snail.external.oauth 段;下游:auth 登录链按 source 匹配端口。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "snail.external.oauth.mock", havingValue = "true")
public class MockSocialAuthConfig {

    @Bean
    public MockSocialAuthClient dingtalkMockSocialAuthClient(ExternalCallLedger ledger,
                                                             SnailExternalProperties props) {
        return new MockSocialAuthClient(ledger, props.getOauth().getMockMobile(), "dingtalk");
    }

    @Bean
    public MockSocialAuthClient feishuMockSocialAuthClient(ExternalCallLedger ledger,
                                                           SnailExternalProperties props) {
        return new MockSocialAuthClient(ledger, props.getOauth().getMockMobile(), "feishu");
    }

    @Bean
    public MockSocialAuthClient wecomMockSocialAuthClient(ExternalCallLedger ledger,
                                                          SnailExternalProperties props) {
        return new MockSocialAuthClient(ledger, props.getOauth().getMockMobile(), "wecom");
    }
}
