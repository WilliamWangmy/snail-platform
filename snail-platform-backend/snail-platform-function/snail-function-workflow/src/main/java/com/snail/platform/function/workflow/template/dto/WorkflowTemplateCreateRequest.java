package com.snail.platform.function.workflow.template.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 职责:新增流程模板契约(平台面)。templateKey 全局唯一含已删记录、建后不可改;
 * bpmnXml 入库前必须过 BPMN 白名单校验——存一份发不出去的模板,等于给租户埋雷。
 * 上游:模板管理页面;下游:WorkflowTemplateService.create。
 */
@Schema(description = "新增流程模板请求")
public record WorkflowTemplateCreateRequest(

        @Schema(description = "模板标识,小写字母开头,只允许字母数字下划线;建后不可改",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入模板标识")
        @Pattern(regexp = "[a-z][A-Za-z0-9_]{0,63}",
                message = "模板标识需小写字母开头,仅允许字母、数字、下划线,长度 1-64")
        String templateKey,

        @Schema(description = "模板名称", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入模板名称")
        @Size(max = 100, message = "模板名称过长")
        String name,

        @Schema(description = "分类")
        @Size(max = 50, message = "分类过长")
        String category,

        @Schema(description = "备注:适用场景说明")
        @Size(max = 500, message = "备注过长")
        String remark,

        @Schema(description = "BPMN XML 原文", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "模板内容不能为空")
        String bpmnXml) {
}
