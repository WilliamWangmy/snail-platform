package com.snail.platform.function.workflow.template.controller;

import com.snail.platform.framework.core.response.R;
import com.snail.platform.function.workflow.template.dto.WorkflowTemplateOptionVO;
import com.snail.platform.function.workflow.template.dto.WorkflowTemplateXmlVO;
import com.snail.platform.function.workflow.template.service.WorkflowTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:租户侧读平台流程模板的入口——可选模板列表与模板原文,只读、无写入。
 * 权限口径:复用 workflow:model:list。"从模板建模型"是新建模型的一种来源,
 * 能建模型的人就应当能看模板目录,再设一码只会让两处权限需要同步维护。
 * 表在租户 ignore 清单内(全局目录一份),所以这里无需切租户也无需 bypass 窄窗口。
 * 上游:模型新建页的模板下拉与预览;下游:WorkflowTemplateService。
 */
@Tag(name = "流程模板(租户只读)")
@RestController
@RequestMapping("/workflow/template")
public class WorkflowTemplateController {

    /** 模板服务 */
    private final WorkflowTemplateService templateService;

    public WorkflowTemplateController(WorkflowTemplateService templateService) {
        this.templateService = templateService;
    }

    @PreAuthorize("hasAuthority('workflow:model:list')")
    @Operation(summary = "上架模板列表(建模型用)")
    @GetMapping("/options")
    public R<List<WorkflowTemplateOptionVO>> options() {
        return R.ok(templateService.options());
    }

    @PreAuthorize("hasAuthority('workflow:model:list')")
    @Operation(summary = "模板原文(建模型前预览)")
    @GetMapping("/{templateId}/xml")
    public R<WorkflowTemplateXmlVO> xml(@PathVariable Long templateId) {
        return R.ok(templateService.xml(templateId));
    }
}
