package com.snail.platform.function.system.log.dto;

import com.snail.platform.function.system.log.entity.SysOperLog;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 职责:操作日志列表行——param/result 全文只出现在详情,列表带筛选与状态所需的最小面。
 * 上游:OperLogService 分页投影;下游:日志列表页面。
 */
@Schema(description = "操作日志列表行")
public record OperLogListVO(
        @Schema(description = "日志ID") Long id,
        @Schema(description = "归属租户,平台侧列表用于辨识来源") Long tenantId,
        @Schema(description = "功能模块") String module,
        @Schema(description = "业务类型:1新增 2修改 3删除 4导出 5导入 9其他") Integer businessType,
        @Schema(description = "Java 方法") String method,
        @Schema(description = "HTTP 方法") String requestMethod,
        @Schema(description = "操作人登录名") String operator,
        @Schema(description = "操作人组织ID") Long orgId,
        @Schema(description = "客户端IP") String ip,
        @Schema(description = "请求地址") String url,
        @Schema(description = "结果:1成功 0失败") Integer status,
        @Schema(description = "失败原因") String errorMsg,
        @Schema(description = "耗时毫秒") Long costTime,
        @Schema(description = "链路ID") String traceId,
        @Schema(description = "操作时间") LocalDateTime operTime) {

    public static OperLogListVO of(SysOperLog log) {
        return new OperLogListVO(log.getId(), log.getTenantId(), log.getModule(), log.getBusinessType(),
                log.getMethod(), log.getRequestMethod(), log.getOperator(), log.getOrgId(),
                log.getIp(), log.getUrl(), log.getStatus(), log.getErrorMsg(),
                log.getCostTime(), log.getTraceId(), log.getOperTime());
    }
}
