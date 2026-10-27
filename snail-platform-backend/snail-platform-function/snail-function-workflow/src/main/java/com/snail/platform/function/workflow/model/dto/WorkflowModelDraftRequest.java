package com.snail.platform.function.workflow.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 职责:保存设计器草稿契约。整份 BPMN XML 覆盖式保存,不做增量合并。
 * 上游:bpmn-js 设计器保存动作;下游:WorkflowModelService.saveDraft。
 * 口径:这里只做非空与体积下限校验,白名单校验留在发布环节——草稿允许是半成品,
 * 未绑定审批人、连线没画全都不该挡住"先存一下";但体积上限必须在入口就拦,不能把 2MB 以上塞进请求线程。
 */
@Schema(description = "保存流程草稿请求")
public record WorkflowModelDraftRequest(

        @Schema(description = "BPMN XML 草稿原文", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "流程内容不能为空")
        String xml) {
}
