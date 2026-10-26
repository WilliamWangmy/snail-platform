package com.snail.platform.framework.core.web;

import cn.hutool.core.util.IdUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
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

    /**
     * 可沿用的传入 traceId 形状:字母数字与连字符,8-64 位。
     * 口径:头部是不可信输入,原样进 MDC 就等于把换行/控制字符写进日志行(伪造日志条目),
     * 也原样回写响应头;不合格的一律丢弃改用新生成的 id,不报错——追踪标识不值得为一个坏头拒绝请求。
     */
    private static final Pattern INCOMING_TRACE_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(TRACE_HEADER);
        String traceId = incoming != null && INCOMING_TRACE_ID.matcher(incoming).matches()
                ? incoming : IdUtil.fastSimpleUUID();
        MDC.put(TRACE_ID, traceId);
        response.setHeader(TRACE_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID);
        }
    }
}
