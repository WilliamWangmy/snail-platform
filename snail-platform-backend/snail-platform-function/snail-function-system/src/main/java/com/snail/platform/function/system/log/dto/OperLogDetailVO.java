package com.snail.platform.function.system.log.dto;

import com.snail.platform.function.system.log.entity.SysOperLog;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 职责:操作日志详情——在列表面之外补 param/result 全文,排障与审计取证用。
 * 上游:OperLogService 按 ID 取行;下游:日志详情页。
 */
@Schema(description = "操作日志详情")
public record OperLogDetailVO(
        @Schema(description = "日志ID") Long id,
        @Schema(description = "归属租户") Long tenantId,
        @Schema(description = "功能模块") String module,
        @Schema(description = "业务类型") Integer businessType,
        @Schema(description = "Java 方法") String method,
        @Schema(description = "HTTP 方法") String requestMethod,
        @Schema(description = "操作人登录名") String operator,
        @Schema(description = "操作人组织ID") Long orgId,
        @Schema(description = "客户端IP") String ip,
        @Schema(description = "请求地址") String url,
        @Schema(description = "请求入参(已脱敏)") String param,
        @Schema(description = "响应出参") String result,
        @Schema(description = "结果:1成功 0失败") Integer status,
        @Schema(description = "失败原因") String errorMsg,
        @Schema(description = "耗时毫秒") Long costTime,
        @Schema(description = "链路ID") String traceId,
        @Schema(description = "操作时间") LocalDateTime operTime) {

    public static OperLogDetailVO of(SysOperLog log) {
        return new OperLogDetailVO(log.getId(), log.getTenantId(), log.getModule(), log.getBusinessType(),
                log.getMethod(), log.getRequestMethod(), log.getOperator(), log.getOrgId(),
                log.getIp(), log.getUrl(), log.getParam(), log.getResult(),
                log.getStatus(), log.getErrorMsg(), log.getCostTime(), log.getTraceId(), log.getOperTime());
    }
}
