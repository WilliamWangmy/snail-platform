package com.snail.platform.function.system.dict.dto;

import com.snail.platform.framework.core.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 职责:字典数据分页查询契约。必须钉住所属类型,不提供跨类型的全量翻页。
 * 上游:字典数据管理页(从类型行进入);下游:DictService.pageData、PageAssembler。
 */
@Schema(description = "字典数据查询参数")
public record DictDataQuery(

        @Schema(description = "页码,从 1 起", defaultValue = "1")
        @Min(value = 1, message = "页码从 1 起") Integer pageNum,

        @Schema(description = "每页条数,1-200", defaultValue = "10")
        @Min(value = 1, message = "每页至少 1 条")
        @Max(value = 200, message = "每页至多 200 条") Integer pageSize,

        @Schema(description = "所属字典类型ID", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请指定字典类型") Long dictId,

        @Schema(description = "字典标签,模糊匹配")
        String dictLabel,

        @Schema(description = "状态:0 正常 1 停用,不传不限")
        @Min(value = 0, message = "状态取值 0/1")
        @Max(value = 1, message = "状态取值 0/1") Integer status) implements PageQuery {

    public DictDataQuery {
        pageNum = pageNum == null ? 1 : pageNum;
        pageSize = pageSize == null ? 10 : pageSize;
    }
}
