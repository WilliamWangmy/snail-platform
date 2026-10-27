package com.snail.platform.function.workflow.model.dto;

import com.snail.platform.framework.core.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 职责:流程模型分页查询契约。名称与标识模糊匹配,分类精确过滤,状态精确过滤。
 * 上游:模型管理页面;下游:WorkflowModelService.page、PageAssembler。
 */
@Schema(description = "流程模型查询参数")
public record WorkflowModelQuery(

        @Schema(description = "页码,从 1 起", defaultValue = "1")
        @Min(value = 1, message = "页码从 1 起") Integer pageNum,

        @Schema(description = "每页条数,1-200", defaultValue = "10")
        @Min(value = 1, message = "每页至少 1 条")
        @Max(value = 200, message = "每页至多 200 条") Integer pageSize,

        @Schema(description = "流程名称,模糊匹配")
        String name,

        @Schema(description = "流程标识,模糊匹配")
        String modelKey,

        @Schema(description = "分类,精确匹配")
        String category,

        @Schema(description = "状态:0 草稿 1 已发布 2 停用,不传不限")
        @Min(value = 0, message = "状态取值 0/1/2")
        @Max(value = 2, message = "状态取值 0/1/2") Integer status) implements PageQuery {

    public WorkflowModelQuery {
        pageNum = pageNum == null ? 1 : pageNum;
        pageSize = pageSize == null ? 10 : pageSize;
    }
}
