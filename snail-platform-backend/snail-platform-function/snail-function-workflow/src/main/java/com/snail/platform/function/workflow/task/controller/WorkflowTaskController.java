package com.snail.platform.function.workflow.task.controller;

import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.core.response.R;
import com.snail.platform.function.workflow.task.dto.WorkflowDoneVO;
import com.snail.platform.function.workflow.task.dto.WorkflowHandleRequest;
import com.snail.platform.function.workflow.task.dto.WorkflowTaskQuery;
import com.snail.platform.function.workflow.task.dto.WorkflowTodoVO;
import com.snail.platform.function.workflow.task.dto.WorkflowTransferRequest;
import com.snail.platform.function.workflow.task.service.WorkflowTaskService;
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
 * 职责:任务中心入口——我的待办/已办列表与签收、通过、驳回、转办四个办理动作。
 * 上游:admin 的待办页与已办页;下游:WorkflowTaskService。
 * 口径:办理动作不标 @OperLog。每次办理已经落一条审批台账(带操作人五列快照与意见),
 * 再往操作日志里写一遍就是同一事实两处记,还会把审计表刷成业务流水;
 * 权限码分三档:list 看待办、done 看已办、handle 办业务——待办与已办是两拨人看的两页,码必须分开。
 */
@Tag(name = "流程任务中心")
@RestController
@RequestMapping("/workflow/task")
public class WorkflowTaskController {

    /** 任务中心编排服务 */
    private final WorkflowTaskService taskService;

    public WorkflowTaskController(WorkflowTaskService taskService) {
        this.taskService = taskService;
    }

    @PreAuthorize("hasAuthority('workflow:task:list')")
    @Operation(summary = "我的待办分页")
    @GetMapping("/todo")
    public R<PageResult<WorkflowTodoVO>> todo(@Valid WorkflowTaskQuery query) {
        return R.ok(taskService.pageTodo(query));
    }

    @PreAuthorize("hasAuthority('workflow:task:done')")
    @Operation(summary = "我的已办分页")
    @GetMapping("/done")
    public R<PageResult<WorkflowDoneVO>> done(@Valid WorkflowTaskQuery query) {
        return R.ok(taskService.pageDone(query));
    }

    @PreAuthorize("hasAuthority('workflow:task:handle')")
    @Operation(summary = "签收任务")
    @PostMapping("/{taskId}/claim")
    public R<Void> claim(@PathVariable String taskId, @Valid @RequestBody WorkflowHandleRequest request) {
        taskService.claim(taskId, request);
        return R.ok(null);
    }

    @PreAuthorize("hasAuthority('workflow:task:handle')")
    @Operation(summary = "审批通过")
    @PostMapping("/{taskId}/approve")
    public R<Void> approve(@PathVariable String taskId, @Valid @RequestBody WorkflowHandleRequest request) {
        taskService.approve(taskId, request);
        return R.ok(null);
    }

    @PreAuthorize("hasAuthority('workflow:task:handle')")
    @Operation(summary = "审批驳回")
    @PostMapping("/{taskId}/reject")
    public R<Void> reject(@PathVariable String taskId, @Valid @RequestBody WorkflowHandleRequest request) {
        taskService.reject(taskId, request);
        return R.ok(null);
    }

    @PreAuthorize("hasAuthority('workflow:task:handle')")
    @Operation(summary = "转办")
    @PostMapping("/{taskId}/transfer")
    public R<Void> transfer(@PathVariable String taskId,
                            @Valid @RequestBody WorkflowTransferRequest request) {
        taskService.transfer(taskId, request);
        return R.ok(null);
    }
}
