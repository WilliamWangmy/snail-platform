package com.snail.platform.function.workflow.task.service;

import com.snail.platform.framework.core.context.LoginUserContextHolder;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.workflow.engine.WorkflowEngineGateway;
import com.snail.platform.framework.workflow.engine.WorkflowInstance;
import com.snail.platform.framework.workflow.engine.WorkflowTask;
import com.snail.platform.function.system.api.UserBriefPort;
import com.snail.platform.function.system.api.UserBriefPort.UserBrief;
import com.snail.platform.function.workflow.api.WorkflowStatus;
import com.snail.platform.function.workflow.support.WorkflowOutcomeDispatcher;
import com.snail.platform.function.workflow.task.dto.WorkflowDoneVO;
import com.snail.platform.function.workflow.task.dto.WorkflowHandleRequest;
import com.snail.platform.function.workflow.task.dto.WorkflowTaskQuery;
import com.snail.platform.function.workflow.task.dto.WorkflowTodoVO;
import com.snail.platform.function.workflow.task.dto.WorkflowTransferRequest;
import com.snail.platform.function.workflow.task.service.WorkflowTaskRecordService.Trace;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 职责:任务中心的编排层——待办/已办读模型装配,签收/通过/驳回/转办四个动作(方案 §6.1、§8 P3)。
 * 上游:WorkflowTaskController;下游:framework-workflow 网关(引擎一切读写只经它)、
 * 审批台账 WorkflowTaskRecordService、通知 WorkflowNotifyService、结果回调 WorkflowOutcomeDispatcher、用户档案 UserBriefPort。
 * 口径:
 * 1) 办理权只在网关判(租户 + 办理人/候选角色),本层不重复判也不绕过——重复判就会有第二套答案;
 * 2) 动作与留痕同事务,引擎写成功、台账失败即整体回滚,不给"流程走了却没记录"留缝;
 * 3) 展示名在查询时刻从用户档案取(列表说"现在叫什么"),台账里的快照才是"当时叫什么";
 * 4) 通过/驳回只往流程变量写一个布尔 approved,由定义的网关分支决定去向——平台不解释流程语义;
 * 5) 通知只在事务提交后发,发不出去不影响已成立的审批。
 */
@Service
public class WorkflowTaskService {

    /** 审批结果变量名:与 BPMN 排他网关的 ${approved} 条件对应,是平台与流程定义之间的唯一暗号 */
    private static final String VARIABLE_APPROVED = "approved";

    /** 流程引擎网关 */
    private final WorkflowEngineGateway gateway;

    /** 审批台账写入 */
    private final WorkflowTaskRecordService recordService;

    /** 提交后通知 */
    private final WorkflowNotifyService notifyService;

    /** 用户档案端口:展示名与转办目标校验 */
    private final UserBriefPort userBriefPort;

    /** 流程结果回调派发单点:流程走完时把结果交给认领该业务类型的模块 */
    private final WorkflowOutcomeDispatcher outcomeDispatcher;

    public WorkflowTaskService(WorkflowEngineGateway gateway, WorkflowTaskRecordService recordService,
                               WorkflowNotifyService notifyService, UserBriefPort userBriefPort,
                               WorkflowOutcomeDispatcher outcomeDispatcher) {
        this.gateway = gateway;
        this.recordService = recordService;
        this.notifyService = notifyService;
        this.userBriefPort = userBriefPort;
        this.outcomeDispatcher = outcomeDispatcher;
    }

