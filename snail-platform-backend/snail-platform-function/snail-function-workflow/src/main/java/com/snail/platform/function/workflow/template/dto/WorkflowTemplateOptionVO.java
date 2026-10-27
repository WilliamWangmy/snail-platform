package com.snail.platform.function.workflow.template.dto;

import com.snail.platform.function.workflow.template.entity.SysWorkflowTemplate;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 职责:租户侧可选模板摘要——只出上架模板的最小选择信息,不含原文(原文走 xml 端点)。
 * 上游:WorkflowTemplateService.options;下游:模型新建页的"从模板创建"下拉。
 */
@Schema(description = "可选流程模板")
public record WorkflowTemplateOptionVO(

        @Schema(description = "模板ID")
        Long templateId,

        @Schema(description = "模板标识")
        String templateKey,

        @Schema(description = "模板名称")
        String name,

        @Schema(description = "分类")
        String category,

        @Schema(description = "备注:适用场景")
        String remark) {

    public static WorkflowTemplateOptionVO from(SysWorkflowTemplate template) {
        return new WorkflowTemplateOptionVO(template.getTemplateId(), template.getTemplateKey(),
                template.getName(), template.getCategory(), template.getRemark());
    }
}
