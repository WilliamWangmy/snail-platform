package com.snail.platform.framework.security.web;

import com.snail.platform.framework.core.context.LoginUser;
import com.snail.platform.framework.core.context.LoginUserContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 职责:强制改密的后端兜底闸——待改密标记用户的请求只放行改密/登出/本人信息三条,
 * 其余业务接口一律 403"请先完成强制改密",防止前端绕过直接打接口。
 * 挂在 SnailAuthFilter 之后:判定输入就是它刚装好的登录人快照,匿名请求(无令牌/令牌无效)
 * 不进本闸,仍由安全链入口点出 401。
 * 上游:SnailSecurityConfig addFilterAfter 挂入;下游:放行集内的三个端点与 ForcePasswordChangeFilter 之外的授权判定。
 * 约束:不作为 Spring Bean 注册(同 SnailAuthFilter,否则会被容器再挂一遍全局 filter 链);
 * 放行集是 (方法,路径) 精确匹配,不收模式——口径改动须与前端路由守卫同步评审。
 */
public class ForcePasswordChangeFilter extends OncePerRequestFilter {

    /** 待改密会话的放行集:执行改密、退出登录、恢复会话态所需的只读本人信息 */
    private static final Set<String> ALLOWED = Set.of(
            "POST /system/profile/password/force-change",
            "POST /system/auth/logout",
            "GET /system/auth/me");

    /** 拒绝文案:前端据此识别"先去改密"分支 */
    private static final String MESSAGE = "请先完成强制改密";

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        // 同 SnailAuthFilter:异步派发重建同一份认证上下文,兜底闸不豁免,保持覆盖面一致
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        LoginUser snapshot = LoginUserContextHolder.get();
        if (snapshot != null && snapshot.passwordChangeRequired()
                && !ALLOWED.contains(request.getMethod() + " " + request.getRequestURI())) {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"message\":\"" + MESSAGE + "\",\"data\":null}");
            return;
        }
        chain.doFilter(request, response);
    }
}
