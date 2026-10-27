package com.snail.platform.framework.workflow.engine;

import com.snail.platform.framework.core.context.LoginUser;
import com.snail.platform.framework.core.context.LoginUserContextHolder;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.workflow.bpmn.BpmnSecurityValidator;
import com.snail.platform.framework.workflow.support.WorkflowEngineErrors;
import com.snail.platform.framework.workflow.support.WorkflowTimes;
import com.snail.platform.framework.workflow.support.WorkflowVariablePolicy;
import com.snail.platform.framework.workflow.tenant.WorkflowTenantIds;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.springframework.stereotype.Component;

/**
 * 职责:流程引擎在平台侧的唯一门面——引擎的一切读写只能从这里进(方案 §2、§3)。
 * 上游:snail-function-workflow(它不认识 Flowable,只调本类);
 * 下游:Flowable 的 RepositoryService/RuntimeService/TaskService/HistoryService。
 * 本类把四件事钉死在同一处,任何一件漏出去都是隔离缺口:
 * 1) 租户——每个动作先取当前线程租户,写侧显式带 tenantId,读侧显式加租户过滤,缺失即拒(§3.1);
 * 2) 身份——引擎的"认证用户"从登录人快照注入,只在这层生效,结束必清,异步线程无权写(§3.2);
 * 3) 变量——进出都过 WorkflowVariablePolicy,只准标量;
 * 4) 异常——引擎异常在本层翻成 BizException,Flowable 类型不外泄(§2 边界)。
 * 约束:不声明 @Transactional(约定 §3:事务边界只在 function service),不参与 HTTP/通知等外部调用;
 * 引擎与业务共用同一 DataSource 与事务管理器,本层的写入自动加入调用方事务(P0 已实证)。
 */
@Component
public class WorkflowEngineGateway {

    /** 发起人变量名:BPMN 里以 ${initiator} 绑审批人,只能由网关写,不接受调用方传值 */
    private static final String VARIABLE_INITIATOR = "initiator";

    /** 单据标题变量名:业务发起时带入,同时用作流程实例名——实例没有可读标题,列表与通知就无从展示 */
    private static final String VARIABLE_BUSINESS_TITLE = "businessTitle";

    /** 引擎候选类型常量(避免引 identitylink 内部包) */
    private static final String IDENTITY_LINK_CANDIDATE = "candidate";

    /** 部署资源名后缀:Flowable 按扩展名判定 BPMN 解析器 */
    private static final String RESOURCE_SUFFIX = ".bpmn20.xml";

    /** 定义仓储服务:部署与定义查询 */
    private final RepositoryService repositoryService;

    /** 运行时服务:发起、撤销与在途查询 */
    private final RuntimeService runtimeService;

    /** 任务服务:待办与办理 */
    private final TaskService taskService;

    /** 历史服务:已办、实例轨迹与已结束实例查询 */
    private final HistoryService historyService;

    /** BPMN 安全闸门:部署前必过 */
    private final BpmnSecurityValidator bpmnSecurityValidator;

    public WorkflowEngineGateway(RepositoryService repositoryService, RuntimeService runtimeService,
                                 TaskService taskService, HistoryService historyService,
                                 BpmnSecurityValidator bpmnSecurityValidator) {
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.historyService = historyService;
        this.bpmnSecurityValidator = bpmnSecurityValidator;
    }

