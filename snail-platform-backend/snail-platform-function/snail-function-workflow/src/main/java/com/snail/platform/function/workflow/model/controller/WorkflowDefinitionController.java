package com.snail.platform.function.workflow.model.controller;

import com.snail.platform.framework.core.response.R;
import com.snail.platform.function.workflow.model.dto.WorkflowDefinitionXmlVO;
import com.snail.platform.function.workflow.model.service.WorkflowModelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:已发布流程定义的只读入口(租户面)——按定义 ID 取发布时的 BPMN 原文。
 * 为什么与模型分开一个类:定义 ID 是引擎侧坐标,不属于模型主键空间,硬塞进 /workflow/model/{modelId} 会让路由含糊。
 * 租户归属由引擎网关按 processDefinitionTenantId 判定,跨租户读取在这里就已经是"不存在",不依赖路径参数。
 * 上游:版本详情的只读画布;下游:WorkflowModelService.definitionXml。
 */
@Tag(name = "流程定义")
@RestController
@RequestMapping("/workflow/definition")
public class WorkflowDefinitionController {

    /** 模型服务:定义读取归它(与版本列表同源) */
    private final WorkflowModelService modelService;

    public WorkflowDefinitionController(WorkflowModelService modelService) {
        this.modelService = modelService;
    }

    @PreAuthorize("hasAuthority('workflow:model:list')")
    @Operation(summary = "已发布版本的 BPMN 原文(只读回显)")
    @GetMapping("/{definitionId}/xml")
    public R<WorkflowDefinitionXmlVO> xml(@PathVariable String definitionId) {
        return R.ok(modelService.definitionXml(definitionId));
    }
}
