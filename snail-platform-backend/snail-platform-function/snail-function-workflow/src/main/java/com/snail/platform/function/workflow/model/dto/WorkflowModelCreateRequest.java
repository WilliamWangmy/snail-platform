package com.snail.platform.function.workflow.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 职责:新增流程模型契约。modelKey 就是 BPMN 里的 process id,建后不可改、租户内终身占用,
 * 因此新增时必须一次定准;draftXml 与 templateId 二选一(给模板则服务端拷原文并按本 key 改名)。
 * 上游:模型管理页面与设计器"新建";下游:WorkflowModelService.create。
 */
@Schema(description = "新增流程模型请求")
public record WorkflowModelCreateRequest(

        @Schema(description = "流程标识,小写字母开头,只允许字母数字下划线;建后不可改",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入流程标识")
        @Pattern(regexp = "[a-z][A-Za-z0-9_]{0,63}",
                message = "流程标识需小写字母开头,仅允许字母、数字、下划线,长度 1-64")
        String modelKey,

        @Schema(description = "流程名称", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入流程名称")
        @Size(max = 100, message = "流程名称过长")
        String name,

        @Schema(description = "分类")
        @Size(max = 50, message = "分类过长")
        String category,

        @Schema(description = "备注")
        @Size(max = 500, message = "备注过长")
        String remark,

        @Schema(description = "来源模板ID;给了模板就不必给 draftXml")
        Long templateId,

        @Schema(description = "初始 BPMN XML 草稿;与 templateId 至少给一个")
        String draftXml) {
}