    /**
     * 部署一份流程定义:先过 BPMN 安全校验,再带租户部署。
     * 校验放在引擎之前是刻意的——BpmnParse 一旦被恶意 XML 走通,部署记录已经落库,来不及收回。
     * 一次部署只允许产出一个定义:模型的"当前版本指针"(P2)按单定义写回,多定义会让指针含糊。
     */
    public WorkflowDeployment deploy(String name, String bpmnXml) {
        String tenantId = WorkflowTenantIds.requireEngineTenant();
        String processKey = bpmnSecurityValidator.validate(bpmnXml);
        return WorkflowEngineErrors.supply("部署流程定义", () -> {
            Deployment deployment = repositoryService.createDeployment()
                    .name(name)
                    .addString("bpmn/" + processKey + RESOURCE_SUFFIX, bpmnXml)
                    .tenantId(tenantId)
                    .deploy();
            List<ProcessDefinition> definitions = repositoryService.createProcessDefinitionQuery()
                    .deploymentId(deployment.getId())
                    .list();
            if (definitions.size() != 1) {
                throw BizException.badRequest("一次部署必须且只能产出一个流程,当前 " + definitions.size() + " 个");
            }
            ProcessDefinition definition = definitions.get(0);
            return new WorkflowDeployment(deployment.getId(), definition.getId(),
                    definition.getKey(), definition.getVersion());
        });
    }

    /**
     * 按流程标识发起实例。
     * initiator 由网关注入并覆盖调用方同名字段:审批人绑 ${initiator} 时,
     * 若允许调用方自选发起人,就等于允许把待办投给任意人。
     * 实例标题按 businessTitle 落,缺省退回定义名:引擎默认不给实例起名,不显式设就处处是 null——
     * 待办列表与提醒文案都会写成"null",这一层不补就没有第二处能补。
     */
    public WorkflowInstance start(String definitionKey, String businessKey, Map<String, Object> variables) {
        String tenantId = WorkflowTenantIds.requireEngineTenant();
        String initiator = requireLoginUserId();
        Map<String, Object> safeVariables = WorkflowVariablePolicy.sanitize(
                withInitiator(variables, initiator));
        WorkflowDefinition definition = latestDefinition(definitionKey, tenantId);
        String title = titleOf(safeVariables, definition);
        return WorkflowEngineErrors.supply("发起流程", () -> {
            ProcessInstance instance = supplyAsUser(initiator, () -> runtimeService.createProcessInstanceBuilder()
                    .processDefinitionId(definition.id())
                    .businessKey(businessKey)
                    .name(title)
                    .variables(safeVariables)
                    .start());
            return toRunningInstance(instance);
        });
    }

    /** 当前租户下某个流程标识的最新定义;不存在即视作不可发起(与按 key 启动的引擎报错同口径) */
    private WorkflowDefinition latestDefinition(String definitionKey, String tenantId) {
        return WorkflowEngineErrors.supply("查询流程定义", () -> {
            ProcessDefinition latest = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionKey(definitionKey)
                    .processDefinitionTenantId(tenantId)
                    .latestVersion()
                    .orderByProcessDefinitionVersion().desc()
                    .list().stream().findFirst()
                    .orElseThrow(() -> BizException.badRequest("流程定义不存在或不属于当前租户:" + definitionKey));
            return new WorkflowDefinition(latest.getId(), latest.getKey(), latest.getName(),
                    latest.getVersion(), latest.getDeploymentId(), latest.isSuspended());
        });
    }

    /** businessTitle 是平台与业务约定的人员可读标题(方案 §7);不是字符串或没给就用定义名 */
    private static String titleOf(Map<String, Object> variables, WorkflowDefinition definition) {
        Object raw = variables.get(VARIABLE_BUSINESS_TITLE);
        if (raw instanceof String title && !title.isBlank()) {
            return title.trim();
        }
        return definition.name();
    }

    /**
     * 办理并推进流程:办理权在本层校验,引擎自身不查 assignee(方案 §6.1)。
     * 未签收的候选任务在这里"办理即签收":引擎的 ASSIGNEE_ 是已办查询与历史轨迹的办理人口径,
     * 留空完成就等于把"谁办的"从引擎里抹掉,已办列表与轨迹都会查无此人。
     */
    public void complete(String taskId, Map<String, Object> variables) {
        WorkflowTask task = requireHandlableTask(taskId);
        Map<String, Object> safeVariables = WorkflowVariablePolicy.sanitize(variables);
        String operator = requireLoginUserId();
        WorkflowEngineErrors.run("办理任务", () -> runAsUser(operator, () -> {
            if (task.assignee() == null) {
                taskService.setAssignee(task.id(), operator);
            }
            taskService.complete(task.id(), safeVariables);
        }));
    }

