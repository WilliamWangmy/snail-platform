package com.snail.platform.function.workflow.model.dto;

import com.snail.platform.framework.workflow.engine.WorkflowDefinition;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 职责:已发布版本的 BPMN 原文出参,设计器只读回显用。
 * 上游:WorkflowModelService.definitionXml;下游:版本详情画布(Viewer,不带编辑能力)。
 */
@Schema(description = "已发布流程定义内容")
public record WorkflowDefinitionXmlVO(

        @Schema(description = "引擎流程定义ID")
        String definitionId,

        @Schema(description = "流程标识")
        String definitionKey,

        @Schema(description = "版本号")
        Integer version,

        @Schema(description = "是否挂起")
        Boolean suspended,

        @Schema(description = "发布时部署进引擎的 BPMN XML 原文")
        String xml) {

    public static WorkflowDefinitionXmlVO of(WorkflowDefinition definition, String xml) {
        return new WorkflowDefinitionXmlVO(definition.id(), definition.key(), definition.version(),
                definition.suspended(), xml);
    }
}
