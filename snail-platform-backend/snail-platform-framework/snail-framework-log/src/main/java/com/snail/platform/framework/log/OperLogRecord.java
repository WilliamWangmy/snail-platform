package com.snail.platform.framework.log;

import java.time.LocalDateTime;

/**
 * 职责:操作日志一条留痕的框架侧模型——字段口径与 sys_oper_log 表对齐,但它是 framework-log 的出参,
 * 不是任何表的 PO;落库实现(function 侧 OperLogSink)负责映射,框架层因此不反向依赖业务构件。
 * 上游:OperLogAspect 在请求线程采齐后交给异步线程;下游:OperLogSink 实现方。
 * 约束:tenantId/operator/orgId 等快照必须是请求线程取好的值——异步线程没有任何上下文。
 * status 口径:1 成功 0 失败(与 sys_oper_log 一致,和业务表的 0 正常相反,别记串)。
 */
public record OperLogRecord(Long tenantId, String traceId, String module, int businessType,
                            String method, String requestMethod, String operator, Long orgId,
                            String ip, String url, String param, String result,
                            Integer status, String errorMsg, Long costTime, LocalDateTime operTime) {
}
