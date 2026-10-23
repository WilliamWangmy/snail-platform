package com.snail.platform.framework.security.web;

import com.snail.platform.framework.core.context.AuditActorHolder;
import com.snail.platform.framework.core.context.OperatorContextHolder;
import com.snail.platform.framework.core.context.TenantContextHolder;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.security.token.JwtTokenService;
import com.snail.platform.framework.security.token.SnailPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 职责:把 Bearer 令牌换成四份上下文——SecurityContext 主体、TenantContextHolder 租户、
 * AuditActorHolder 操作人 ID、OperatorContextHolder 操作人展示快照,请求收尾必清理。令牌无效不在此拒绝,只留匿名,拒绝由安全链的入口点统一出 401。
 * 上游:SnailSecurityConfig 挂入链;下游:认证过的全部业务请求。
 * 约束:不作为 Spring Bean 注册(否则会被容器再挂一遍全局 filter 链,双跑一次上下文覆盖)。
 */
public class SnailAuthFilter extends OncePerRequestFilter {

    /** 令牌载荷里的角色码,认证后直接给到权限判定(scope 之外的功能权限消费点) */
    public static final String ROLE_AUTHORITY_PREFIX = "ROLE_";

    private final JwtTokenService jwtTokenService;

    public SnailAuthFilter(JwtTokenService jwtTokenService) {
        this.jwtTokenService = jwtTokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                try {
                    SnailPrincipal principal = jwtTokenService.verify(header.substring(7));
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            principal, null, List.of(new SimpleGrantedAuthority(ROLE_AUTHORITY_PREFIX + "authenticated")));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    TenantContextHolder.set(principal.tenantId());
                    AuditActorHolder.set(principal.userId());
                    OperatorContextHolder.set(new OperatorContextHolder.Operator(
                            principal.userId(), principal.username(), principal.orgId()));
                } catch (BizException ignored) {
                    // 令牌不合法:保持匿名,是否拒绝交给授权规则
                }
            }
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
            TenantContextHolder.clear();
            AuditActorHolder.clear();
            OperatorContextHolder.clear();
        }
    }
}
