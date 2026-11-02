package com.snail.platform.config;

import cn.hutool.crypto.digest.BCrypt;
import java.security.SecureRandom;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 职责:dev 环境下 admin 种子口令的一次性随机生成——SNAIL_SEED_ADMIN_PASSWORD 与
 * spring.flyway.placeholders 都未提供时,现场生成随机口令并打印,拒绝"admin/admin123 可预测登录"。
 * 上游:application-dev.yml 的空默认;下游:V4__seed_builtin_data.sql 的 ${seedAdminPassword} 占位。
 * 口径:占位已显式提供(环境变量或 yml)则原样保留,绝不覆盖部署方口令;
 * prod 的 ${SNAIL_SEED_ADMIN_PASSWORD} 无默认,缺失在属性解析期即启动失败,不进本组件的管辖。
 * 边界:库已初始化(V4 已执行)时本组件仍会生成并打印一次,此时该口令不生效——日志已注明"仅首次初始化时有效"。
 */
@Configuration
public class DevSeedPasswordConfig implements FlywayConfigurationCustomizer {

    private static final Logger log = LoggerFactory.getLogger(DevSeedPasswordConfig.class);

    /** 种子口令占位名,与 V4 迁移脚本一致 */
    private static final String PLACEHOLDER = "seedAdminPassword";

    /** 与业务侧 PasswordPolicy 同口径的字符池:大小写字母 + 数字 + 四个符号类 */
    private static final String LOWER = "abcdefghjkmnpqrstuvwxyz";
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String DIGIT = "23456789";
    private static final String SYMBOL = "!@#$%^&*";
    private static final String ALL = LOWER + UPPER + DIGIT + SYMBOL;
    private static final int PASSWORD_LENGTH = 12;

    private final Environment environment;

    public DevSeedPasswordConfig(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void customize(FluentConfiguration configuration) {
        String configured = configuration.getPlaceholders().get(PLACEHOLDER);
        if (configured != null && !configured.isBlank()) {
            return;
        }
        String random = randomPassword();
        java.util.Map<String, String> placeholders = new java.util.HashMap<>(configuration.getPlaceholders());
        placeholders.put(PLACEHOLDER, BCrypt.hashpw(random));
        configuration.placeholders(placeholders);
        log.warn(""
                + "============================================================\n"
                + "  dev 环境未配置 SNAIL_SEED_ADMIN_PASSWORD,已现场生成随机 admin 种子口令:\n"
                + "      admin / {}\n"
                + "  该口令仅在首次初始化空库(V4 迁移执行)时生效;库已初始化则忽略本行。\n"
                + "  建议用 SNAIL_SEED_ADMIN_PASSWORD(BCrypt 密文)固定开发库口令。\n"
                + "============================================================", random);
    }

    /** 四类字符各取其一 + 随机补足,SecureRandom 无模偏采样后 Fisher-Yates 洗牌 */
    private String randomPassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder(PASSWORD_LENGTH);
        password.append(randomChar(LOWER, random));
        password.append(randomChar(UPPER, random));
        password.append(randomChar(DIGIT, random));
        password.append(randomChar(SYMBOL, random));
        while (password.length() < PASSWORD_LENGTH) {
            password.append(randomChar(ALL, random));
        }
        // Fisher-Yates:保证四类保底之外每一位等概率
        for (int i = password.length() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char tmp = password.charAt(i);
            password.setCharAt(i, password.charAt(j));
            password.setCharAt(j, tmp);
        }
        return password.toString();
    }

    private char randomChar(String pool, SecureRandom random) {
        return pool.charAt(random.nextInt(pool.length()));
    }
}