    /** 签收:把未指派的任务领到自己名下,候选角色内才允许 */
    public void claim(String taskId) {
        WorkflowTask task = requireHandlableTask(taskId);
        String operator = requireLoginUserId();
        WorkflowEngineErrors.run("签收任务", () ->
                runAsUser(operator, () -> taskService.claim(task.id(), operator)));
    }

    /**
     * 转办:改派办理人。
     * 目标用户是否存在、是否同租户由业务 service 校验——只有它拿得到用户档案,
     * 网关回查用户会把 framework 层拖进业务表(方案 §2:显示名不由引擎层回查)。
     */
    public void transfer(String taskId, String targetUserId) {
        WorkflowTask task = requireHandlableTask(taskId);
        if (targetUserId == null || targetUserId.isBlank()) {
            throw BizException.badRequest("转办目标办理人不能为空");
        }
        String operator = requireLoginUserId();
        WorkflowEngineErrors.run("转办任务", () ->
                runAsUser(operator, () -> taskService.setAssignee(task.id(), targetUserId)));
    }

    /** 撤销在途实例(业务侧的"撤回申请");已结束的实例由引擎抛对象不存在,统一翻成 400 */
    public void withdraw(String processInstanceId, String reason) {
        WorkflowInstance instance = requireRunningInstance(processInstanceId);
        String operator = requireLoginUserId();
        WorkflowEngineErrors.run("撤销流程", () -> runAsUser(operator,
                () -> runtimeService.deleteProcessInstance(instance.id(),
                        reason == null || reason.isBlank() ? "撤销" : reason)));
    }

    /** 当前租户下某个流程标识的定义列表;latestOnly=true 时每个 key 只留最新版本 */
    public List<WorkflowDefinition> findDefinitions(String definitionKey, boolean latestOnly) {
        String tenantId = WorkflowTenantIds.requireEngineTenant();
        return WorkflowEngineErrors.supply("查询流程定义", () -> {
            var query = repositoryService.createProcessDefinitionQuery().processDefinitionTenantId(tenantId);
            if (definitionKey != null && !definitionKey.isBlank()) {
                query = query.processDefinitionKey(definitionKey);
            }
            if (latestOnly) {
                query = query.latestVersion();
            }
            return query.orderByProcessDefinitionKey().asc().orderByProcessDefinitionVersion().desc().list()
                    .stream()
                    .map(definition -> new WorkflowDefinition(definition.getId(), definition.getKey(),
                            definition.getName(), definition.getVersion(), definition.getDeploymentId(),
                            definition.isSuspended()))
                    .toList();
        });
    }

    /** 取当前租户下的单个定义(按定义 ID);跨租户读取一律视作不存在 */
    public WorkflowDefinition requireDefinition(String definitionId) {
        String tenantId = WorkflowTenantIds.requireEngineTenant();
        return WorkflowEngineErrors.supply("查询流程定义", () -> {
            ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionId(definitionId)
                    .processDefinitionTenantId(tenantId)
                    .singleResult();
            if (definition == null) {
                throw BizException.badRequest("流程定义不存在或不属于当前租户");
            }
            return new WorkflowDefinition(definition.getId(), definition.getKey(), definition.getName(),
                    definition.getVersion(), definition.getDeploymentId(), definition.isSuspended());
        });
    }

    /**
     * 挂起当前租户下的某个定义:挂起后不能再发起新实例。
     * 用单参重载是刻意的——带 suspendProcessInstances 的重载会连带挂住在途实例与它们的作业,
     * 那是"中止在跑的单子",不属于模型停用这个动作。
     */
    public void suspendDefinition(String definitionId) {
        requireDefinition(definitionId);
        WorkflowEngineErrors.run("挂起流程定义",
                () -> repositoryService.suspendProcessDefinitionById(definitionId));
    }

