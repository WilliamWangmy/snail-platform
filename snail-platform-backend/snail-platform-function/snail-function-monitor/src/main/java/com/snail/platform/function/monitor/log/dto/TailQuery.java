package com.snail.platform.function.monitor.log.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 职责:日志 tail 入参契约(主方案 §5.3,冻结)——since 为上次最大时间 epoch 毫秒,增量正序(DD-26)。
 */
@Schema(description = "应用日志 tail 参数")
public record TailQuery(

        @Schema(description = "只返回该时刻(epoch 毫秒)之后的行", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "缺少 since")
        Long since,

        @Schema(description = "返回条数上限,默认 200,≤500")
        @Min(value = 1, message = "limit 至少 1")
        @Max(value = 500, message = "limit 至多 500")
        Integer limit) {

    public TailQuery {
        limit = limit == null ? 200 : limit;
    }
}
