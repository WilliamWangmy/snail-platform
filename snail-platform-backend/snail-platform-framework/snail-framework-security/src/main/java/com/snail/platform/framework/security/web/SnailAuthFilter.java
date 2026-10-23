package com.snail.platform.framework.security.web;

import com.snail.platform.framework.core.context.LoginUser;
import com.snail.platform.framework.core.context.LoginUserContextHolder;
import com.snail.platform.framework.core.context.TenantContextHolder;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.security.authority.UserAuthorityProvider;
import com.snail.platform.framework.security.login.LoginUserProvider;
import com.snail.platform.framework.security.token.JwtTokenService;
import com.snail.platform.framework.security.token.SnailPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 职责:把 Bearer 令牌换成三份上下文——SecurityContext 主体、TenantContextHolder 租户、
 * LoginUserContextHolder 登录人快照(人员/组织/租户/角色),请求收尾必清理。
 * 审计填充、操作日志与业务读取统一从登录人快照取操作人,不再维护独立的操作人 ThreadLocal。
 * 权限码经 UserAuthorityProvider 端口、登录人信息经 LoginUserProvider 端口按请求解析(实现侧缓存);
 * 端口缺省时登录人降级为令牌身份最小快照,权限只剩 authenticated。
 * 令牌无效不在此拒绝,只留匿名,拒绝由安全链入口点统一出 401。
 * 上游:SnailSecurityConfig 挂入链;下游:认证过的全部业务请求与 @PreAuthorize 判定。
 * 约束:不作为 Spring Bean 注册(否则会被容器再挂一遍全局 filter 链,双跑一次上下文覆盖)。
 */
public class SnailAuthFilter extends OncePerRequestFilter {

    /** 认证态角色前缀;当前只放 authenticated,业务权限码原样追加 */
    public static final String ROLE_AUTHORITY_PREFIX = "ROLE_";

    private final JwtTokenService jwtTokenService;

    /** 权限码端口;framework 单独使用时允许缺省,此时只保留 authenticated */
    private final ObjectProvider<UserAuthorityProvider> authorityProviders;

    /** 登录人富快照端口;缺省时降级为令牌身份最小快照 */
    private final ObjectProvider<LoginUserProvider> loginUserProviders;

    public SnailAuthFilter(JwtTokenService jwtTokenService,
                           ObjectProvider<UserAuthorityProvider> authorityProviders,
                           ObjectProvider<LoginUserProvider> loginUserProviders) {
        this.jwtTokenService = jwtTokenService;
        this.authorityProviders = authorityProviders;
        this.loginUserProviders = loginUserProviders;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                SnailPrincipal principal = null;
                try {
                    principal = jwtTokenService.verify(header.substring(7));
                } catch (BizException ignored) {
                    // 令牌不合法:保持匿名,是否拒绝交给授权规则
                }
                if (principal != null) {
                    // 权限解析要读租户托管表,必须先于 Provider 调用注入租户
                    TenantContextHolder.set(principal.tenantId());
                    LoginUserContextHolder.set(loginUserOf(principal));
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            principal, null, authoritiesOf(principal.userId()));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
            TenantContextHolder.clear();
            LoginUserContextHolder.clear();
        }
    }

    /** 登录人快照由业务实现补全(带缓存);端口缺省时只保留令牌身份,不让 framework 单用被业务模型绑死 */
    private LoginUser loginUserOf(SnailPrincipal principal) {
        LoginUserProvider provider = loginUserProviders.getIfAvailable();
        return provider != null ? provider.load(principal) : LoginUserProvider.minimal(principal);
    }

    /** authenticated 恒在,权限码去重后原样作为 GrantedAuthority;Provider 缺省时降级为仅认证 */
    private List<GrantedAuthority> authoritiesOf(Long userId) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        authorities.add(new SimpleGrantedAuthority(ROLE_AUTHORITY_PREFIX + "authenticated"));
        UserAuthorityProvider provider = authorityProviders.getIfAvailable();
        if (provider != null) {
            for (String permission : provider.authoritiesOf(userId)) {
                authorities.add(new SimpleGrantedAuthority(permission));
            }
        }
        return List.copyOf(authorities);
    }
}