    /** 我的待办:引擎分页 + 业务坐标批量回查 + 展示名批量取档,一页数据三次查询,没有逐行回查 */
    public PageResult<WorkflowTodoVO> pageTodo(WorkflowTaskQuery query) {
        long total = gateway.countTodo();
        List<WorkflowTask> tasks = gateway.findTodo(offset(query), query.pageSize());
        if (tasks.isEmpty()) {
            return PageResult.of(List.of(), total);
        }
        Map<String, WorkflowInstance> instances = instancesOf(tasks);
        Map<Long, String> names = namesOf(tasks, instances);
        List<WorkflowTodoVO> rows = tasks.stream()
                .map(task -> {
                    WorkflowInstance instance = instances.get(task.instanceId());
                    Long initiatorId = WorkflowTodoVO.toUserId(instance == null ? null : instance.startUserId());
                    Long assigneeId = WorkflowTodoVO.toUserId(task.assignee());
                    return WorkflowTodoVO.of(task, instance,
                            initiatorId == null ? null : names.get(initiatorId),
                            assigneeId == null ? null : names.get(assigneeId));
                })
                .toList();
        return PageResult.of(rows, total);
    }

    /** 我的已办:引擎历史任务分页,倒序按办结时刻 */
    public PageResult<WorkflowDoneVO> pageDone(WorkflowTaskQuery query) {
        long total = gateway.countDone();
        List<WorkflowTask> tasks = gateway.findDone(offset(query), query.pageSize());
        if (tasks.isEmpty()) {
            return PageResult.of(List.of(), total);
        }
        Map<String, WorkflowInstance> instances = instancesOf(tasks);
        Map<Long, String> names = namesOf(List.of(), instances);
        List<WorkflowDoneVO> rows = tasks.stream().map(task -> {
            WorkflowInstance instance = instances.get(task.instanceId());
            Long initiatorId = WorkflowTodoVO.toUserId(instance == null ? null : instance.startUserId());
            return WorkflowDoneVO.of(task, instance,
                    initiatorId == null ? null : names.get(initiatorId));
        }).toList();
        return PageResult.of(rows, total);
    }

    /** 签收:把候选任务领到自己名下。领完别人就办不了了,所以这一步必须留痕 */
    @Transactional
    public void claim(String taskId, WorkflowHandleRequest request) {
        WorkflowTask task = gateway.requireHandlableTask(taskId);
        WorkflowInstance instance = gateway.requireInstance(task.instanceId());
        gateway.claim(taskId);
        recordService.record(Trace.ofTask(WorkflowTaskRecordService.ACTION_CLAIM, task,
                recordService.businessTypeOf(task.instanceId()), instance.businessKey(), request.comment()));
    }

    /** 通过:办理并推进流程,结果变量为 true */
    @Transactional
    public void approve(String taskId, WorkflowHandleRequest request) {
        handle(taskId, request.comment(), true);
    }

    /** 驳回:同样是办理动作,只是把流程推向"不通过"那一侧 */
    @Transactional
    public void reject(String taskId, WorkflowHandleRequest request) {
        handle(taskId, request.comment(), false);
    }

    /**
     * 转办:改派给指定人并留痕。目标人先过用户档案校验——把单子派给不存在的人或别的租户,
     * 引擎不会报错,只会静默产出一条谁也办不了的待办。
     */
    @Transactional
    public void transfer(String taskId, WorkflowTransferRequest request) {
        WorkflowTask task = gateway.requireHandlableTask(taskId);
        WorkflowInstance instance = gateway.requireInstance(task.instanceId());
        UserBrief target = userBriefPort.briefs(List.of(request.targetUserId())).stream()
                .findFirst()
                .orElseThrow(() -> BizException.badRequest("转办目标用户不存在或不在当前租户"));
        gateway.transfer(taskId, String.valueOf(request.targetUserId()));
        recordService.record(Trace.ofTransfer(task, recordService.businessTypeOf(task.instanceId()),
                instance.businessKey(), target.userId(), target.nickname(), request.comment()));
        notifyService.notifyUsers("转办提醒:" + blankTo(instance.name(), "流程任务"),
                "有单子转给您:「" + blankTo(task.name(), task.taskDefinitionKey()) + "」,缘由:"
                        + blankTo(request.comment(), "未填写") + "。"
                        + WorkflowNotifyService.coordinate(instance.businessKey()),
                List.of(target.userId()));
    }

