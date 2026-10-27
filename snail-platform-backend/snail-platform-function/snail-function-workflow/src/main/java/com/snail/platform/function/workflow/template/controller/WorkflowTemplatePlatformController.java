package com.snail.platform.function.workflow.template.controller;

import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.core.response.R;
import com.snail.platform.framework.log.BusinessType;
import com.snail.platform.framework.log.OperLog;
import com.snail.platform.function.workflow.template.dto.WorkflowTemplateCreateRequest;
import com.snail.platform.function.workflow.template.dto.WorkflowTemplateQuery;
import com.snail.platform.function.workflow.template.dto.WorkflowTemplateUpdateRequest;
import com.snail.platform.function.workflow.template.dto.WorkflowTemplateVO;
import com.snail.platform.function.workflow.template.dto.WorkflowTemplateXmlVO;
import com.snail.platform.function.workflow.template.service.WorkflowTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:平台面流程模板目录入口——分页、详情、原文与增删改。
 * 模板是全局一份的目录表(与菜单目录同口径),写动作只影响后续建模型,不回溯已建好的模型;
 * 权限码走 platform:workflow:template:*,与租户侧 workflow:* 分面,不复用。
 * 上游:平台的模板管理页;下游:WorkflowTemplateService。
 */
@Tag(name = "流程模板管理(平台)")
@RestController
@RequestMapping("/platform/workflow/template")
public class WorkflowTemplatePlatformController {

    /** 模板服务 */
    private final WorkflowTemplateService templateService;

    public WorkflowTemplatePlatformController(WorkflowTemplateService templateService) {
        this.templateService = templateService;
    }

    @PreAuthorize("hasAuthority('platform:workflow:template:list')")
    @Operation(summary = "模板分页")
    @GetMapping("/page")
    public R<PageResult<WorkflowTemplateVO>> page(@Valid WorkflowTemplateQuery query) {
        return R.ok(templateService.page(query));
    }

    @PreAuthorize("hasAuthority('platform:workflow:template:list')")
    @Operation(summary = "模板详情(不含 XML)")
    @GetMapping("/{templateId}")
    public R<WorkflowTemplateVO> detail(@PathVariable Long templateId) {
        return R.ok(templateService.detail(templateId));
    }

    @PreAuthorize("hasAuthority('platform:workflow:template:list')")
    @Operation(summary = "模板原文")
    @GetMapping("/{templateId}/xml")
    public R<WorkflowTemplateXmlVO> xml(@PathVariable Long templateId) {
        return R.ok(templateService.xml(templateId));
    }

    @PreAuthorize("hasAuthority('platform:workflow:template:create')")
    @Operation(summary = "新增模板(入库前过 BPMN 白名单)")
    @OperLog(module = "流程模板", businessType = BusinessType.INSERT)
    @PostMapping
    public R<Long> create(@Valid @RequestBody WorkflowTemplateCreateRequest request) {
        return R.ok(templateService.create(request));
    }

    @PreAuthorize("hasAuthority('platform:workflow:template:update')")
    @Operation(summary = "修改模板(标识不可改;不传 XML 即保留原文)")
    @OperLog(module = "流程模板", businessType = BusinessType.UPDATE)
    @PutMapping("/{templateId}")
    public R<Void> update(@PathVariable Long templateId,
                          @Valid @RequestBody WorkflowTemplateUpdateRequest request) {
        templateService.update(templateId, request);
        return R.ok(null);
    }

    @PreAuthorize("hasAuthority('platform:workflow:template:delete')")
    @Operation(summary = "删除模板(逻辑删,不影响已建模型)")
    @OperLog(module = "流程模板", businessType = BusinessType.DELETE)
    @DeleteMapping("/{templateId}")
    public R<Void> delete(@PathVariable Long templateId) {
        templateService.delete(templateId);
        return R.ok(null);
    }
}
