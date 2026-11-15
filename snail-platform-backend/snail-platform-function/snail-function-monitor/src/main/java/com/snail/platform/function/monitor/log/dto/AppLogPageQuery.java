package com.snail.platform.function.monitor.log.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 职责:应用日志检索入参契约(主方案 §5.3,冻结)——时间窗必填跨度≤24h(service 校验),
 * level/traceId 精确、keyword 子串忽略大小写≤100。
 */
@Schema(description = "应用日志检索参数")
public record AppLogPageQuery(

        @Schema(description = "开始时间(ISO,含)", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请选择开始时间")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        LocalDateTime beginTime,

        @Schema(description = "结束时间(ISO,含,跨度≤24h)", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请选择结束时间")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        LocalDateTime endTime,

        @Schema(description = "级别:TRACE/DEBUG/INFO/WARN/ERROR,不传不限")
        @Pattern(regexp = "TRACE|DEBUG|INFO|WARN|ERROR", message = "级别取值 TRACE/DEBUG/INFO/WARN/ERROR")
        String level,

        @Schema(description = "链路 ID,精确匹配,不传不限")
        String traceId,

        @Schema(description = "关键字(子串,忽略大小写,≤100)")
        @Size(max = 100, message = "关键字最长 100 字")
        String keyword,

        @Schema(description = "返回条数上限(窗口内最后 N 条,时间倒序),默认 200,≤500")
        @Min(value = 1, message = "limit 至少 1")
        @Max(value = 500, message = "limit 至多 500")
        Integer limit) {

    public AppLogPageQuery {
        limit = limit == null ? 200 : limit;
    }
}
