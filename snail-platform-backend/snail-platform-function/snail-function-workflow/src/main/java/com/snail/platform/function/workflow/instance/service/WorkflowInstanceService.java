package com.snail.platform.function.workflow.instance.service;

import com.snail.platform.framework.core.context.LoginUserContextHolder;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.workflow.engine.WorkflowEngineGateway;
import com.snail.platform.framework.workflow.engine.WorkflowInstance;
import com.snail.platform.framework.workflow.engine.WorkflowTask;
import com.snail.platform.framework.workflow.engine.WorkflowTraceStep;
import com.snail.platform.function.system.api.UserBriefPort;
import com.snail.platform.function.workflow.api.WorkflowApi;
import com.snail.platform.function.workflow.api.WorkflowStartCommand;
import com.snail.platform.function.workflow.api.WorkflowStatus;
import com.snail.platform.function.workflow.instance.dto.WorkflowInstanceQuery;
import com.snail.platform.function.workflow.instance.dto.WorkflowInstanceVO;
import com.snail.platform.function.workflow.instance.dto.WorkflowStartRequest;
import com.snail.platform.function.workflow.instance.dto.WorkflowTraceVO;
import com.snail.platform.function.workflow.task.entity.SysWorkflowTaskRecord;
import com.snail.platform.function.workflow.task.service.WorkflowNotifyService;
import com.snail.platform.function.workflow.task.service.WorkflowTaskRecordService;
import com.snail.platform.function.workflow.task.service.WorkflowTaskRecordService.Trace;
import com.snail.platform.function.workflow.support.WorkflowOutcomeDispatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 职责:实例域的编排层——发起、撤销、我发起的、详情、轨迹,同时是 WorkflowApi 的实现(方案 §7)。
 * 上游:WorkflowInstanceController 与各业务模块注入的 WorkflowApi;
 * 下游:framework-workflow 网关、审批台账、结果回调派发、待办提醒、用户档案端口。
 * 口径:
 * 1) 发起与业务写单同事务(引擎与业务共用一个事务管理器,P0 已实证),任一半失败即整体回滚;
 * 2) 一单一条流程按 businessType + businessKey 判,依据是审批台账而不是引擎的 businessKey——
 *    引擎不认识业务类型,两个业务域撞同一个单号时按引擎查会互相挡住;
 * 3) 已结束实例的业务结果由台账最后一个定结果动作反推;没有任何定局动作就是流程自己走完了,即通过;
 * 4) 撤销只认发起人——别人的流程能被别人撤走不是功能,是漏洞;
 * 5) 结果回调排在事务提交之后派发,派发失败不影响已经成立的流程动作。
 */
@Service
public class WorkflowInstanceService implements WorkflowApi {

    /** 单据标题变量名:网关按它给实例命名,列表与提醒都读实例名 */
    private static final String VARIABLE_BUSINESS_TITLE = "businessTitle";

    /** 引擎活动类型:只有用户任务可承载驳回动作 */
    private static final String ACTIVITY_TYPE_USER_TASK = "userTask";

    /** 流程图节点状态:已通过 / 当前停留 / 驳回路径 / 撤销终止点 */
    private static final String NODE_DONE = "DONE";
    private static final String NODE_ACTIVE = "ACTIVE";
    private static final String NODE_REJECTED = "REJECTED";
    private static final String NODE_WITHDRAWN = "WITHDRAWN";

    /** 流程引擎网关 */
    private final WorkflowEngineGateway gateway;

    /** 审批台账:发起与撤销在此落账,业务关联键也从这里读 */
    private final WorkflowTaskRecordService recordService;

    /** 提交后提醒 */
    private final WorkflowNotifyService notifyService;

    /** 结果回调派发单点 */
    private final WorkflowOutcomeDispatcher outcomeDispatcher;

    /** 用户档案端口:轨迹页在途节点的现值显示名 */
    private final UserBriefPort userBriefPort;

    public WorkflowInstanceService(WorkflowEngineGateway gateway, WorkflowTaskRecordService recordService,
                                   WorkflowNotifyService notifyService, WorkflowOutcomeDispatcher outcomeDispatcher,
                                   UserBriefPort userBriefPort) {
        this.gateway = gateway;
        this.recordService = recordService;
        this.notifyService = notifyService;
        this.outcomeDispatcher = outcomeDispatcher;
        this.userBriefPort = userBriefPort;
    }

