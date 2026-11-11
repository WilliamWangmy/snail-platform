package com.snail.platform.config;

import com.snail.platform.function.auth.SnailAuthProperties;
import com.snail.platform.function.external.config.SnailExternalProperties;
import com.snail.platform.function.notify.SnailNotifyProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 职责:function 模块配置类的注册单点——external 凭据(snail.external)、auth 开关与白名单
 * (snail.auth)、notify 开关与模板(snail.notify)三个类型化配置类在此统一启用;
 * 各 function 模块按惯例不持 @Configuration,装配与注册层住 start(与 CacheSpecConfig 同层)。
 * 上游:application*.yml 的 snail.auth/notify/external 段;下游:各模块凭据与开关消费方。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SnailExternalProperties.class, SnailAuthProperties.class,
        SnailNotifyProperties.class})
public class SnailPropertiesConfig {
}
