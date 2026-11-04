package com.snail.platform.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 职责:第三方 SDK 边界规则的自检——用一个故意违规的样例类与一个豁免样例类,
 * 断言"规则会立案"且"豁免按包位判定"。规则本身写错而静默通过,比没有规则更危险,
 * 所以规则要拿样例验证,不只靠主套件零违规的弱信号。
 * 上游:ArchitectureRulesTest.thirdPartySdkViolations;下游:mvn test 常规构建。
 */
class ThirdPartySdkRuleTest {

    @Test
    void outOfModuleSdkUsageFiresAndExternalExemptHolds() {
        var classes = new ClassFileImporter().importPackages(
                "com.snail.platform.architecture.fixture",
                "com.snail.platform.function.external.fixture");
        List<String> violations = ArchitectureRulesTest.thirdPartySdkViolations(classes);

        assertTrue(violations.stream().anyMatch(v -> v.contains("MailSdkFixture")),
                "external 模块外引用 jakarta.mail 必须立案,规则疑似失效:\n" + String.join("\n", violations));
        assertFalse(violations.stream().anyMatch(v -> v.contains("ExternalMailFixture")),
                "external 模块内的 SDK 引用被误报,豁免口径破坏:\n" + String.join("\n", violations));
    }
}