    /**
     * 业务侧发起入口(WorkflowApi):落台账、起实例、提醒首节点收件人。
     * businessKey 非空时先判重,已有在途即拒绝——重复提交一张单子必须由平台挡,不能指望每个业务模块自觉。
     */
    @Transactional
    @Override
    public StartResult start(WorkflowStartCommand command) {
        if (command == null || isBlank(command.definitionKey())) {
            throw BizException.badRequest("缺少流程标识,无法发起");
        }
        requireBusinessPair(command.businessType(), command.businessKey());
        String businessType = trimToNull(command.businessType());
        String businessKey = trimToNull(command.businessKey());
        rejectIfAlreadyRunning(businessType, businessKey);

        WorkflowInstance instance = gateway.start(command.definitionKey().trim(), businessKey,
                variablesOf(command));
        recordService.record(Trace.ofInstance(WorkflowTaskRecordService.ACTION_START, instance.id(),
                businessType, businessKey, null));
        gateway.findTasksOfInstance(instance.id())
                .forEach(next -> {
                    notifyService.remindTodo(next, instance.name(), businessKey);
                    notifyService.notifyCc(next, instance.name(), businessKey);
                });
        return new StartResult(instance.id(), instance.definitionId());
    }

    /** REST 发起:与业务发起走同一条链路,只是入参形态不同 */
    @Transactional
    public StartResult start(WorkflowStartRequest request) {
        return start(new WorkflowStartCommand(request.definitionKey(), request.businessType(),
                request.businessKey(), request.businessTitle(), request.variables()));
    }

    /** 按业务单撤销(WorkflowApi):业务侧只知道自己那张单的键,不应该存引擎实例 ID */
    @Transactional
    @Override
    public void withdraw(String businessType, String businessKey, String reason) {
        requireBusinessPair(businessType, businessKey);
        WorkflowInstance instance = latestInstanceOf(businessType, businessKey);
        if (instance == null) {
            throw BizException.badRequest("该单据没有可撤销的流程");
        }
        withdrawInstance(instance.id(), reason);
    }

    /** 按实例 ID 撤销(REST /workflow/instance/{instanceId}/withdraw) */
    @Transactional
    public void withdraw(String instanceId, String reason) {
        withdrawInstance(instanceId, reason);
    }

    /** 当前流程状态(WorkflowApi);单据从未进流程返回 NONE */
    @Override
    public WorkflowStatus status(String businessType, String businessKey) {
        requireBusinessPair(businessType, businessKey);
        WorkflowInstance instance = latestInstanceOf(businessType, businessKey);
        return instance == null ? WorkflowStatus.NONE : statusOf(instance);
    }

    /** 我发起的:引擎历史实例分页,状态按台账批量反推,一页数据两次查询 */
    public PageResult<WorkflowInstanceVO> pageStarted(WorkflowInstanceQuery query) {
        long total = gateway.countStartedByMe(trimToNull(query.definitionKey()));
        List<WorkflowInstance> instances =
                gateway.findStartedByMe(trimToNull(query.definitionKey()), offset(query), query.pageSize());
        if (instances.isEmpty()) {
            return PageResult.of(List.of(), total);
        }
        Map<String, String> actions = recordService.lastOutcomeActionsOf(
                instances.stream().map(WorkflowInstance::id).toList());
        List<WorkflowInstanceVO> rows = instances.stream()
                .map(instance -> WorkflowInstanceVO.of(instance,
                        instance.ended() ? terminalStatus(actions.get(instance.id())) : WorkflowStatus.RUNNING))
                .toList();
        return PageResult.of(rows, total);
    }

    /** 实例详情:归属校验由网关的租户过滤兜底,跨租户读取视作不存在 */
    public WorkflowInstanceVO detail(String instanceId) {
        WorkflowInstance instance = gateway.requireInstance(instanceId);
        return WorkflowInstanceVO.of(instance, statusOf(instance));
    }

