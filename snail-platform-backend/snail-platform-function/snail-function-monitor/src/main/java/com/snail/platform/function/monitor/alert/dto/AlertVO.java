package com.snail.platform.function.monitor.alert.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 职责:告警行出参契约(主方案 §5.2 告警字段,冻结)。
 */
@Schema(description = "监控告警")
public record AlertVO(

        @Schema(description = "告警 ID")
        Long alertId,

        @Schema(description = "规则 ID")
        Long ruleId,

        @Schema(description = "规则名称快照")
        String ruleName,

        @Schema(description = "指标键快照")
        String metric,

        @Schema(description = "级别快照")
        String level,

        @Schema(description = "状态:firing/acked/resolved")
        String status,

        @Schema(description = "最近触发值")
        Double triggerValue,

        @Schema(description = "阈值快照")
        Double threshold,

        @Schema(description = "比较符快照")
        String comparator,

        @Schema(description = "首次触发时间")
        LocalDateTime firstTime,

        @Schema(description = "最近满足时间")
        LocalDateTime lastTime,

        @Schema(description = "恢复时间")
        LocalDateTime resolveTime,

        @Schema(description = "确认人")
        String ackBy,

        @Schema(description = "确认时间")
        LocalDateTime ackTime,

        @Schema(description = "规则当前静默截止时间(实读规则表)")
        LocalDateTime silenceUntil) {
}
