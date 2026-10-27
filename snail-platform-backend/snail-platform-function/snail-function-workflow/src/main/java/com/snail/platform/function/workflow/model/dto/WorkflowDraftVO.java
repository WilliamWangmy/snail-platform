package com.snail.platform.function.workflow.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 职责:设计器打开模型时的入参——草稿原文加上标识,前端据此校验"画布上的 process id 是否还是这个 key"。
 * 上游:WorkflowModelService.draft;下游:bpmn-js 设计器。
 * 口径:只出草稿,已发布版本走定义原文端点;两者分开是让"编辑"与"回看"不会拿到同一份内容互相覆盖。
 */
@Schema(description = "流程草稿内容")
public record WorkflowDraftVO(

        @Schema(description = "模型ID")
        Long modelId,

        @Schema(description = "流程标识,BPMN process id")
        String modelKey,

        @Schema(description = "流程名称")
        String name,

        @Schema(description = "状态:0 草稿 1 已发布 2 停用")
        Integer status,

        @Schema(description = "BPMN XML 草稿原文,未保存过草稿为空")
        String xml) {
}
