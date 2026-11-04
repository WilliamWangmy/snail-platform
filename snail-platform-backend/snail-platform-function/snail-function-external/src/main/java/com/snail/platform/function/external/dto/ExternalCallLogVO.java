package com.snail.platform.function.external.dto;

import com.snail.platform.function.external.entity.SysExternalCallLog;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 职责:第三方调用流水分页出参——流水行的字段投影,不含逻辑。
 * request/response 是已脱敏截断的快照,原样透出;无详情端点,列表行就是全行(与登录日志同口径)。
 * 上游:ExternalCallLogService;下游:第三方调用页面。
 */
@Schema(description = "第三方调用流水行")
public record ExternalCallLogVO(

        @Schema(description = "调用 ID")
        Long callId,

        @Schema(description = "厂商标识")
        String vendor,

        @Schema(description = "动作标识")
        String action,

        @Schema(description = "调用方标识")
        String caller,

        @Schema(description = "业务域标签")
        String bizType,

        @Schema(description = "关联业务 ID")
        Long bizId,

        @Schema(description = "请求快照(脱敏+截断)")
        String request,

        @Schema(description = "响应摘要(脱敏+截断)")
        String response,

        @Schema(description = "终态,0 成功 1 失败")
        Integer status,

        @Schema(description = "失败原因")
        String error,

        @Schema(description = "耗时毫秒")
        Integer costMs,

        @Schema(description = "链路 ID")
        String traceId,

        @Schema(description = "触发人用户 ID 快照")
        Long operatorId,

        @Schema(description = "租户快照")
        Long tenantId,

        @Schema(description = "调用时间")
        java.time.LocalDateTime createTime) {

    /** 纯字段投影,不含逻辑 */
    public static ExternalCallLogVO of(SysExternalCallLog row) {
        return new ExternalCallLogVO(row.getCallId(), row.getVendor(), row.getAction(), row.getCaller(),
                row.getBizType(), row.getBizId(), row.getRequest(), row.getResponse(),
                row.getStatus(), row.getError(), row.getCostMs(), row.getTraceId(),
                row.getOperatorId(), row.getTenantId(), row.getCreateTime());
    }
}
