package com.snail.platform.function.workflow.template.dto;

import com.snail.platform.function.workflow.template.entity.SysWorkflowTemplate;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 职责:流程模板出参(平台面列表)。不含 XML 原文——列表页不需要,原文走专门端点。
 * 上游:WorkflowTemplateService;下游:模板管理列表。
 */
@Schema(description = "流程模板信息")
public record WorkflowTemplateVO(

        @Schema(description = "模板ID")
        Long templateId,

        @Schema(description = "模板标识,建后不可改")
        String templateKey,

        @Schema(description = "模板名称")
        String name,

        @Schema(description = "分类")
        String category,

        @Schema(description = "备注")
        String remark,

        @Schema(description = "状态:0 上架 1 停用")
        Integer status,

        @Schema(description = "创建时间")
        LocalDateTime createTime,

        @Schema(description = "修改时间")
        LocalDateTime updateTime) {

    public static WorkflowTemplateVO from(SysWorkflowTemplate template) {
        return new WorkflowTemplateVO(template.getTemplateId(), template.getTemplateKey(),
                template.getName(), template.getCategory(), template.getRemark(), template.getStatus(),
                template.getCreateTime(), template.getUpdateTime());
    }
}