    /** 办理的共同路径:取坐标→推进引擎→落台账→按后续节点情况发提醒 */
    private void handle(String taskId, String comment, boolean approved) {
        WorkflowTask task = gateway.requireHandlableTask(taskId);
        WorkflowInstance instance = gateway.requireInstance(task.instanceId());
        String businessType = recordService.businessTypeOf(task.instanceId());
        gateway.complete(taskId, Map.of(VARIABLE_APPROVED, approved));
        recordService.record(Trace.ofTask(approved
                        ? WorkflowTaskRecordService.ACTION_APPROVE : WorkflowTaskRecordService.ACTION_REJECT,
                task, businessType, instance.businessKey(), comment));

        List<WorkflowTask> nextTasks = gateway.findTasksOfInstance(instance.id());
        if (nextTasks.isEmpty()) {
            notifyOutcome(instance, approved);
            // 流程走完:把结果交给认领该业务类型的模块。没挂业务的裸流程 businessType 为空,派发即静默跳过
            outcomeDispatcher.dispatch(businessType, instance.businessKey(), instance.id(),
                    approved ? WorkflowStatus.APPROVED : WorkflowStatus.REJECTED);
            return;
        }
        nextTasks.forEach(next -> notifyService.remindTodo(next, instance.name(), instance.businessKey()));
    }

    /** 流程办结:告知发起人结果。发起人自己办的最后一 step 不给自己发通知 */
    private void notifyOutcome(WorkflowInstance instance, boolean approved) {
        Long initiatorId = WorkflowTodoVO.toUserId(instance.startUserId());
        Long actorId = LoginUserContextHolder.userId();
        if (initiatorId == null || Objects.equals(initiatorId, actorId)) {
            return;
        }
        notifyService.notifyUsers("流程" + (approved ? "已通过" : "被驳回") + ":" + blankTo(instance.name(), "流程"),
                "您发起的「" + blankTo(instance.name(), "流程") + "」" + (approved ? "已审批通过。" : "已被驳回。")
                        + WorkflowNotifyService.coordinate(instance.businessKey()),
                List.of(initiatorId));
    }

    /** 一页任务对应的实例视图,按实例 ID 批量取 */
    private Map<String, WorkflowInstance> instancesOf(List<WorkflowTask> tasks) {
        List<String> instanceIds = tasks.stream().map(WorkflowTask::instanceId).distinct().toList();
        return gateway.findInstancesByIds(instanceIds).stream()
                .collect(Collectors.toMap(WorkflowInstance::id, Function.identity()));
    }

    /**
     * 展示名批量取档:一页里出现的发起人(来自实例视图)与办理人(来自任务视图)一次查完。
     * ID 解析不了的(系统/异步触发的空身份、脏数据)静默缺席,列表照常出——展示名缺一个不如整行还在。
     */
    private Map<Long, String> namesOf(List<WorkflowTask> tasks, Map<String, WorkflowInstance> instances) {
        List<String> rawIds = new ArrayList<>();
        tasks.forEach(task -> rawIds.add(task.assignee()));
        instances.values().forEach(instance -> rawIds.add(instance.startUserId()));
        Set<Long> ids = new LinkedHashSet<>();
        rawIds.stream().map(WorkflowTodoVO::toUserId).filter(Objects::nonNull).forEach(ids::add);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userBriefPort.briefs(ids).stream()
                .filter(brief -> brief.nickname() != null)
                .collect(Collectors.toMap(UserBrief::userId, UserBrief::nickname));
    }

    /** 引擎分页是 offset/limit,页码在这里换算 */
    private static int offset(WorkflowTaskQuery query) {
        return (query.pageNum() - 1) * query.pageSize();
    }

    private static String blankTo(String raw, String fallback) {
        return raw == null || raw.isBlank() ? fallback : raw;
    }
}
