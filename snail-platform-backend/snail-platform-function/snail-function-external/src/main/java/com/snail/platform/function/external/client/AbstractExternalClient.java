package com.snail.platform.function.external.client;

import com.snail.platform.framework.core.context.LoginUserContextHolder;
import com.snail.platform.framework.core.context.TenantContextHolder;
import com.snail.platform.function.external.api.ExternalCallException;
import com.snail.platform.function.external.entity.SysExternalCallLog;
import com.snail.platform.framework.log.SensitiveDataMasker;
import java.util.function.Supplier;
import org.slf4j.MDC;

/**
 * 职责:第三方客户端模板方法单点——厂商适配器只写 doInvoke 的协议细节,
 * 计时、异常归一、快照脱敏截断、异步落账全部收口在这里,一次调用一行流水。
 * 快照在调用线程现取(traceId/operatorId/tenantId):invoke 与调用方同线程,上下文还在;
 * 真正落库在流水线程,只消费行内快照,不再回查任何上下文。
 * 上游:SmtpMailClient 等适配器;下游:ExternalCallLedger。
 */
public abstract class AbstractExternalClient {

    /** 流水终态:成功 */
    protected static final int STATUS_SUCCESS = 0;

    /** 流水终态:失败 */
    protected static final int STATUS_FAILED = 1;

    /** request/response 列宽,超长截断 */
    static final int SNAPSHOT_MAX = 2000;

    /** error 列宽,超长截断 */
    static final int ERROR_MAX = 500;

    /** 异步落账单点 */
    private final ExternalCallLedger ledger;

    protected AbstractExternalClient(ExternalCallLedger ledger) {
        this.ledger = ledger;
    }

    /** 厂商标识,落流水 vendor 列:smtp-mail/aliyun-sms/amap */
    protected abstract String vendor();

    /**
     * 模板方法:执行一次第三方调用并留痕。成功原样返回 supplier 的结果;
     * 任何异常归一为 ExternalCallException 上抛——送达承诺类端口(邮件/短信)让它继续传播,
     * 装饰信息类端口(IP 定位)在自己的实现里捕获降级,降级策略住端口契约不藏在这里。
     *
     * @param invocation 调用描述:动作/调用方/业务溯源/请求快照(客户端已自行做好地址打码)
     * @param call       实际调用;返回值作为响应摘要落流水(如 "bcc×3"),无摘要时返回 null
     */
    protected <T> T invoke(ExternalInvocation invocation, Supplier<T> call) {
        long start = System.nanoTime();
        String request = truncate(SensitiveDataMasker.mask(invocation.requestJson()), SNAPSHOT_MAX);
        try {
            T result = call.get();
            ledger.record(row(invocation, request, truncate(String.valueOf(result), SNAPSHOT_MAX),
                    STATUS_SUCCESS, costMs(start), null));
            return result;
        } catch (Exception e) {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            ledger.record(row(invocation, request, null, STATUS_FAILED, costMs(start), truncate(error, ERROR_MAX)));
            throw ExternalCallException.wrap(vendor(), invocation.action(), e);
        }
    }

    /** 行组装:上下文快照在调用线程现取,调用时间由落账线程写 create_time */
    private SysExternalCallLog row(ExternalInvocation invocation, String request, String response,
                                   int status, int costMs, String error) {
        SysExternalCallLog row = new SysExternalCallLog();
        row.setVendor(vendor());
        row.setAction(invocation.action());
        row.setCaller(invocation.caller() == null || invocation.caller().isBlank()
                ? "unknown" : invocation.caller());
        row.setBizType(invocation.bizType());
        row.setBizId(invocation.bizId());
        row.setRequest(request);
        row.setResponse(response);
        row.setStatus(status);
        row.setError(error);
        row.setCostMs(costMs);
        row.setTraceId(MDC.get("traceId"));
        row.setOperatorId(LoginUserContextHolder.userId());
        row.setTenantId(TenantContextHolder.get());
        return row;
    }

    private static int costMs(long startNanos) {
        return (int) Math.min((System.nanoTime() - startNanos) / 1_000_000, Integer.MAX_VALUE);
    }

    /** 按列宽截断,null 原样返回 */
    static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }

    /**
     * 把一段明文转成可嵌入 JSON 字符串的字面量(转义反斜杠/引号/控制字符)——
     * 客户端拼请求快照用;刻意不引 JSON 库:快照是给人排查看的摘要,不是机器契约。
     */
    static String jsonString(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
