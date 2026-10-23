package com.snail.platform.framework.security.web;

import com.snail.platform.framework.core.response.R;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 职责:方法级鉴权拒绝的 HTTP 翻译处——@PreAuthorize 抛出的 AccessDeniedException 统一回 403 + R 信封。
 * 住在 security 而不是 framework-core:core 不能反向依赖 spring-security;@Order 抢在全局 500 兜底之前。
 * 上游:任意 Controller 方法上的 @PreAuthorize;下游:前端消费的 403 响应。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityExceptionHandler {

    /** 权限不足:与安全链入口点同文案,前端只认 HTTP 状态与 code */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<R<Void>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(R.fail(HttpStatus.FORBIDDEN.value(), "无权限访问"));
    }
}
