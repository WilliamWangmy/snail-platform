package com.snail.platform.function.monitor.config;

import com.snail.platform.framework.core.response.R;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * 职责:监控总闸——snail.monitor.enabled=false 时 /monitor/** 一律 200 + R.fail(400,「监控功能未开放」),
 * 已登录请求才见得到关闸信息(未认证请求由 JWT 链先行 401,注册顺序见 MonitorAutoConfiguration)。
 * 上游:MonitorAutoConfiguration 的 FilterRegistrationBean(order 0);下游:放行后由各 Monitor*Controller 处理。
 * 口径:业务拒绝走 HTTP 200 + 非 0 code(契约 §0.3);不新增错误码段,400 即可;
 * 序列化用 tools.jackson(Boot 4 的 Jackson 3,DD-08 同源口径),一次性写响应,不走 GlobalExceptionHandler。
 */
public class MonitorGateFilter extends OncePerRequestFilter {

    /** 总闸配置(enabled=false 才拦) */
    private final MonitorProperties properties;

    /** Jackson 3 序列化,与平台信封同栈 */
    private final ObjectMapper objectMapper;

    public MonitorGateFilter(MonitorProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!properties.isEnabled()) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json;charset=UTF-8");
            byte[] body = objectMapper.writeValueAsBytes(R.fail(400, "监控功能未开放"));
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
            response.getOutputStream().flush();
            return;
        }
        chain.doFilter(request, response);
    }
}
