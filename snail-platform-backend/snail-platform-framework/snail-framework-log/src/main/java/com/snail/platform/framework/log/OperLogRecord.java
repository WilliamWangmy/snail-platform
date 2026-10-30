package com.snail.platform.framework.log;

import java.time.LocalDateTime;

/**
 * 职责:操作日志一条留痕的框架侧模型——字段口径与 sys_oper_log 表对齐,但它是 framework-log 的出参,
 * 不是任何表的 PO;落库实现(function 侧 OperLogSink)负责映射,框架层因此不反向依赖业务构件。
 * 上游:OperLogAspect 在请求线程采齐后交给异步线程;下游:OperLogSink 实现方。
 * 约束:tenantId/operator/orgId 等快照必须是请求线程取好的值——异步线程没有任何上下文。
 * status 口径:1 成功 0 失败(与 sys_oper_log 一致,和业务表的 0 正常相反,别记串)。
 */
public record OperLogRecord(
        /** 所属租户;请求线程快照,异步线程不可再取 */
        Long tenantId,
        /** 请求链路标识,与日志 MDC 的 traceId 同值 */
        String traceId,
        /** @OperLog.module 声明的功能模块 */
        String module,
        /** 业务类型码,见 BusinessType */
        int businessType,
        /** 被调用的类名.方法名 */
        String method,
        /** HTTP 方法;非 HTTP 触发为 null */
        String requestMethod,
        /** 操作人登录名快照 */
        String operator,
        /** 操作人组织 ID 快照 */
        Long orgId,
        /** 客户端 IP */
        String ip,
        /** 请求 URI,超长已截断 */
        String url,
        /** 脱敏后的入参 JSON */
        String param,
        /** 脱敏后的出参 JSON;二进制响应只记占位文案 */
        String result,
        /** 1 成功 0 失败(与业务表的 0 正常相反) */
        Integer status,
        /** 失败原因,超长已截断;成功为 null */
        String errorMsg,
        /** 方法耗时(毫秒) */
        Long costTime,
        /** 操作发生时刻 */
        LocalDateTime operTime) {
}
