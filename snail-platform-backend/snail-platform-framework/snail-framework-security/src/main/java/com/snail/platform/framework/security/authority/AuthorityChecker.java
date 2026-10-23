package com.snail.platform.framework.security.authority;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 职责:非 Controller 层的权限码判定入口——Service 需要按平台面能力分叉时读当前认证,不自己翻角色表。
 * 上游:function 模块的 Service(如同步日志的跨租户视图);下游:SecurityContext 中的权限码集合。
 * 与 @PreAuthorize 的分工:HTTP 入口用注解,进入 Service 后的数据面判定用本类。
 */
public final class AuthorityChecker {

    private AuthorityChecker() {
    }

    /** 当前请求是否持有指定权限码;未认证或无该码一律 false */
    public static boolean has(String authority) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authority == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority()));
    }
}
