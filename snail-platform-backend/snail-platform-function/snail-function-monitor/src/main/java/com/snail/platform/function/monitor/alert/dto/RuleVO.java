package com.snail.platform.function.monitor.alert.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 职责:告警规则出参契约(主方案 §5.2,冻结)——另带 isSystem/silenceUntil/审计列。
 */
@Schema(description = "告警规则")
public record RuleVO(

        @Schema(description = "规则 ID")
        Long ruleId,

        @Schema(description = "规则名称")
        String name,

        @Schema(description = "指标键")
        String metric,

        @Schema(description = "比较符")
        String comparator,

        @Schema(description = "阈值")
        Double threshold,

        @Schema(description = "持续点数")
        Integer durationPoints,

        @Schema(description = "级别")
        String level,

        @Schema(description = "开关:0 启用 1 停用")
        Integer enabled,

        @Schema(description = "内置规则标识")
        Integer isSystem,

        @Schema(description = "静默截止时间")
        LocalDateTime silenceUntil,

        @Schema(description = "备注")
        String remark,

        @Schema(description = "创建人用户 ID")
        Long createBy,

        @Schema(description = "创建时间")
        LocalDateTime createTime,

        @Schema(description = "修改时间")
        LocalDateTime updateTime) {
}
