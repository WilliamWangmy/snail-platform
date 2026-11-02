package com.snail.platform.framework.security;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 职责:认证构件的配置单点。密钥与有效期在 yml 声明,生产用环境变量覆盖,代码不落真实口令;
 * 跨域来源默认留空(不启用 CORS),只在前后端分域部署时按名白名单放开。
 * 上游:application*.yml 的 snail.security 段;下游:SnailSecurityConfig、JwtTokenService。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "snail.security")
public class SnailSecurityProperties {

    /** JWT 签发配置 */
    private final Jwt jwt = new Jwt();

    /** 跨域配置 */
    private final Cors cors = new Cors();

    /** 口令回传加密配置 */
    private final PasswordTransfer passwordTransfer = new PasswordTransfer();

    @Getter
    @Setter
    public static class PasswordTransfer {

        /** AES-256-GCM 共享密钥(Base64,解码后须 32 字节);前端用同值解密展示,缺配置拒绝启动 */
        private String key;
    }

    @Getter
    @Setter
    public static class Cors {

        /** 允许的前端来源(如 https://admin.example.com),逗号分隔;留空即不启用 CORS——同源部署与开发期代理都不需要 */
        private List<String> allowedOrigins = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class Jwt {

        /** HS256 签名密钥,至少 32 字符;启动即校验,短了就拒绝启动而不是运行到签发才炸 */
        private String secret;

        /** 令牌有效期(分钟);正数校验在 JwtTokenService 构造期,与密钥校验同点拒绝 */
        private long expireMinutes = 120;
    }
}