    /**
     * 轨迹页:已发生的动作读台账(快照口径),当前停在哪里读引擎(现值口径)。
     * 两段拼在一屏是刻意的——"当时谁办的"与"现在卡在谁手里"是两个问题,用同一张表答必然有一个是错的。
     * includeDiagram 由详情页显式打开:列表摘要只要步骤数与在途节点,不该为每一行付出定义原文的代价。
     */
    public WorkflowTraceVO trace(String instanceId, boolean includeDiagram) {
        WorkflowInstance instance = gateway.requireInstance(instanceId);
        List<WorkflowTraceVO.Step> steps = recordService.listByInstance(instance.id()).stream()
                .map(WorkflowInstanceService::stepOf)
                .toList();
        WorkflowStatus status = statusOf(instance);
        List<WorkflowTraceVO.Waiting> waiting = waitingOf(gateway.findTasksOfInstance(instance.id()));
        return new WorkflowTraceVO(WorkflowInstanceVO.of(instance, status), steps, waiting,
                includeDiagram ? diagramOf(instance, status, steps, waiting) : null);
    }

    /** 撤销的共同路径:发起人校验→抓在册任务→终止引擎→落台账→通知当时在等的人→派发结果 */
    private void withdrawInstance(String instanceId, String reason) {
        WorkflowInstance instance = gateway.requireInstance(instanceId);
        if (instance.ended()) {
            throw BizException.badRequest("流程已结束,无法撤销");
        }
        requireInitiator(instance);
        // 撤销前先抓在册任务:实例一旦终止,待办与它们的收件人就从引擎里消失了,事后再查是空集
        List<WorkflowTask> openTasks = gateway.findTasksOfInstance(instance.id());
        String businessType = recordService.businessTypeOf(instance.id());
        gateway.withdraw(instance.id(), reason);
        recordService.record(Trace.ofInstance(WorkflowTaskRecordService.ACTION_WITHDRAW, instance.id(),
                businessType, instance.businessKey(), reason));
        notifyService.notifyWithdrawn(openTasks, instance.name(), instance.businessKey());
        outcomeDispatcher.dispatch(businessType, instance.businessKey(), instance.id(), WorkflowStatus.WITHDRAWN);
    }

    /** 只有发起人可以撤:引擎身份是字符串,解析不了(系统触发)即无人可撤 */
    private static void requireInitiator(WorkflowInstance instance) {
        Long initiatorId = parseUserId(instance.startUserId());
        Long currentUserId = LoginUserContextHolder.userId();
        if (initiatorId == null || !Objects.equals(initiatorId, currentUserId)) {
            throw BizException.forbidden("只有发起人可以撤销这条流程");
        }
    }

    /** 实例视图 → 状态:在途即 RUNNING,已结束按台账定局动作反推 */
    private WorkflowStatus statusOf(WorkflowInstance instance) {
        return instance.ended()
                ? terminalStatus(recordService.lastOutcomeActionOf(instance.id()))
                : WorkflowStatus.RUNNING;
    }

    /**
     * 台账定局动作 → 状态。没有任何定局动作的已结束实例按"通过"处理:
     * 驳回与撤销都会写下自己的动作行,一行都没有说明流程一路走完到结束事件,没有人为拦住它。
     */
    private static WorkflowStatus terminalStatus(String lastOutcomeAction) {
        if (WorkflowTaskRecordService.ACTION_REJECT.equals(lastOutcomeAction)) {
            return WorkflowStatus.REJECTED;
        }
        if (WorkflowTaskRecordService.ACTION_WITHDRAW.equals(lastOutcomeAction)) {
            return WorkflowStatus.WITHDRAWN;
        }
        return WorkflowStatus.APPROVED;
    }

    /** 一单一条:同业务类型同单号已有在途实例即拒绝发起 */
    private void rejectIfAlreadyRunning(String businessType, String businessKey) {
        if (businessType == null || businessKey == null) {
            return;
        }
        WorkflowInstance running = latestInstanceOf(businessType, businessKey);
        if (running != null && !running.ended()) {
            throw BizException.badRequest("该单据已有在途流程,请勿重复发起");
        }
    }

