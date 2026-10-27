package com.snail.platform.function.workflow.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 职责:修改流程模型契约。只改展示面字段——modelKey 是流程标识与发布指针的锚点,不在修改契约里出现。
 * 上游:模型管理页面;下游:WorkflowModelService.update。
 */
@Schema(description = "修改流程模型请求")
public record WorkflowModelUpdateRequest(

        @Schema(description = "流程名称", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入流程名称")
        @Size(max = 100, message = "流程名称过长")
        String name,

        @Schema(description = "分类")
        @Size(max = 50, message = "分类过长")
        String category,

        @Schema(description = "备注")
        @Size(max = 500, message = "备注过长")
        String remark) {
}
