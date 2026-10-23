package com.snail.platform.framework.core.web;

import cn.hutool.core.util.IdUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 职责:每个 HTTP 请求一个 traceId——请求头带来就沿用,没有就生成;写入 MDC(键 traceId,
 * 与 logback-spring.xml 的 %X{traceId} 对齐)并回写响应头,供操作日志与排障互查。
 * 上游:进入应用的一切 HTTP 流量;下游:应用日志、snail-framework-log(后续阶段消费)。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {

    /** MDC 键,与 logback pattern 的 %X{traceId} 是同一条口径,两处只能一起改 */
    public static final String TRACE_ID = "traceId";

    /** 跨组件传递用的请求/响应头名 */
    public static final String TRACE_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(TRACE_HEADER);
        String traceId = incoming != null && !incoming.isBlank() ? incoming : IdUtil.fastSimpleUUID();
        MDC.put(TRACE_ID, traceId);
        response.setHeader(TRACE_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID);
        }
    }
}
