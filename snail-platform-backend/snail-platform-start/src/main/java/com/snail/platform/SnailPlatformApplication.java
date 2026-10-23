package com.snail.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 平台唯一启动类。
 *
 * <p>职责：作为整棵后端的装配入口，把 {@code com.snail.platform} 下的 framework 构件与
 * function 模块一次扫全；不承载任何业务语义，也不放 {@code @Bean}——横切能力一律由
 * framework 侧的自动配置提供，避免装配点变成第二处配置来源。
 *
 * <p>上下游：上游是 {@code snail-platform-start} 的 {@code application*.yml} 与
 * {@code logback-spring.xml}；下游是被它扫入的 {@code snail-framework-*} 与
 * {@code snail-function-*}，后两者只以包级契约（各模块 {@code api}）互相调用。
 */
@SpringBootApplication(scanBasePackages = "com.snail.platform")
public class SnailPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(SnailPlatformApplication.class, args);
    }
}
