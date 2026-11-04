package com.snail.platform.function.external.fixture;

import jakarta.mail.internet.MimeMessage;

/**
 * 职责:ArchUnit 第三方 SDK 边界规则的豁免样例——包位落在 external 模块内,
 * 引用 jakarta.mail 不应立案;与违规样例配对,断言豁免口径按包位判定而不是按类型判定。
 * 只住测试源码,随违规样例一起由类文件导入器定点喂入。
 */
class ExternalMailFixture {

    /** external 模块内引用 jakarta.mail,合法,不应触发违规 */
    MimeMessage message() {
        return null;
    }
}
