package com.snail.platform.function.workflow.model.controller;

import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.core.response.R;
import com.snail.platform.framework.log.BusinessType;
import com.snail.platform.framework.log.OperLog;
import com.snail.platform.function.workflow.model.dto.WorkflowDefinitionVO;
import com.snail.platform.function.workflow.model.dto.WorkflowDraftVO;
import com.snail.platform.function.workflow.model.dto.WorkflowModelCreateRequest;
import com.snail.platform.function.workflow.model.dto.WorkflowModelDraftRequest;
import com.snail.platform.function.workflow.model.dto.WorkflowModelQuery;
import com.snail.platform.function.workflow.model.dto.WorkflowModelUpdateRequest;
import com.snail.platform.function.workflow.model.dto.WorkflowModelVO;
import com.snail.platform.function.workflow.model.service.WorkflowModelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
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
 * 职责:流程模型入口(租户面)——模型 CRUD、设计器草稿读写、发布与停用/激活、版本列表。
 * 权限口径:list 只圈可见面,增删改各一码,发布与停用共用 workflow:model:publish——
 * 发布等同部署代码,停用等同让流程发不起来,两者危险等级一致,与"改个名字"必须分档(方案 §5)。
 * 草稿保存不标 @OperLog:设计器自动保存是高频动作,进审计表只会把操作日志刷成噪音。
 * 上游:模型管理页与设计器;下游:WorkflowModelService。
 */
@Tag(name = "流程模型")
@RestController
@RequestMapping("/workflow/model")
public class WorkflowModelController {

    /** 模型服务 */
    private final WorkflowModelService modelService;

    public WorkflowModelController(WorkflowModelService modelService) {
        this.modelService = modelService;
    }

    @PreAuthorize("hasAuthority('workflow:model:list')")
    @Operation(summary = "模型分页")
    @GetMapping("/page")
    public R<PageResult<WorkflowModelVO>> page(@Valid WorkflowModelQuery query) {
        return R.ok(modelService.page(query));
    }

    @PreAuthorize("hasAuthority('workflow:model:list')")
    @Operation(summary = "模型详情(不含 XML)")
    @GetMapping("/{modelId}")
    public R<WorkflowModelVO> detail(@PathVariable Long modelId) {
        return R.ok(modelService.detail(modelId));
    }

    @PreAuthorize("hasAuthority('workflow:model:create')")
    @Operation(summary = "新增模型(可直接给草稿,或从平台模板落地)")
    @OperLog(module = "流程模型", businessType = BusinessType.INSERT)
    @PostMapping
    public R<Long> create(@Valid @RequestBody WorkflowModelCreateRequest request) {
        return R.ok(modelService.create(request));
    }

    @PreAuthorize("hasAuthority('workflow:model:update')")
    @Operation(summary = "修改模型展示信息(流程标识不可改)")
    @OperLog(module = "流程模型", businessType = BusinessType.UPDATE)
    @PutMapping("/{modelId}")
    public R<Void> update(@PathVariable Long modelId,
                          @Valid @RequestBody WorkflowModelUpdateRequest request) {
        modelService.update(modelId, request);
        return R.ok(null);
    }

    @PreAuthorize("hasAuthority('workflow:model:delete')")
    @Operation(summary = "删除模型(该流程标识下有在途实例时拒绝)")
    @OperLog(module = "流程模型", businessType = BusinessType.DELETE)
    @DeleteMapping("/{modelId}")
    public R<Void> delete(@PathVariable Long modelId) {
        modelService.delete(modelId);
        return R.ok(null);
    }

    @PreAuthorize("hasAuthority('workflow:model:list')")
    @Operation(summary = "取设计器草稿")
    @GetMapping("/{modelId}/draft")
    public R<WorkflowDraftVO> draft(@PathVariable Long modelId) {
        return R.ok(modelService.draft(modelId));
    }

    @PreAuthorize("hasAuthority('workflow:model:update')")
    @Operation(summary = "保存设计器草稿(整份覆盖)")
    @PutMapping("/{modelId}/draft")
    public R<Void> saveDraft(@PathVariable Long modelId,
                             @Valid @RequestBody WorkflowModelDraftRequest request) {
        modelService.saveDraft(modelId, request.xml());
        return R.ok(null);
    }

    @PreAuthorize("hasAuthority('workflow:model:publish')")
    @Operation(summary = "发布模型(校验 + 部署 + 回写版本指针)")
    @OperLog(module = "流程模型", businessType = BusinessType.OTHER)
    @PostMapping("/{modelId}/publish")
    public R<WorkflowDefinitionVO> publish(@PathVariable Long modelId) {
        return R.ok(modelService.publish(modelId));
    }

    @PreAuthorize("hasAuthority('workflow:model:list')")
    @Operation(summary = "版本列表(当前指针标 deployed)")
    @GetMapping("/{modelId}/definitions")
    public R<List<WorkflowDefinitionVO>> definitions(@PathVariable Long modelId) {
        return R.ok(modelService.definitions(modelId));
    }

    @PreAuthorize("hasAuthority('workflow:model:publish')")
    @Operation(summary = "停用流程(挂起当前定义,禁止新发起)")
    @OperLog(module = "流程模型", businessType = BusinessType.OTHER)
    @PostMapping("/{modelId}/suspend")
    public R<Void> suspend(@PathVariable Long modelId) {
        modelService.suspend(modelId);
        return R.ok(null);
    }

    @PreAuthorize("hasAuthority('workflow:model:publish')")
    @Operation(summary = "启用流程(激活当前定义,恢复可发起)")
    @OperLog(module = "流程模型", businessType = BusinessType.OTHER)
    @PostMapping("/{modelId}/activate")
    public R<Void> activate(@PathVariable Long modelId) {
        modelService.activate(modelId);
        return R.ok(null);
    }
}
