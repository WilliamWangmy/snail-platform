package com.snail.platform.config;

import com.snail.platform.function.external.client.ExternalCallLedger;
import com.snail.platform.function.external.client.MockSocialAuthClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 职责:开发态第三方扫码登录的 mock 装配——snail.external.oauth.mock=true 时按来源注册
 * 钉钉/飞书/企业微信三个 mock 端口实例,登录页三个入口在无厂商应用时全链可跑;
 * 生产 profile 缺省不装配,由三家真实客户端顶位。装配层住 start(与 CacheSpecConfig 同层),
 * external 模块自身不持 @Configuration(模块惯例)。
 * 上游:application-dev.yml 的 snail.external.oauth.mock;下游:auth 登录链按 source 匹配端口。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "snail.external.oauth.mock", havingValue = "true")
public class MockSocialAuthConfig {

    @Bean
    public MockSocialAuthClient dingtalkMockSocialAuthClient(
            ExternalCallLedger ledger,
            @Value("${snail.external.oauth.mock-mobile:13800000000}") String mockMobile) {
        return new MockSocialAuthClient(ledger, mockMobile, "dingtalk");
    }

    @Bean
    public MockSocialAuthClient feishuMockSocialAuthClient(
            ExternalCallLedger ledger,
            @Value("${snail.external.oauth.mock-mobile:13800000000}") String mockMobile) {
        return new MockSocialAuthClient(ledger, mockMobile, "feishu");
    }

    @Bean
    public MockSocialAuthClient wecomMockSocialAuthClient(
            ExternalCallLedger ledger,
            @Value("${snail.external.oauth.mock-mobile:13800000000}") String mockMobile) {
        return new MockSocialAuthClient(ledger, mockMobile, "wecom");
    }
}
