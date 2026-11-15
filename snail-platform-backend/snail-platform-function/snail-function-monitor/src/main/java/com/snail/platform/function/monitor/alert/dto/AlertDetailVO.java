package com.snail.platform.function.monitor.alert.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 职责:告警详情出参契约——告警行 + 规则当前快照(规则已删则 null)+ 触发序列(DD-10)+ 通知记录。
 */
@Schema(description = "告警详情")
public record AlertDetailVO(

        @Schema(description = "告警行")
        AlertVO alert,

        @Schema(description = "规则当前快照(规则已删除为 null)")
        RuleVO rule,

        @Schema(description = "触发值序列(按 rule.metric + first~last 从采样表派生;超过指标保留期为空数组)")
        List<SeriesPoint> series,

        @Schema(description = "通知记录(notify_log 解析)")
        List<NotifyRecord> notifyRecords,

        @Schema(description = "series 是否已过指标保留期(true=前端显示保留期提示)")
        boolean seriesExpired) {

    /**
     * 序列点:t=epoch 毫秒,v=值。
     */
    public record SeriesPoint(
            /** epoch 毫秒 */
            long t,
            /** 值 */
            double v) {
    }

    /**
     * 单条通知记录(notify_log JSON 元素)。
     */
    public record NotifyRecord(
            /** 发送时间 */
            LocalDateTime time,
            /** fire / resolve */
            String type,
            /** 收件人数 */
            int recipients,
            /** 通道 */
            List<String> channels,
            /** 失败原因(成功为 null) */
            String error) {
    }
}