    /**
     * 业务单当前关联的实例:台账里出现过的实例 ID 一次取回,按发起时刻取最新一条。
     * 驳回后重新提交会产生第二条实例,列表与状态都要看最新那条;查不到即从未进过流程。
     */
    private WorkflowInstance latestInstanceOf(String businessType, String businessKey) {
        Set<String> instanceIds = new LinkedHashSet<>();
        recordService.listByBusiness(businessType, businessKey).stream()
                .map(SysWorkflowTaskRecord::getProcessInstanceId)
                .filter(Objects::nonNull)
                .forEach(instanceIds::add);
        if (instanceIds.isEmpty()) {
            return null;
        }
        return gateway.findInstancesByIds(List.copyOf(instanceIds)).stream()
                .max(Comparator.comparing(WorkflowInstance::startTime,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    /** 业务类型与单号必须同起同落:只有一个会造出"有单号没归属域"的孤儿关联 */
    private static void requireBusinessPair(String businessType, String businessKey) {
        if (isBlank(businessType) != isBlank(businessKey)) {
            throw BizException.badRequest("业务类型与业务单号必须同时提供或同时留空");
        }
    }

    /** 标题写进 businessTitle 变量:网关用它给实例命名,业务侧不必知道这个变量名 */
    private static Map<String, Object> variablesOf(WorkflowStartCommand command) {
        Map<String, Object> variables = new LinkedHashMap<>();
        if (command.variables() != null) {
            variables.putAll(command.variables());
        }
        if (!isBlank(command.title())) {
            variables.put(VARIABLE_BUSINESS_TITLE, command.title().trim());
        }
        return variables;
    }

    /** 台账行 → 轨迹一步:显示名用台账快照,不回查档案 */
    private static WorkflowTraceVO.Step stepOf(SysWorkflowTaskRecord row) {
        return new WorkflowTraceVO.Step(row.getAction(), row.getTaskId(), row.getTaskName(),
                row.getOperatorId(), row.getOperatorNickname(), row.getTargetUserNickname(),
                row.getComment(), row.getCreatedAt());
    }

    /** 在途节点 → 等待人:办理人现值名批量取一次,未签收的留空由前端写"待候选角色办理" */
    private List<WorkflowTraceVO.Waiting> waitingOf(List<WorkflowTask> tasks) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        List<Long> assigneeIds = new ArrayList<>();
        tasks.forEach(task -> {
            Long assigneeId = parseUserId(task.assignee());
            if (assigneeId != null) {
                assigneeIds.add(assigneeId);
            }
        });
        Map<Long, String> names = new LinkedHashMap<>();
        userBriefPort.briefs(assigneeIds).stream()
                .filter(brief -> brief.nickname() != null)
                .forEach(brief -> names.put(brief.userId(), brief.nickname()));
        return tasks.stream()
                .map(task -> {
                    Long assigneeId = parseUserId(task.assignee());
                    return new WorkflowTraceVO.Waiting(task.id(), task.taskDefinitionKey(), task.name(),
                            assigneeId, assigneeId == null ? null : names.get(assigneeId));
                })
                .toList();
    }

    /**
     * 流程图数据:定义原文 + 已发生节点的状态。
     * 节点状态按实例结果推导——通过把走过的节点全标完成;驳回从驳回任务起把后续路径染成驳回;
     * 撤销把最后停留的用户任务标成终止点;在途实例按历史活动是否有结束时刻区分已完成与当前节点。
     * 定义原文读取失败只让流程图降级为空态,不拖垮轨迹主体。
     */
    private WorkflowTraceVO.Diagram diagramOf(WorkflowInstance instance, WorkflowStatus status,
                                              List<WorkflowTraceVO.Step> steps,
                                              List<WorkflowTraceVO.Waiting> waiting) {
        List<WorkflowTraceStep> activities = gateway.findTrace(instance.id()).stream()
                .filter(activity -> activity.activityId() != null && !activity.activityId().isBlank())
                .toList();
        Map<String, String> states = new LinkedHashMap<>();
        if (status == WorkflowStatus.RUNNING) {
            activities.forEach(activity -> mergeNodeState(states, activity.activityId(),
                    activity.endTime() == null ? NODE_ACTIVE : NODE_DONE));
            waiting.forEach(task -> mergeNodeState(states, task.activityId(), NODE_ACTIVE));
        } else if (status == WorkflowStatus.REJECTED) {
            int rejectIndex = rejectActivityIndex(activities, steps);
            for (int i = 0; i < activities.size(); i++) {
                mergeNodeState(states, activities.get(i).activityId(),
                        i >= rejectIndex ? NODE_REJECTED : NODE_DONE);
            }
        } else if (status == WorkflowStatus.WITHDRAWN) {
            int endIndex = lastUserTaskIndex(activities);
            for (int i = 0; i < activities.size(); i++) {
                mergeNodeState(states, activities.get(i).activityId(),
                        i == endIndex ? NODE_WITHDRAWN : NODE_DONE);
            }
        } else {
            activities.forEach(activity -> mergeNodeState(states, activity.activityId(), NODE_DONE));
        }

        List<WorkflowTraceVO.Node> nodes = states.entrySet().stream()
                .map(entry -> new WorkflowTraceVO.Node(entry.getKey(), entry.getValue()))
                .toList();
        String xml = null;
        try {
            xml = gateway.findDefinitionXml(instance.definitionId());
        } catch (RuntimeException ignored) {
            // 定义原文缺失或不可读:流程图空态,轨迹主体与在途节点照常展示
        }
        return new WorkflowTraceVO.Diagram(xml, nodes);
    }

    /** 驳回任务在活动历史里的位置:优先按任务 ID 对齐,退而按节点名找最后一个同名用户任务 */
    private static int rejectActivityIndex(List<WorkflowTraceStep> activities, List<WorkflowTraceVO.Step> steps) {
        WorkflowTraceVO.Step reject = null;
        for (WorkflowTraceVO.Step step : steps) {
            if (WorkflowTaskRecordService.ACTION_REJECT.equals(step.action())) {
                reject = step;
            }
        }
        if (reject == null) {
            return activities.size();
        }
        if (reject.taskId() != null) {
            for (int i = 0; i < activities.size(); i++) {
                if (reject.taskId().equals(activities.get(i).taskId())) {
                    return i;
                }
            }
        }
        if (reject.taskName() != null) {
            for (int i = activities.size() - 1; i >= 0; i--) {
                WorkflowTraceStep activity = activities.get(i);
                if (ACTIVITY_TYPE_USER_TASK.equals(activity.type())
                        && reject.taskName().equals(activity.name())) {
                    return i;
                }
            }
        }
        return Math.max(lastUserTaskIndex(activities), 0);
    }

    /** 最后一个用户任务的位置:撤销发生在哪个节点,就把它标成终止点 */
    private static int lastUserTaskIndex(List<WorkflowTraceStep> activities) {
        for (int i = activities.size() - 1; i >= 0; i--) {
            if (ACTIVITY_TYPE_USER_TASK.equals(activities.get(i).type())) {
                return i;
            }
        }
        return -1;
    }

    /** 同一节点多次经过时保留语义更强的一次:当前 > 驳回 > 撤销 > 已通过 */
    private static void mergeNodeState(Map<String, String> states, String activityId, String state) {
        if (activityId == null || activityId.isBlank()) {
            return;
        }
        states.merge(activityId, state,
                (current, next) -> nodeStateRank(next) >= nodeStateRank(current) ? next : current);
    }

    private static int nodeStateRank(String state) {
        if (NODE_ACTIVE.equals(state)) {
            return 3;
        }
        if (NODE_REJECTED.equals(state)) {
            return 2;
        }
        if (NODE_WITHDRAWN.equals(state)) {
            return 1;
        }
        return 0;
    }

    /** 引擎身份字符串 → 平台用户 ID;非数字按缺失处理,不让一行展示因为一个脏 id 整页失败 */
    private static Long parseUserId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int offset(WorkflowInstanceQuery query) {
        return (query.pageNum() - 1) * query.pageSize();
    }

    private static boolean isBlank(String raw) {
        return raw == null || raw.isBlank();
    }

    private static String trimToNull(String raw) {
        return isBlank(raw) ? null : raw.trim();
    }
}
