package com.snail.platform.function.workflow.template.dto;

import com.snail.platform.framework.core.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 职责:流程模板分页查询契约(平台面)。名称模糊、分类与状态精确。
 * 上游:模板管理页面;下游:WorkflowTemplateService.page、PageAssembler。
 */
@Schema(description = "流程模板查询参数")
public record WorkflowTemplateQuery(

        @Schema(description = "页码,从 1 起", defaultValue = "1")
        @Min(value = 1, message = "页码从 1 起") Integer pageNum,

        @Schema(description = "每页条数,1-200", defaultValue = "10")
        @Min(value = 1, message = "每页至少 1 条")
        @Max(value = 200, message = "每页至多 200 条") Integer pageSize,

        @Schema(description = "模板名称,模糊匹配")
        String name,

        @Schema(description = "模板标识,模糊匹配")
        String templateKey,

        @Schema(description = "分类,精确匹配")
        String category,

        @Schema(description = "状态:0 上架 1 停用,不传不限")
        @Min(value = 0, message = "状态取值 0/1")
        @Max(value = 1, message = "状态取值 0/1") Integer status) implements PageQuery {

    public WorkflowTemplateQuery {
        pageNum = pageNum == null ? 1 : pageNum;
        pageSize = pageSize == null ? 10 : pageSize;
    }
}
