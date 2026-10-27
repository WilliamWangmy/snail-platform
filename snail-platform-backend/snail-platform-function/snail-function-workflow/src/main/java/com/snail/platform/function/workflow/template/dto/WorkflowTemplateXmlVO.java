package com.snail.platform.function.workflow.template.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 职责:模板原文出参。平台面编辑与租户面"先看模板长什么样"共用这一个形状——
 * 原文只有一份,拆两个对象只会让两边显示不同步。
 * 上游:WorkflowTemplateService.xml;下游:模板编辑器与模板预览。
 */
@Schema(description = "流程模板内容")
public record WorkflowTemplateXmlVO(

        @Schema(description = "模板ID")
        Long templateId,

        @Schema(description = "模板标识")
        String templateKey,

        @Schema(description = "模板名称")
        String name,

        @Schema(description = "BPMN XML 原文")
        String xml) {
}
