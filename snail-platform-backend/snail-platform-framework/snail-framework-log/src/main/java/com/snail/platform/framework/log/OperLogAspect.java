package com.snail.platform.framework.log;

import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.servlet.JakartaServletUtil;
import cn.hutool.json.JSONUtil;
import com.snail.platform.framework.core.context.LoginUser;
import com.snail.platform.framework.core.context.LoginUserContextHolder;
import com.snail.platform.framework.core.context.TenantContextHolder;
import com.snail.platform.framework.core.web.TraceFilter;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindingResult;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

/**
 * 职责:@OperLog 标注方法的留痕采集——环绕通知内"请求线程取齐、异步线程落库":
 * 租户/登录人快照(操作人)/traceId/请求面/入参在 proceed 之前采完,结果或异常在 proceed 之后定格,
 * 整条 OperLogRecord 交给单线程池落库;队列满丢日志告警、落库异常只记应用日志,业务永不为审计阻塞或失败。
 * 上游:打标的 Controller 写操作;下游:OperLogSink 实现(function 侧)。
 * 约束:入参/出参先 JSON 化再过 SensitiveDataMasker;二进制响应体(导出流)不序列化,只记占位文案;
 * error_msg 超 2000 截断;status 1 成功 0 失败。
 */
@Aspect
@Component
public class OperLogAspect {

    private static final Logger log = LoggerFactory.getLogger(OperLogAspect.class);

    /** error_msg 列宽,超长截断 */
    private static final int ERROR_MSG_LIMIT = 2000;

    /** 落库出口;function 构件缺席(如纯框架冒烟上下文)时为 null,丢弃并告警 */
    private final ObjectProvider<OperLogSink> sinkProvider;

    /** 单线程落库池:审计写不追求吞吐,要的是不乱序不并发;有界队列宁丢勿堵 */
    private final ExecutorService executor;

    public OperLogAspect(ObjectProvider<OperLogSink> sinkProvider) {
        this.sinkProvider = sinkProvider;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2000),
                runnable -> {
                    Thread thread = new Thread(runnable, "snail-oper-log");
                    thread.setDaemon(true);
                    return thread;
                },
                (task, pool) -> log.warn("操作日志队列已满,丢弃一条留痕"));
    }

    @Around("@annotation(operLog)")
    public Object around(ProceedingJoinPoint joinPoint, OperLog operLog) throws Throwable {
        long start = System.currentTimeMillis();
        // —— 以下全部发生在请求线程:上下文、入参、请求面一次取齐 ——
        Long tenantId = TenantContextHolder.get();
        LoginUser operator = LoginUserContextHolder.get();
        String traceId = MDC.get(TraceFilter.TRACE_ID);
        String method = joinPoint.getSignature().getDeclaringType().getSimpleName()
                + "." + joinPoint.getSignature().getName();
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        String requestMethod = null;
        String url = null;
        String ip = null;
        if (attributes != null) {
            var request = attributes.getRequest();
            requestMethod = request.getMethod();
            url = StrUtil.maxLength(request.getRequestURI(), 255);
            ip = JakartaServletUtil.getClientIP(request);
        }
        String param = SensitiveDataMasker.mask(serializableArgs(joinPoint.getArgs()));

        try {
            Object result = joinPoint.proceed();
            submit(tenantId, traceId, operLog, method, requestMethod, operator, ip, url,
                    param, safeJson(result), 1, null, start);
            return result;
        } catch (Throwable ex) {
            submit(tenantId, traceId, operLog, method, requestMethod, operator, ip, url,
                    param, null, 0, StrUtil.maxLength(ExceptionUtil.getMessage(ex), ERROR_MSG_LIMIT), start);
            throw ex;
        }
    }

    private void submit(Long tenantId, String traceId, OperLog operLog, String method,
                        String requestMethod, LoginUser operator,
                        String ip, String url, String param, String result,
                        int status, String errorMsg, long start) {
        OperLogRecord record = new OperLogRecord(tenantId, traceId, operLog.module(),
                operLog.businessType().getCode(), method, requestMethod,
                operator == null ? null : operator.username(),
                operator == null ? null : operator.orgId(),
                ip, url, param, result, status, errorMsg,
                System.currentTimeMillis() - start, LocalDateTime.now());
        executor.execute(() -> {
            OperLogSink sink = sinkProvider.getIfAvailable();
            if (sink == null) {
                log.warn("未发现 OperLogSink 实现,丢弃模块[{}]的一条操作留痕", operLog.module());
                return;
            }
            try {
                sink.save(record);
            } catch (RuntimeException e) {
                log.error("操作日志落库失败: module={}, method={}", record.module(), record.method(), e);
            }
        });
    }

    /** Servlet 容器对象、绑定结果与文件流不是"入参事实",不入日志;其余逐个归一化后 JSON 化 */
    private String serializableArgs(Object[] args) {
        List<Object> kept = new ArrayList<>();
        for (Object arg : args) {
            if (arg instanceof ServletRequest || arg instanceof ServletResponse
                    || arg instanceof BindingResult || arg instanceof MultipartFile) {
                continue;
            }
            kept.add(normalize(arg));
        }
        if (kept.isEmpty()) {
            return "[]";
        }
        try {
            return JSONUtil.toJsonStr(kept);
        } catch (RuntimeException e) {
            StringBuilder names = new StringBuilder("[");
            for (Object arg : kept) {
                names.append(arg == null ? "null" : arg.getClass().getSimpleName()).append(' ');
            }
            return names.append(']').toString();
        }
    }

    /**
     * record → 组件名/值 的有序 Map 递归归一化。
     * 为什么必须自己做:平台 DTO 全线是 record(Hutool 5.8 的 JSON 按 JavaBean getter 取属性,
     * record 的访问器不带 get 前缀,直丢 JSONUtil 会得到空对象 {},脱敏与取证都落空);
     * isRecord/getRecordComponents 都是 Java 16 定稿 API,--release 17 门槛内。
     */
    private static Object normalize(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        Class<?> type = value.getClass();
        if (type.isRecord()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (RecordComponent component : type.getRecordComponents()) {
                try {
                    map.put(component.getName(), normalize(component.getAccessor().invoke(value)));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    map.put(component.getName(), "[不可读]");
                }
            }
            return map;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> list = new ArrayList<>();
            iterable.forEach(item -> list.add(normalize(item)));
            return list;
        }
        if (type.isArray()) {
            return "[数组:" + type.getComponentType().getSimpleName() + "]";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), normalize(v)));
            return copy;
        }
        return value;
    }

    /** 出参同理:二进制响应体(导出)不进 TEXT 列,占位即可——文件内容本就不该落库 */
    private String safeJson(Object result) {
        if (result == null) {
            return null;
        }
        if (result instanceof byte[] || result instanceof Resource) {
            return "[二进制响应]";
        }
        try {
            return SensitiveDataMasker.mask(JSONUtil.toJsonStr(normalize(result)));
        } catch (RuntimeException e) {
            return "[出参不可序列化:" + result.getClass().getSimpleName() + "]";
        }
    }
}