    /** 激活被挂起的定义,恢复可发起 */
    public void activateDefinition(String definitionId) {
        requireDefinition(definitionId);
        WorkflowEngineErrors.run("激活流程定义",
                () -> repositoryService.activateProcessDefinitionById(definitionId));
    }

    /** 已发布版本的 BPMN 原文:设计器回显历史版本用,租户归属由 requireDefinition 钉死 */
    public String findDefinitionXml(String definitionId) {
        WorkflowDefinition definition = requireDefinition(definitionId);
        return WorkflowEngineErrors.supply("读取流程定义原文", () -> {
            try (InputStream stream = repositoryService.getProcessModel(definition.id())) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw BizException.badRequest("流程定义原文读取失败:" + e.getMessage());
            }
        });
    }

    /**
     * 某流程标识下是否还有在途实例:删除模型的前置闸(方案 §4.2"删除后 key 永不释放"的另一半)。
     * 按 key 而非当前定义 ID 查:同 key 的每个版本都可能在跑,只查最新版会漏掉没走完的旧版本。
     */
    public boolean hasRunningInstancesOfKey(String definitionKey) {
        String tenantId = WorkflowTenantIds.requireEngineTenant();
        if (definitionKey == null || definitionKey.isBlank()) {
            throw BizException.badRequest("缺少流程标识,无法查在途实例");
        }
        return WorkflowEngineErrors.supply("查询在途实例", () ->
                runtimeService.createProcessInstanceQuery()
                        .processDefinitionKey(definitionKey)
                        .processInstanceTenantId(tenantId)
                        .count() > 0);
    }

    /**
     * 当前用户的待办:办理人是自己,或未签收且命中自己的角色候选组。
     * 两个条件写在同一个 or 里,分页与计数口径天然一致;
     * 角色候选组按 roleId 字符串匹配(方案 §3.1),停用角色不参与(登录人快照只收启用角色)。
     */
    public List<WorkflowTask> findTodo(int firstResult, int maxResults) {
        String tenantId = WorkflowTenantIds.requireEngineTenant();
        String userId = requireLoginUserId();
        List<String> roleIds = currentRoleIds();
        return WorkflowEngineErrors.supply("查询待办", () ->
                todoQuery(tenantId, userId, roleIds).orderByTaskCreateTime().desc()
                        .listPage(firstResult, maxResults).stream().map(this::toTask).toList());
    }

    /** 待办总数,与 findTodo 同一套过滤条件 */
    public long countTodo() {
        String tenantId = WorkflowTenantIds.requireEngineTenant();
        String userId = requireLoginUserId();
        List<String> roleIds = currentRoleIds();
        return WorkflowEngineErrors.supply("统计待办", () -> todoQuery(tenantId, userId, roleIds).count());
    }

    /** 当前用户已办:历史任务里结束时间非空、办理人是自己的那部分,按结束时间倒序 */
    public List<WorkflowTask> findDone(int firstResult, int maxResults) {
        String tenantId = WorkflowTenantIds.requireEngineTenant();
        String userId = requireLoginUserId();
        return WorkflowEngineErrors.supply("查询已办", () -> historyService.createHistoricTaskInstanceQuery()
                .taskTenantId(tenantId)
                .taskAssignee(userId)
                .finished()
                .orderByHistoricTaskInstanceEndTime().desc()
                .listPage(firstResult, maxResults).stream().map(this::toHistoricTask).toList());
    }

    /** 已办总数,与 findDone 同一套过滤条件 */
    public long countDone() {
        String tenantId = WorkflowTenantIds.requireEngineTenant();
        String userId = requireLoginUserId();
        return WorkflowEngineErrors.supply("统计已办", () -> historyService.createHistoricTaskInstanceQuery()
                .taskTenantId(tenantId)
                .taskAssignee(userId)
                .finished()
                .count());
    }

    /** 业务单是否已有在途实例(方案 §7:一单一条流程的判据) */
    public boolean hasRunningInstance(String businessKey) {
        String tenantId = WorkflowTenantIds.requireEngineTenant();
        if (businessKey == null || businessKey.isBlank()) {
            throw BizException.badRequest("缺少业务单号,无法判重");
        }
        return WorkflowEngineErrors.supply("查询在途流程", () -> runtimeService.createProcessInstanceQuery()
                .processInstanceTenantId(tenantId)
                .processInstanceBusinessKey(businessKey)
                .count() > 0);
    }

    /**
     * 按业务单号取当前租户下最近一条实例(在途优先,其次历史最新)。
     * 业务侧的状态查询与"按单撤销"都要它:一个 businessKey 正常只对应一条流程,
     * 但驳回后重新提交会产生第二条,取最新的那条才代表当前状态。
     * 返回 null 表示这个单号从没进过流程。
     */
    public WorkflowInstance findLatestInstance(String businessKey) {
        String tenantId = WorkflowTenantIds.requireEngineTenant();
        if (businessKey == null || businessKey.isBlank()) {
            throw BizException.badRequest("缺少业务单号,无法查询流程状态");
        }
        return WorkflowEngineErrors.supply("查询业务单流程", () -> {
            ProcessInstance running = runtimeService.createProcessInstanceQuery()
                    .processInstanceTenantId(tenantId)
                    .processInstanceBusinessKey(businessKey)
                    .list().stream()
                    .max(Comparator.comparing(ProcessInstance::getStartTime,
                            Comparator.nullsFirst(Comparator.naturalOrder())))
                    .orElse(null);
            if (running != null) {
                return toRunningInstance(running);
            }
            var historic = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceTenantId(tenantId)
                    .processInstanceBusinessKey(businessKey)
                    .orderByProcessInstanceStartTime().desc()
                    .listPage(0, 1).stream().findFirst().orElse(null);
            if (historic == null) {
                return null;
            }
            return new WorkflowInstance(historic.getId(), historic.getProcessDefinitionId(),
                    historic.getProcessDefinitionKey(), historic.getBusinessKey(), historic.getName(),
                    historic.getStartUserId(), List.of(), WorkflowTimes.ofEngine(historic.getStartTime()),
                    WorkflowTimes.ofEngine(historic.getEndTime()), true);
        });
    }

    /**
     * 当前登录人在当前租户发起的实例(在途与已结束都算),按发起时刻倒序分页。
     * 走历史表而不是运行时表:"我发起的"要能看见已经跑完和已经撤销的单子,那些不在运行时表里。
     * definitionKey 非空即只看这一条流程,空白视作不过滤。
     */
    public List<WorkflowInstance> findStartedByMe(String definitionKey, int firstResult, int maxResults) {
        return WorkflowEngineErrors.supply("查询我发起的流程", () ->
                startedQuery(WorkflowTenantIds.requireEngineTenant(), requireLoginUserId(), definitionKey)
                        .orderByProcessInstanceStartTime().desc()
                        .listPage(firstResult, maxResults).stream()
                        .map(row -> new WorkflowInstance(row.getId(), row.getProcessDefinitionId(),
                                row.getProcessDefinitionKey(), row.getBusinessKey(), row.getName(),
                                row.getStartUserId(), List.of(), WorkflowTimes.ofEngine(row.getStartTime()),
                                WorkflowTimes.ofEngine(row.getEndTime()), row.getEndTime() != null))
                        .toList());
    }

    /** 我发起的总数,与 findStartedByMe 同一套过滤条件 */
    public long countStartedByMe(String definitionKey) {
        return WorkflowEngineErrors.supply("统计我发起的流程", () ->
                startedQuery(WorkflowTenantIds.requireEngineTenant(), requireLoginUserId(), definitionKey).count());
    }

    /** 发起人的历史实例查询条件:租户 + startedBy(+ 可选流程标识),分页与计数共用 */
    private HistoricProcessInstanceQuery startedQuery(String tenantId, String userId, String definitionKey) {
        var query = historyService.createHistoricProcessInstanceQuery()
                .processInstanceTenantId(tenantId)
                .startedBy(userId);
        if (definitionKey != null && !definitionKey.isBlank()) {
            query = query.processDefinitionKey(definitionKey.trim());
        }
        return query;
    }

    /** 当前租户下的单个任务(展示与前置校验用;办理请走 requireHandlableTask) */
    public WorkflowTask requireVisibleTask(String taskId) {
        String tenantId = WorkflowTenantIds.requireEngineTenant();
        return WorkflowEngineErrors.supply("查询任务", () -> {
            Task task = taskService.createTaskQuery().taskId(taskId).taskTenantId(tenantId).singleResult();
            if (task == null) {
                throw BizException.badRequest("任务不存在或不属于当前租户");
            }
            return toTask(task);
        });
    }

    /** 当前租户下、且当前用户有权办理的任务 */
    public WorkflowTask requireHandlableTask(String taskId) {
        WorkflowTask task = requireVisibleTask(taskId);
        requireHandlingRight(task);
        return task;
    }

    /** 当前租户下的实例:先在运行时表找,找不到落历史表;两处都没有即视作不存在 */
    public WorkflowInstance requireInstance(String processInstanceId) {
        String tenantId = WorkflowTenantIds.requireEngineTenant();
        return WorkflowEngineErrors.supply("查询流程实例", () -> {
            ProcessInstance running = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .processInstanceTenantId(tenantId)
                    .singleResult();
            if (running != null) {
                return toRunningInstance(running);
            }
            var finished = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .processInstanceTenantId(tenantId)
                    .singleResult();
            if (finished == null) {
                throw BizException.badRequest("流程实例不存在或不属于当前租户");
            }
            return new WorkflowInstance(finished.getId(), finished.getProcessDefinitionId(),
                    finished.getProcessDefinitionKey(), finished.getBusinessKey(), finished.getName(),
                    finished.getStartUserId(), List.of(), WorkflowTimes.ofEngine(finished.getStartTime()),
                    WorkflowTimes.ofEngine(finished.getEndTime()), true);
        });
    }

    /**
     * 按实例 ID 批量取实例视图(当前租户内),在途与已结束都覆盖。
     * 待办列表要把业务单号补进每一行,靠这个一次查完——逐行 requireInstance 就是 N+1。
     * 先查运行时表,剩下的 ID 落历史表:同一批 ID 不会两处都有,所以两次查询都只覆盖缺的那一半。
     * 传空集合直接返回空列表,不发查询。
     */
    public List<WorkflowInstance> findInstancesByIds(List<String> processInstanceIds) {
        String tenantId = WorkflowTenantIds.requireEngineTenant();
        if (processInstanceIds == null || processInstanceIds.isEmpty()) {
            return List.of();
        }
        Set<String> ids = Set.copyOf(processInstanceIds);
        return WorkflowEngineErrors.supply("批量查询流程实例", () -> {
            List<WorkflowInstance> running = runtimeService.createProcessInstanceQuery()
                    .processInstanceIds(ids)
                    .processInstanceTenantId(tenantId)
                    .list().stream()
                    .map(this::toRunningInstance)
                    .toList();
            if (running.size() == ids.size()) {
                return running;
            }
            List<String> rest = ids.stream()
                    .filter(id -> running.stream().noneMatch(row -> row.id().equals(id)))
                    .toList();
            return Stream.concat(running.stream(),
                            historyService.createHistoricProcessInstanceQuery()
                                    .processInstanceTenantId(tenantId)
                                    .processInstanceIds(Set.copyOf(rest))
                                    .list().stream()
                                    .map(finished -> new WorkflowInstance(finished.getId(),
                                            finished.getProcessDefinitionId(), finished.getProcessDefinitionKey(),
                                            finished.getBusinessKey(), finished.getName(), finished.getStartUserId(),
                                            List.of(), WorkflowTimes.ofEngine(finished.getStartTime()),
                                            WorkflowTimes.ofEngine(finished.getEndTime()), true)))
                    .toList();
        });
    }

    /**
     * 某个实例当前的待办任务(租户过滤兜底,别人的实例只会查出空集),供"新待办生成"通知找收件人。
     * 不按人过滤:通知要的是"这个节点归谁",按人过滤就变成给每个登录人各查一遍。
     */
    public List<WorkflowTask> findTasksOfInstance(String processInstanceId) {
        String tenantId = WorkflowTenantIds.requireEngineTenant();
        return WorkflowEngineErrors.supply("查询实例待办", () -> taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskTenantId(tenantId)
                .orderByTaskCreateTime().asc()
                .list().stream().map(this::toTask).toList());
    }

    /**
     * 实例轨迹:按进入时刻正序返回活动实例。
     * 实例 ID 全局唯一且已由 requireInstance 钉过租户,这里不再重复过滤。
     */
    public List<WorkflowTraceStep> findTrace(String processInstanceId) {
        WorkflowInstance instance = requireInstance(processInstanceId);
        return WorkflowEngineErrors.supply("查询流程轨迹", () -> historyService
                .createHistoricActivityInstanceQuery()
                .processInstanceId(instance.id())
                .orderByHistoricActivityInstanceStartTime().asc()
                .list().stream()
                .map(activity -> new WorkflowTraceStep(activity.getActivityId(), activity.getActivityName(),
                        activity.getActivityType(), activity.getAssignee(),
                        WorkflowTimes.ofEngine(activity.getStartTime()),
                        WorkflowTimes.ofEngine(activity.getEndTime()), activity.getDurationInMillis()))
                .toList());
    }

    /** 待办查询条件:租户 + (办理人=我 or 候选角色命中我的角色) */
    private TaskQuery todoQuery(String tenantId, String userId, List<String> roleIds) {
        var query = taskService.createTaskQuery().taskTenantId(tenantId);
        if (roleIds.isEmpty()) {
            return query.taskAssignee(userId);
        }
        return query.or().taskAssignee(userId).taskCandidateGroupIn(roleIds).endOr();
    }

    /** 办理权判定:自己是办理人;或未签收且命中候选角色。他人已签收的任务直接拒绝 */
    private void requireHandlingRight(WorkflowTask task) {
        String userId = requireLoginUserId();
        if (userId.equals(task.assignee())) {
            return;
        }
        if (task.assignee() != null) {
            throw BizException.forbidden("任务已被他人签收或已转办,当前用户无权办理");
        }
        List<String> roleIds = currentRoleIds();
        if (!roleIds.isEmpty() && task.candidateGroups().stream().anyMatch(roleIds::contains)) {
            return;
        }
        throw BizException.forbidden("当前用户不在该任务的候选角色内");
    }

    /** 当前登录人 ID;引擎异步线程没有登录人,写动作在这一步就被拒(方案 §3.2) */
    private String requireLoginUserId() {
        LoginUser loginUser = LoginUserContextHolder.get();
        if (loginUser == null || loginUser.userId() == null || loginUser.tenantId() == null) {
            throw BizException.forbidden("缺少登录人上下文,拒绝流程写操作");
        }
        return String.valueOf(loginUser.userId());
    }

    /** 当前登录人的启用角色 ID 字符串集合:候选组的比对口径 */
    private List<String> currentRoleIds() {
        LoginUser loginUser = LoginUserContextHolder.get();
        if (loginUser == null) {
            return List.of();
        }
        return loginUser.roles().stream().map(role -> String.valueOf(role.roleId())).toList();
    }

    /**
     * 在引擎侧以某身份执行一段有返回值的动作。
     * Authentication 是线程级变量,结束必清——漏一次清理,同池的下一个请求就把流程记到别人名下。
     */
    private <T> T supplyAsUser(String userId, Supplier<T> action) {
        Authentication.setAuthenticatedUserId(userId);
        try {
            return action.get();
        } finally {
            Authentication.setAuthenticatedUserId(null);
        }
    }

    /** supplyAsUser 的无返回值版:引擎的写动作多是 void,拆两 overload 比强造返回值干净 */
    private void runAsUser(String userId, Runnable action) {
        supplyAsUser(userId, () -> {
            action.run();
            return null;
        });
    }

    private WorkflowInstance toRunningInstance(ProcessInstance instance) {
        return new WorkflowInstance(instance.getId(), instance.getProcessDefinitionId(),
                instance.getProcessDefinitionKey(), instance.getBusinessKey(), instance.getName(),
                instance.getStartUserId(), activeActivityIds(instance.getId()),
                WorkflowTimes.ofEngine(instance.getStartTime()), null, false);
    }

    /** 在途活动 ID:并行网关下会有多个,单值视图会把并行的另一半藏掉 */
    private List<String> activeActivityIds(String processInstanceId) {
        return WorkflowEngineErrors.supply("查询在途节点",
                () -> runtimeService.getActiveActivityIds(processInstanceId));
    }

    private WorkflowTask toTask(Task row) {
        return new WorkflowTask(row.getId(), row.getTaskDefinitionKey(), row.getName(),
                row.getProcessDefinitionId(), row.getProcessInstanceId(),
                row.getAssignee(), candidateGroupsOf(row.getId()),
                WorkflowTimes.ofEngine(row.getCreateTime()), WorkflowTimes.ofEngine(row.getDueDate()), null);
    }

    /** 历史任务没有实时身份链接可读,候选组留空:已办展示的是"我办过的",候选信息以留痕为准 */
    private WorkflowTask toHistoricTask(HistoricTaskInstance row) {
        return new WorkflowTask(row.getId(), row.getTaskDefinitionKey(), row.getName(),
                row.getProcessDefinitionId(), row.getProcessInstanceId(),
                row.getAssignee(), List.of(),
                WorkflowTimes.ofEngine(row.getCreateTime()), WorkflowTimes.ofEngine(row.getDueDate()),
                WorkflowTimes.ofEngine(row.getEndTime()));
    }

    /** 任务的候选角色:引擎把它们挂在身份链接上,这里按 candidate 类型取 groupId 集合 */
    private List<String> candidateGroupsOf(String taskId) {
        return WorkflowEngineErrors.supply("查询任务候选角色", () -> taskService.getIdentityLinksForTask(taskId)
                .stream()
                .filter(link -> IDENTITY_LINK_CANDIDATE.equals(link.getType()) && link.getGroupId() != null)
                .map(link -> link.getGroupId())
                .distinct()
                .toList());
    }

    /** 网关注入 initiator,并拒绝调用方自带同名变量 */
    private Map<String, Object> withInitiator(Map<String, Object> variables, String initiator) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (variables != null) {
            if (variables.containsKey(VARIABLE_INITIATOR)) {
                throw BizException.badRequest(VARIABLE_INITIATOR + " 由流程引擎按登录人写入,不接受外部传值");
            }
            merged.putAll(variables);
        }
        merged.put(VARIABLE_INITIATOR, initiator);
        return merged;
    }

    /** 在途实例读取:撤销前的存在性与租户校验 */
    private WorkflowInstance requireRunningInstance(String processInstanceId) {
        WorkflowInstance instance = requireInstance(processInstanceId);
        if (instance.ended()) {
            throw BizException.badRequest("流程已结束,无法撤销");
        }
        return instance;
    }
}
