package com.snail.platform.function.workflow.instance.controller;

import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.core.response.R;
import com.snail.platform.function.workflow.api.WorkflowApi.StartResult;
import com.snail.platform.function.workflow.instance.dto.WorkflowInstanceQuery;
import com.snail.platform.function.workflow.instance.dto.WorkflowInstanceVO;
import com.snail.platform.function.workflow.instance.dto.WorkflowStartRequest;
import com.snail.platform.function.workflow.instance.dto.WorkflowTraceVO;
import com.snail.platform.function.workflow.instance.dto.WorkflowWithdrawRequest;
import com.snail.platform.function.workflow.instance.service.WorkflowInstanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:流程实例入口——发起、我发起的、实例详情、轨迹、撤销。
 * 上游:admin 的"我发起的"与轨迹页;下游:WorkflowInstanceService。
 * 口径:发起与撤销不标 @OperLog——两者都已在审批台账落一条带操作人快照与缘由的行,同一事实不记两遍(同任务中心口径);
 * 这里的发起是给"没有业务单据"的场景用的,业务模块一律走 WorkflowApi.start,单据与流程必须同事务落库。
 * "我发起的"只认当前登录人,入参没有 userId 位:能看别人的发起列表就等于能看别人的单据。
 */
@Tag(name = "流程实例")
@RestController
@RequestMapping("/workflow/instance")
public class WorkflowInstanceController {

    /** 实例域编排服务 */
    private final WorkflowInstanceService instanceService;

    public WorkflowInstanceController(WorkflowInstanceService instanceService) {
        this.instanceService = instanceService;
    }

    @PreAuthorize("hasAuthority('workflow:instance:start')")
    @Operation(summary = "发起流程")
    @PostMapping
    public R<StartResult> start(@Valid @RequestBody WorkflowStartRequest request) {
        return R.ok(instanceService.start(request));
    }

    @PreAuthorize("hasAuthority('workflow:instance:list')")
    @Operation(summary = "我发起的分页")
    @GetMapping("/started")
    public R<PageResult<WorkflowInstanceVO>> started(@Valid WorkflowInstanceQuery query) {
        return R.ok(instanceService.pageStarted(query));
    }

    @PreAuthorize("hasAuthority('workflow:instance:list')")
    @Operation(summary = "流程实例详情")
    @GetMapping("/{instanceId}")
    public R<WorkflowInstanceVO> detail(@PathVariable String instanceId) {
        return R.ok(instanceService.detail(instanceId));
    }

    @PreAuthorize("hasAuthority('workflow:instance:list')")
    @Operation(summary = "流程轨迹")
    @GetMapping("/{instanceId}/trace")
    public R<WorkflowTraceVO> trace(@PathVariable String instanceId) {
        return R.ok(instanceService.trace(instanceId));
    }

    @PreAuthorize("hasAuthority('workflow:instance:withdraw')")
    @Operation(summary = "撤销在途流程")
    @PostMapping("/{instanceId}/withdraw")
    public R<Void> withdraw(@PathVariable String instanceId,
                            @Valid @RequestBody WorkflowWithdrawRequest request) {
        instanceService.withdraw(instanceId, request.reason());
        return R.ok(null);
    }
}
