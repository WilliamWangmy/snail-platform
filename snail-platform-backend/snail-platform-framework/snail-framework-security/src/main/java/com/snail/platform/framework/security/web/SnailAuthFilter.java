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
import com.snail.platform.framework.security.heartbeat.SessionHeartbeatPort;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Objects;
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

    /** 在线心跳端口;缺省时跳过,在线观测不影响认证主链 */
    private final ObjectProvider<SessionHeartbeatPort> heartbeats;

    public SnailAuthFilter(JwtTokenService jwtTokenService,
                           ObjectProvider<UserAuthorityProvider> authorityProviders,
                           ObjectProvider<LoginUserProvider> loginUserProviders,
                           ObjectProvider<SessionHeartbeatPort> heartbeats) {
        this.jwtTokenService = jwtTokenService;
        this.authorityProviders = authorityProviders;
        this.loginUserProviders = loginUserProviders;
        this.heartbeats = heartbeats;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        // 上下文全部派生自请求本身(无会话态),异步派发(如 StreamingResponseBody 下载)重建同一份
        // 认证上下文交授权规则重判;默认跳过会让异步派发变匿名,响应已提交时安全异常无法落笔
        return false;
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
                    LoginUserProvider provider = loginUserProviders.getIfAvailable();
                    LoginUser snapshot = provider != null ? provider.load(principal)
                            : LoginUserProvider.minimal(principal);
                    if (sessionValid(provider != null, principal, snapshot)) {
                        LoginUserContextHolder.set(snapshot);
                        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                principal, null, authoritiesOf(principal.userId()));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                        SessionHeartbeatPort heartbeat = heartbeats.getIfAvailable();
                        if (heartbeat != null) {
                            // 在线心跳是辅助观测:实现侧自行容错,失败不影响认证主链
                            heartbeat.beat(principal, request.getRemoteAddr(), request.getHeader("User-Agent"));
                        }
                    }
                    // 快照无版本(用户已删/停用)跳过比对;版本不一致视同令牌已被吊销,保持匿名交授权规则出 401
                }
            }
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
            TenantContextHolder.clear();
            LoginUserContextHolder.clear();
        }
    }

    /**
     * 会话有效性:解析端口缺席时按降级口径放行(framework 单用不绑业务模型);
     * 端口在场而快照无版本,即用户已删/停用——整体 401 而非降级为"仅认证";
     * 版本与令牌 ver 声明不一致即已吊销(改密/重置/停用/踢人)。
     */
    private boolean sessionValid(boolean providerPresent, SnailPrincipal principal, LoginUser snapshot) {
        if (!providerPresent) {
            return true;
        }
        if (snapshot.tokenVersion() == null) {
            return false;
        }
        return Objects.equals(principal.tokenVersion(), snapshot.tokenVersion());
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
