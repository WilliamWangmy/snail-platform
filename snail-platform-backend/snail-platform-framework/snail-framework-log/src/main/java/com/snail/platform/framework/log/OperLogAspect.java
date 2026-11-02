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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.beans.factory.DisposableBean;
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
 * error_msg 超 2000 截断;param/result 超 4000 截断——超长落库失败等于这条审计永久丢失,宁截不丢;
 * status 1 成功 0 失败。
 */
@Aspect
@Component
public class OperLogAspect implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(OperLogAspect.class);

    /** error_msg 列宽,超长截断 */
    private static final int ERROR_MSG_LIMIT = 2000;

    /** param/result 列宽上限,超长截断为大出参占位——审计宁缺内容不缺记录 */
    private static final int PARAM_RESULT_LIMIT = 4000;

    /** normalize 递归深度上限:自引用集合(list.add(list))不做防护会栈溢出在写操作主链上 */
    private static final int NORMALIZE_MAX_DEPTH = 8;

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

    /**
     * 优雅停机:JVM 正常退出(发布重启是常态)时排空队列,正常态下队列里最多 2000 条审计
     * 不应因 daemon 线程随主线程蒸发;限时 5 秒,排不空的部分随日志告警放弃。
     */
    @Override
    public void destroy() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("操作日志停机排空超时,丢弃剩余待落库留痕");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    /** 环绕留痕:请求线程采齐上下文与入参,业务返回或抛错后定格结果并异步落库,业务异常原样外抛 */
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
        String param = SensitiveDataMasker.mask(truncate(serializableArgs(joinPoint.getArgs()), PARAM_RESULT_LIMIT));

        try {
            Object result = joinPoint.proceed();
            submit(tenantId, traceId, operLog, method, requestMethod, operator, ip, url,
                    param, truncate(safeJson(result), PARAM_RESULT_LIMIT), 1, null, start);
            return result;
        } catch (Throwable ex) {
            submit(tenantId, traceId, operLog, method, requestMethod, operator, ip, url,
                    param, null, 0, StrUtil.maxLength(ExceptionUtil.getMessage(ex), ERROR_MSG_LIMIT), start);
            throw ex;
        }
    }

    /**
     * 整条记录在请求线程组装完才入队:耗时与操作时刻此刻定格,池线程只负责一次 insert。
     * sink 每次现查 ObjectProvider——function 侧实现缺席(纯框架冒烟上下文)时切面要能退化丢弃,
     * 而不是在构造期就绑死一个可能不存在的 Bean。
     */
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

    /** 列宽截断:null 直通,超长以省略号占位——param/result 落库失败等于审计永久丢失,宁截不丢 */
    private static String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit) + "…[超长截断]";
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
        Set<Object> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        return normalize(value, 0, seen);
    }

    private static Object normalize(Object value, int depth, Set<Object> seen) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        // 深度与自引用双防护:自引用集合(list.add(list))与超深嵌套在写操作主链上直接降级占位
        if (depth > NORMALIZE_MAX_DEPTH || !seen.add(value)) {
            return "[递归截断]";
        }
        try {
            Class<?> type = value.getClass();
            if (type.isRecord()) {
                Map<String, Object> map = new LinkedHashMap<>();
                for (RecordComponent component : type.getRecordComponents()) {
                    try {
                        map.put(component.getName(), normalize(component.getAccessor().invoke(value), depth + 1, seen));
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        map.put(component.getName(), "[不可读]");
                    }
                }
                return map;
            }
            if (value instanceof Iterable<?> iterable) {
                List<Object> list = new ArrayList<>();
                iterable.forEach(item -> list.add(normalize(item, depth + 1, seen)));
                return list;
            }
            if (type.isArray()) {
                return "[数组:" + type.getComponentType().getSimpleName() + "]";
            }
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((k, v) -> copy.put(String.valueOf(k), normalize(v, depth + 1, seen)));
                return copy;
            }
            return value;
        } finally {
            seen.remove(value);
        }
    }

    /** 出参同理:二进制响应体(导出)不进 TEXT 列,占位即可——文件内容本就不该落库 */
    private String safeJson(Object result) {
        if (result == null) {
            return null;
        }
        if (result instanceof byte[] || result instanceof Resource) {
            return "[二进制响应]";
        }
        if (result instanceof org.springframework.http.ResponseEntity<?> entity) {
            // 流式下载(文件/导出)走 ResponseEntity:只留状态行,响应体不序列化
            return "[HTTP " + entity.getStatusCode().value() + "]";
        }
        try {
            return SensitiveDataMasker.mask(JSONUtil.toJsonStr(normalize(result)));
        } catch (RuntimeException e) {
            return "[出参不可序列化:" + result.getClass().getSimpleName() + "]";
        }
    }
}
