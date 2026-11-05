package com.snail.platform.framework.security.config;

import com.snail.platform.framework.security.SnailSecurityProperties;
import com.snail.platform.framework.security.authority.UserAuthorityProvider;
import com.snail.platform.framework.security.login.LoginUserProvider;
import com.snail.platform.framework.security.heartbeat.SessionHeartbeatPort;
import com.snail.platform.framework.security.token.JwtTokenService;
import com.snail.platform.framework.security.web.ForcePasswordChangeFilter;
import com.snail.platform.framework.security.web.SnailAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 职责:HTTP 安全链的唯一装配——无状态 JWT、白名单放行、其余一律认证;401/403 输出 R 同构 JSON。
 * 白名单只收认证入口与接口文档路径,业务端点不允许加进来。
 * 权限码两层:METHOD 级 @PreAuthorize 判接口准入,过滤器经 UserAuthorityProvider 注入当前用户权限码。
 * 上游:framework-security 的令牌服务、认证过滤器与权限端口;下游:全部 HTTP 请求。
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(SnailSecurityProperties.class)
public class SnailSecurityConfig {

    /** 认证入口白名单;登录相关窄开口,别扩 */
    private static final String[] AUTH_WHITELIST = {
            "/system/auth/captcha",
            "/system/auth/login",
            // 短信三端点与第三方扫码端点同为认证入口:方法级 permitAll 不被 HTTP 授权层感知,必须在此放行
            "/system/auth/sms-captcha",
            "/system/auth/login-sms",
            "/system/auth/login-sms/pick",
            "/system/auth/oauth/**"
    };

    /** 接口文档白名单:仅在 springdoc 实际启用时拼入,生产忘关文档端点也不会裸奔 */
    private static final String[] DOC_WHITELIST = {
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    /** 令牌服务:构造期即校验密钥长度,配置不合格在这里拒绝启动 */
    @Bean
    public JwtTokenService jwtTokenService(SnailSecurityProperties properties) {
        return new JwtTokenService(properties);
    }

    /** 安全链:无状态、白名单放行、其余认证;认证过滤器在前,强制改密闸在其后 */
    @Bean
    public SecurityFilterChain snailSecurityFilterChain(HttpSecurity http, SnailSecurityProperties properties,
                                                        JwtTokenService jwtTokenService,
                                                        ObjectProvider<UserAuthorityProvider> authorityProviders,
                                                        ObjectProvider<LoginUserProvider> loginUserProviders,
                                                        ObjectProvider<SessionHeartbeatPort> heartbeats,
                                                        Environment environment)
            throws Exception {
        List<String> whitelist = new ArrayList<>(List.of(AUTH_WHITELIST));
        if (environment.getProperty("springdoc.api-docs.enabled", Boolean.class, true)) {
            whitelist.addAll(List.of(DOC_WHITELIST));
        }
        http.csrf(csrf -> csrf.disable())
                .cors(cors -> {
                    CorsConfigurationSource corsSource = corsSource(properties.getCors());
                    if (corsSource != null) {
                        cors.configurationSource(corsSource);
                    }
                })
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // 一条覆盖全部方法即可:GET 一条是冗余的白名单分裂,保持单点便于审计
                        .requestMatchers(whitelist.toArray(String[]::new)).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new SnailAuthFilter(jwtTokenService, authorityProviders, loginUserProviders, heartbeats),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new ForcePasswordChangeFilter(), SnailAuthFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) ->
                                writeJson(response, 401, "未认证或登录已过期"))
                        .accessDeniedHandler((request, response, ex) ->
                                writeJson(response, 403, "无权限访问")));
        return http.build();
    }

    /**
     * 跨域来源白名单装配:未配置即返回 null,过滤器链保持不下发 CORS 头。
     * 口径:令牌走 Authorization 头而不是 Cookie,故 allowCredentials 保持默认 false,
     * 来源必须逐名写全(不给 *),避免任意站点借无凭证跨域打到内网管理端。
     */
    private static CorsConfigurationSource corsSource(SnailSecurityProperties.Cors corsProperties) {
        if (corsProperties.getAllowedOrigins().isEmpty()) {
            return null;
        }
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.getAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /** 入口点直接吐与 R 同构的最小 JSON,避免为一行响应引序列化器 */
    private void writeJson(HttpServletResponse response, int code, String message) throws java.io.IOException {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}");
    }
}
