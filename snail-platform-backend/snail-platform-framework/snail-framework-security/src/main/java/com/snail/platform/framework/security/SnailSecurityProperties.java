package com.snail.platform.framework.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 职责:认证构件的配置单点。密钥与有效期在 yml 声明,生产用环境变量覆盖,代码不落真实口令。
 * 上游:application*.yml 的 snail.security 段;下游:SnailSecurityConfig、JwtTokenService。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "snail.security")
public class SnailSecurityProperties {

    /** JWT 签发配置 */
    private final Jwt jwt = new Jwt();

    @Getter
    @Setter
    public static class Jwt {

        /** HS256 签名密钥,至少 32 字符;启动即校验,短了就拒绝启动而不是运行到签发才炸 */
        private String secret;

        /** 令牌有效期(分钟) */
        private long expireMinutes = 120;
    }
}
