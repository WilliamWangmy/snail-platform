package com.snail.platform.function.monitor.alert.dto;

import com.snail.platform.framework.core.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 职责:告警历史分页查询契约——status/level/ruleId/时间窗过滤,按 first_time 倒序。
 * 上游:AlertController;下游:AlertService.page。
 */
@Schema(description = "告警历史分页参数")
public record AlertQuery(

        @Schema(description = "页码,从 1 起", defaultValue = "1")
        @Min(value = 1, message = "页码从 1 起") Integer pageNum,

        @Schema(description = "每页条数,1-200", defaultValue = "20")
        @Min(value = 1, message = "每页至少 1 条")
        @Max(value = 200, message = "每页至多 200 条") Integer pageSize,

        @Schema(description = "状态:firing/acked/resolved,不传不限")
        @Pattern(regexp = "firing|acked|resolved", message = "状态取值 firing/acked/resolved")
        String status,

        @Schema(description = "级别:info/warning/critical,不传不限")
        @Pattern(regexp = "info|warning|critical", message = "级别取值 info/warning/critical")
        String level,

        @Schema(description = "规则 ID,不传不限")
        Long ruleId,

        @Schema(description = "首次触发时间起,ISO 局部日期时间")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime beginTime,

        @Schema(description = "首次触发时间止,ISO 局部日期时间")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) implements PageQuery {

    public AlertQuery {
        pageNum = pageNum == null ? 1 : pageNum;
        pageSize = pageSize == null ? 20 : pageSize;
    }
}
