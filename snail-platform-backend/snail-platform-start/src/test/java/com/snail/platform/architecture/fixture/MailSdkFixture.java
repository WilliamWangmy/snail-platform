package com.snail.platform.architecture.fixture;

import jakarta.mail.internet.MimeMessage;

/**
 * 职责:ArchUnit 第三方 SDK 边界规则的违规样例——住在 external 模块之外却引用 jakarta.mail,
 * 专门用来断言规则会立案(规则本身写错静默通过比没有规则更危险)。
 * 只住测试源码:主规则按 DoNotIncludeTests 跑看不见它,样例测试用类文件导入器定点喂入。
 */
class MailSdkFixture {

    /** 引用 jakarta.mail 类型,即触发第三方 SDK 越界 */
    MimeMessage message() {
        return null;
    }
}
