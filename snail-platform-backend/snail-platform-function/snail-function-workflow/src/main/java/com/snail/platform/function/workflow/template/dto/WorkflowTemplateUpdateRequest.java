package com.snail.platform.function.workflow.template.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 职责:修改流程模板契约(平台面)。templateKey 不可改;内容不传即保留原文,避免只改名称时误清空。
 * 上游:模板管理页面;下游:WorkflowTemplateService.update。
 */
@Schema(description = "修改流程模板请求")
public record WorkflowTemplateUpdateRequest(

        @Schema(description = "模板名称", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入模板名称")
        @Size(max = 100, message = "模板名称过长")
        String name,

        @Schema(description = "分类")
        @Size(max = 50, message = "分类过长")
        String category,

        @Schema(description = "备注")
        @Size(max = 500, message = "备注过长")
        String remark,

        @Schema(description = "BPMN XML 原文,不传表示不改内容")
        String bpmnXml,

        @Schema(description = "状态:0 上架 1 停用,不传保持原值")
        @Min(value = 0, message = "状态取值 0/1")
        @Max(value = 1, message = "状态取值 0/1") Integer status) {
}
