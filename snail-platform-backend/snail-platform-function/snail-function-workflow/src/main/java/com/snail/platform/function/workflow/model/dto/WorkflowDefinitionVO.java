package com.snail.platform.function.workflow.model.dto;

import com.snail.platform.framework.workflow.engine.WorkflowDefinition;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 职责:已发布流程定义出参(版本列表)。deployed 标记"模型当前指针指向哪一版",
 * 让页面上能区分"在用版本"与"历史版本";历史版本可查原文、可挂起。
 * 上游:WorkflowModelService.definitions;下游:模型详情的版本列表。
 */
@Schema(description = "流程定义版本信息")
public record WorkflowDefinitionVO(

        @Schema(description = "引擎流程定义ID")
        String definitionId,

        @Schema(description = "流程标识")
        String definitionKey,

        @Schema(description = "定义名称")
        String definitionName,

        @Schema(description = "版本号,同 key 递增")
        Integer version,

        @Schema(description = "引擎部署ID")
        String deploymentId,

        @Schema(description = "是否挂起:挂起后不能发起新实例")
        Boolean suspended,

        @Schema(description = "是否为模型当前发布版本")
        Boolean deployed) {

    public static WorkflowDefinitionVO from(WorkflowDefinition definition, String currentDefinitionId) {
        return new WorkflowDefinitionVO(definition.id(), definition.key(), definition.name(),
                definition.version(), definition.deploymentId(), definition.suspended(),
                definition.id().equals(currentDefinitionId));
    }
}
