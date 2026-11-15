package com.snail.platform.function.monitor.alert.dto;

import com.snail.platform.framework.core.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 职责:规则分页查询契约——keyword 模糊 name、enabled 精确,按 rule_id 升序。
 */
@Schema(description = "告警规则分页参数")
public record RuleQuery(

        @Schema(description = "页码,从 1 起", defaultValue = "1")
        @Min(value = 1, message = "页码从 1 起") Integer pageNum,

        @Schema(description = "每页条数,1-200", defaultValue = "20")
        @Min(value = 1, message = "每页至少 1 条")
        @Max(value = 200, message = "每页至多 200 条") Integer pageSize,

        @Schema(description = "规则名称,模糊匹配")
        String keyword,

        @Schema(description = "开关:0 启用 1 停用,不传不限")
        @Min(value = 0, message = "开关取值 0/1")
        @Max(value = 1, message = "开关取值 0/1") Integer enabled) implements PageQuery {

    public RuleQuery {
        pageNum = pageNum == null ? 1 : pageNum;
        pageSize = pageSize == null ? 20 : pageSize;
    }
}
