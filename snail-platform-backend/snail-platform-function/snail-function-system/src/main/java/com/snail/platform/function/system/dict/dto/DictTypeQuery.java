package com.snail.platform.function.system.dict.dto;

import com.snail.platform.framework.core.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 职责:字典类型分页查询契约。名称与类型标识均为模糊匹配,停用状态精确过滤。
 * 上游:字典管理页面;下游:DictService.pageTypes、PageAssembler。
 */
@Schema(description = "字典类型查询参数")
public record DictTypeQuery(

        @Schema(description = "页码,从 1 起", defaultValue = "1")
        @Min(value = 1, message = "页码从 1 起") Integer pageNum,

        @Schema(description = "每页条数,1-200", defaultValue = "10")
        @Min(value = 1, message = "每页至少 1 条")
        @Max(value = 200, message = "每页至多 200 条") Integer pageSize,

        @Schema(description = "字典名称,模糊匹配")
        String dictName,

        @Schema(description = "字典类型标识,模糊匹配")
        String dictType,

        @Schema(description = "状态:0 正常 1 停用,不传不限")
        @Min(value = 0, message = "状态取值 0/1")
        @Max(value = 1, message = "状态取值 0/1") Integer status) implements PageQuery {

    public DictTypeQuery {
        pageNum = pageNum == null ? 1 : pageNum;
        pageSize = pageSize == null ? 10 : pageSize;
    }
}
