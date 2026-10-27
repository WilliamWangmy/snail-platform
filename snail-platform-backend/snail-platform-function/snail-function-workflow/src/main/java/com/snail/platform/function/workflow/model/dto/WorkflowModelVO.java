package com.snail.platform.function.workflow.model.dto;

import com.snail.platform.function.workflow.model.entity.SysWorkflowModel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 职责:流程模型出参(列表与详情共用)。带当前发布指针,列表页据此显示"已发布 v3";
 * 不含 XML 原文——草稿与已发布定义各有专门端点,混进本对象会让分页响应膨胀到 MB 级。
 * 上游:WorkflowModelService;下游:模型管理列表、模型详情。
 */
@Schema(description = "流程模型信息")
public record WorkflowModelVO(

        @Schema(description = "模型ID")
        Long modelId,

        @Schema(description = "流程标识,即 BPMN process id,建后不可改")
        String modelKey,

        @Schema(description = "流程名称")
        String name,

        @Schema(description = "分类")
        String category,

        @Schema(description = "备注")
        String remark,

        @Schema(description = "状态:0 草稿 1 已发布 2 停用")
        Integer status,

        @Schema(description = "来源模板ID,无来源为空")
        Long templateId,

        @Schema(description = "是否有草稿内容")
        Boolean hasDraft,

        @Schema(description = "当前发布指针:引擎流程定义ID,未发布为空")
        String processDefinitionId,

        @Schema(description = "当前发布指针:引擎版本号")
        Integer deployedVersion,

        @Schema(description = "最后一次发布时间")
        LocalDateTime publishTime,

        @Schema(description = "创建时间")
        LocalDateTime createTime,

        @Schema(description = "修改时间")
        LocalDateTime updateTime) {

    public static WorkflowModelVO from(SysWorkflowModel model) {
        return new WorkflowModelVO(model.getModelId(), model.getModelKey(), model.getName(),
                model.getCategory(), model.getRemark(), model.getStatus(), model.getTemplateId(),
                model.getDraftXml() != null && !model.getDraftXml().isBlank(),
                model.getProcessDefinitionId(), model.getDeployedVersion(), model.getPublishTime(),
                model.getCreateTime(), model.getUpdateTime());
    }
}
