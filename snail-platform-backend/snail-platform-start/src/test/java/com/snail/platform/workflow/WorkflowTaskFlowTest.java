package com.snail.platform.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.snail.platform.framework.core.context.LoginUser;
import com.snail.platform.framework.core.context.LoginUserContextHolder;
import com.snail.platform.framework.core.context.TenantContextHolder;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.workflow.engine.WorkflowEngineGateway;
import com.snail.platform.framework.workflow.engine.WorkflowInstance;
import com.snail.platform.framework.workflow.engine.WorkflowTask;
import com.snail.platform.function.notify.entity.SysNotifyMessage;
import com.snail.platform.function.notify.entity.SysNotifyRecipient;
import com.snail.platform.function.notify.mapper.SysNotifyMessageMapper;
import com.snail.platform.function.notify.mapper.SysNotifyRecipientMapper;
import com.snail.platform.function.workflow.model.dto.WorkflowModelCreateRequest;
import com.snail.platform.function.workflow.model.service.WorkflowModelService;
import com.snail.platform.function.workflow.task.dto.WorkflowDoneVO;
import com.snail.platform.function.workflow.task.dto.WorkflowHandleRequest;
import com.snail.platform.function.workflow.task.dto.WorkflowTaskQuery;
import com.snail.platform.function.workflow.task.dto.WorkflowTodoVO;
import com.snail.platform.function.workflow.task.dto.WorkflowTransferRequest;
import com.snail.platform.function.workflow.task.entity.SysWorkflowTaskRecord;
import com.snail.platform.function.workflow.task.service.WorkflowTaskRecordService;
import com.snail.platform.function.workflow.task.service.WorkflowTaskService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 职责:P3 任务中心验收——待办/已办读模型、四个办理动作、审批台账留痕与提交后通知四条链路全通,且越权与脏目标被拦住。
 * 上游:snail-platform-start 完整上下文(需本机 MySQL,SNAIL_DB_* 未设则整类跳过);
 * 下游:一律走 function-workflow 的 service 与 framework-workflow 网关,测试不直接触引擎写动作。
 * 口径:M0/P1 证的是"引擎能按平台规矩跑",P2 证的是"定义只能从模型域发布",本类证的是"办理只能从任务中心走":
 * 断言重点在留痕与拒绝路径——台账记的是动作发生时的操作人快照(不是档案现值),
 * 转办目标必须真实存在,别人签收过的任务碰不了,通知排在提交之后所以审批不会因为通知而回滚。
 * 数据隔离:每个用例按时间戳派生流程标识与假用户 ID,待办计数与台账行互不串扰;
 * 真实用户只用种子里的超管(user_id=1)与它的角色(role_id=1),因为通知收件人展开要查档案。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SNAIL_DB_USERNAME", matches = ".+")
class WorkflowTaskFlowTest {

    /** 平台默认租户 */
    private static final Long TENANT_A = 1L;

    /** 只做隔离断言的租户 */
    private static final Long TENANT_B = 990003001L;

    /** 种子超管:真实存在的用户与角色,通知扇出与转办目标都得到位 */
    private static final Long REAL_USER_ID = 1L;

    /** 种子内置角色 admin:候选组按 roleId 字符串匹配,用真实角色才能展开出收件人 */
    private static final Long REAL_ROLE_ID = 1L;

    @Autowired
    private WorkflowTaskService taskService;

    @Autowired
    private WorkflowTaskRecordService recordService;

    @Autowired
    private WorkflowModelService modelService;

    @Autowired
    private WorkflowEngineGateway gateway;

    @Autowired
    private SysNotifyMessageMapper messageMapper;

    @Autowired
    private SysNotifyRecipientMapper recipientMapper;

    /** 办理人身份假快照:用户不在 sys_user 里,留痕只能取登录人上下文——这正是"快照不回查档案"的证明 */
    private record Actor(String key, Long userId, Long outsiderId, String username, String nickname) {

        /** 命中真实角色的办理人:未签收时按候选组放行 */
        LoginUser approver() {
            return new LoginUser(userId, TENANT_A, "任务租户", username, nickname,
                    1L, "总部", 1L, List.of(new LoginUser.RoleInfo(REAL_ROLE_ID, "admin", "超级管理员")), false);
        }

        /** 同租户但与流程无关的人:既不在候选组也不是办理人 */
        LoginUser outsider() {
            return new LoginUser(outsiderId, TENANT_A, "任务租户", username + "_out", "局外人",
                    1L, "总部", 1L, List.of(), false);
        }
    }

    @BeforeEach
    void setUpTenant() {
        TenantContextHolder.set(TENANT_A);
    }

    @AfterEach
    void clearContext() {
        LoginUserContextHolder.clear();
        TenantContextHolder.clear();
    }

    /** 待办行要能自证是哪张单:业务单号与实例标题来自批量回查,发起人展示名来自用户档案 */
    @Test
    void todoRowCarriesBusinessCoordsAndNames() {
        Actor actor = actor("tkList");
        publishFlow(actor);
        LoginUserContextHolder.set(initiator());
        WorkflowInstance instance = gateway.start(actor.key(), businessKey(actor),
                Map.of("businessTitle", "出差申请单"));

        LoginUserContextHolder.set(actor.approver());
        List<WorkflowTodoVO> rows = taskService.pageTodo(new WorkflowTaskQuery(1, 20)).rows();
        WorkflowTodoVO mine = rows.stream().filter(row -> row.instanceId().equals(instance.id()))
                .findFirst().orElseThrow(() -> new AssertionError("发起后候选角色内没有我的待办"));
        assertEquals(businessKey(actor), mine.businessKey(), "待办行要带业务单号,不然列表点不进单据");
        assertEquals("出差申请单", mine.instanceName(), "实例标题按 businessTitle 落,列表才有可读的一行");
        assertEquals(actor.key(), mine.definitionKey(), "流程标识供列表跳详情");
        assertEquals(REAL_USER_ID, mine.initiatorId(), "发起人按引擎身份字符串解析");
        assertNotNull(mine.initiatorName(), "发起人展示名要现取:列表说\"现在叫什么\"");
        assertFalse(mine.claimed(), "未签收的任务不该显示办理人");
        assertNull(mine.assigneeId(), "未签收时办理人为空");

        long total = taskService.pageTodo(new WorkflowTaskQuery(1, 20)).total();
        assertTrue(total >= 1, "计数与列表同一套过滤条件");
    }

    /** 签收落台账,操作人五列取自登录人快照;别人再领就撞墙 */
    @Test
    void claimRecordsOperatorSnapshotAndBlocksOutsider() {
        Actor actor = actor("tkClaim");
        publishFlow(actor);
        String instanceId = startAsInitiator(actor);
        String taskId = todoIdOf(instanceId);

        taskService.claim(taskId, new WorkflowHandleRequest("我来处理这单"));

        SysWorkflowTaskRecord claimRecord = recordOf(instanceId, WorkflowTaskRecordService.ACTION_CLAIM);
        assertEquals(actor.username(), claimRecord.getOperatorUsername(), "台账记登录人快照,不回查档案");
        assertEquals(actor.nickname(), claimRecord.getOperatorNickname(), "显示名同样按快照落");
        assertEquals("总部", claimRecord.getOperatorOrgName(), "组织列是名称快照");
        assertEquals("我来处理这单", claimRecord.getComment(), "签收也要有说明,否则领而不动无从追责");
        assertEquals(taskId, claimRecord.getTaskId(), "任务侧动作必须带任务坐标");
        assertEquals(actor.userId(), claimRecord.getOperatorId(), "操作人 ID 取自登录上下文");

        LoginUserContextHolder.set(actor.outsider());
        BizException outsiderClaim = assertThrows(BizException.class,
                () -> taskService.claim(taskId, new WorkflowHandleRequest("换我办")));
        assertEquals(403, outsiderClaim.getCode(), "已签收任务的候选角色成员无权再领");
        BizException outsiderApprove = assertThrows(BizException.class,
                () -> taskService.approve(taskId, new WorkflowHandleRequest("我替他批")));
        assertTrue(outsiderApprove.getMessage().contains("签收"), "点名原因:" + outsiderApprove.getMessage());

        LoginUserContextHolder.set(actor.approver());
        assertEquals(taskId, todoIdOf(instanceId), "签收后任务仍在我的待办里");
    }

    /** 通过即办结:流程结束、台账落 approve、发起人收到结果提醒——通知排在提交后,不影响审批成立 */
    @Test
    void approveEndsFlowAndTellsInitiator() {
        Actor actor = actor("tkApprove");
        publishFlow(actor);
        String instanceId = startAsInitiator(actor);
        String taskId = todoIdOf(instanceId);

        taskService.approve(taskId, new WorkflowHandleRequest("同意,费用合理"));

        assertTrue(gateway.requireInstance(instanceId).ended(), "唯一人工节点办结后实例应结束");
        SysWorkflowTaskRecord approveRecord = recordOf(instanceId, WorkflowTaskRecordService.ACTION_APPROVE);
        assertEquals("同意,费用合理", approveRecord.getComment());
        assertEquals(businessKey(actor), approveRecord.getBusinessKey(), "台账要能按业务单号回查");
        assertThrows(BizException.class, () -> taskService.approve(taskId, new WorkflowHandleRequest("再批一次")),
                "已办结的任务不得重复办理");

        assertEquals(1L, workflowNotificationsTo(actor, REAL_USER_ID), "发起人应收到一条流程结果通知");
    }

    /** 转办:改派后原办理人失权、目标人接手并收到提醒,台账记下目标人快照 */
    @Test
    void transferMovesHandlingRightAndInformsTarget() {
        Actor actor = actor("tkTransfer");
        publishFlow(actor);
        String instanceId = startAsInitiator(actor);
        String taskId = todoIdOf(instanceId);

        taskService.transfer(taskId, new WorkflowTransferRequest(REAL_USER_ID, "我不熟这块,请管理员跟进"));

        WorkflowTask moved = gateway.requireVisibleTask(taskId);
        assertEquals(String.valueOf(REAL_USER_ID), moved.assignee(), "改派落到引擎办理人");
        SysWorkflowTaskRecord record = recordOf(instanceId, WorkflowTaskRecordService.ACTION_TRANSFER);
        assertEquals(REAL_USER_ID, record.getTargetUserId(), "目标人要进台账,否则转办无法复核");
        assertNotNull(record.getTargetUserNickname(), "目标人显示名按当时快照落库");

        LoginUserContextHolder.set(actor.approver());
        BizException lost = assertThrows(BizException.class,
                () -> taskService.approve(taskId, new WorkflowHandleRequest("我转出去了还能批?")));
        assertTrue(lost.getMessage().contains("签收"), "原办理人转办后应无权办理:" + lost.getMessage());
        assertEquals(1L, workflowNotificationsTo(actor, REAL_USER_ID), "接手人应收到转办提醒");

        LoginUserContextHolder.set(initiator());
        taskService.approve(taskId, new WorkflowHandleRequest("管理员办结"));
        assertTrue(gateway.requireInstance(instanceId).ended(), "转办后目标人应能办结");
    }

    /** 驳回与通过走同一条办理路径,只有结果变量与台账动作不同 */
    @Test
    void rejectWritesRejectAction() {
        Actor actor = actor("tkReject");
        publishFlow(actor);
        String instanceId = startAsInitiator(actor);
        String taskId = todoIdOf(instanceId);

        taskService.reject(taskId, new WorkflowHandleRequest("预算不足,退回"));

        SysWorkflowTaskRecord record = recordOf(instanceId, WorkflowTaskRecordService.ACTION_REJECT);
        assertEquals("预算不足,退回", record.getComment());
        assertTrue(gateway.requireInstance(instanceId).ended(), "v1 定义里驳回同样办结实例");
        assertTrue(todoIdsOfInstance(instanceId).isEmpty(), "办结后不得残留待办");
    }

    /** 已办列表:我办过的任务在案,时间轴按办结时刻;别人办的一条不漏进来 */
    @Test
    void doneListFollowsAssignee() {
        Actor actor = actor("tkDone");
        publishFlow(actor);
        String instanceId = startAsInitiator(actor);
        taskService.approve(todoIdOf(instanceId), new WorkflowHandleRequest("同意"));

        List<WorkflowDoneVO> rows = taskService.pageDone(new WorkflowTaskQuery(1, 50)).rows();
        WorkflowDoneVO mine = rows.stream().filter(row -> row.instanceId().equals(instanceId))
                .findFirst().orElseThrow(() -> new AssertionError("办理完的任务应出现在已办里"));
        assertNotNull(mine.handledAt(), "已办要有办结时刻,列表按它排");
        assertEquals(businessKey(actor), mine.businessKey());

        LoginUserContextHolder.set(actor.outsider());
        assertTrue(taskService.pageDone(new WorkflowTaskQuery(1, 50)).rows().stream()
                .noneMatch(row -> row.instanceId().equals(instanceId)), "别人办进的已办不得出现在我的列表");
        TenantContextHolder.set(TENANT_B);
        assertEquals(0L, taskService.pageTodo(new WorkflowTaskQuery(1, 20)).total(), "他租户不得看到本租户待办");
    }

    /** 转办目标必须是当前租户内的真实用户:派给不存在的人等于凭空造一条谁也办不了的待办 */
    @Test
    void transferTargetMustBeARealUser() {
        Actor actor = actor("tkBadTarget");
        publishFlow(actor);
        String instanceId = startAsInitiator(actor);
        String taskId = todoIdOf(instanceId);

        BizException missing = assertThrows(BizException.class,
                () -> taskService.transfer(taskId, new WorkflowTransferRequest(987654321L, "派给不存在的人")));
        assertTrue(missing.getMessage().contains("不存在"), "应点名原因:" + missing.getMessage());

        assertEquals(taskId, todoIdOf(instanceId), "校验失败不得改动引擎任务");
        assertTrue(recordService.listByInstance(instanceId).stream()
                .noneMatch(row -> WorkflowTaskRecordService.ACTION_TRANSFER.equals(row.getAction())),
                "失败的转办不得留下台账");
    }

    /* ------------------------------ 辅助 ------------------------------ */

    /** 用例身份:后缀同时用作流程标识与假用户 ID,保证待办与台账互不串扰 */
    private Actor actor(String prefix) {
        String tag = String.valueOf(System.nanoTime() % 100_000_000L);
        long seed = Long.parseLong(tag);
        TenantContextHolder.set(TENANT_A);
        return new Actor(prefix + tag, 960_000_000_000L + seed, 970_000_000_000L + seed,
                "tk_approver_" + tag, "验收办理人");
    }

    /** 种子超管的登录快照:发起人与转办目标都用真实存在的人,通知展开才落得到收件箱 */
    private static LoginUser initiator() {
        return new LoginUser(REAL_USER_ID, TENANT_A, "默认租户", "admin", "超级管理员",
                1L, "总部", 1L, List.of(new LoginUser.RoleInfo(REAL_ROLE_ID, "admin", "超级管理员")), false);
    }

    private String businessKey(Actor actor) {
        return "bk-" + actor.key();
    }

    /** 建模型→发布:走 P2 的模型域,不在测试里直接部署引擎定义 */
    private void publishFlow(Actor actor) {
        LoginUserContextHolder.set(actor.approver());
        Long modelId = modelService.create(new WorkflowModelCreateRequest(actor.key(), "任务中心验收流程",
                null, null, null, approvalBpmn(actor)));
        modelService.publish(modelId);
    }

    /** 以超管身份发起,返回实例 ID;发起后办理人快照仍是验收办理人 */
    private String startAsInitiator(Actor actor) {
        LoginUserContextHolder.set(initiator());
        WorkflowInstance instance = gateway.start(actor.key(), businessKey(actor),
                Map.of("businessTitle", "任务中心验收单"));
        LoginUserContextHolder.set(actor.approver());
        return instance.id();
    }

    /** 我在待办列表里看到的这张任务 */
    private String todoIdOf(String processInstanceId) {
        return todoIdsOfInstance(processInstanceId).stream().findFirst()
                .orElseThrow(() -> new AssertionError("实例没有产生我的待办:" + processInstanceId));
    }

    private List<String> todoIdsOfInstance(String processInstanceId) {
        return taskService.pageTodo(new WorkflowTaskQuery(1, 50)).rows().stream()
                .filter(row -> row.instanceId().equals(processInstanceId))
                .map(WorkflowTodoVO::taskId)
                .toList();
    }

    /** 某实例的台账里指定动作的那一条;多条即用例串味 */
    private SysWorkflowTaskRecord recordOf(String processInstanceId, String action) {
        List<SysWorkflowTaskRecord> matched = recordService.listByInstance(processInstanceId).stream()
                .filter(row -> action.equals(row.getAction()))
                .toList();
        assertEquals(1, matched.size(), "台账应有且只有一条 " + action);
        return matched.get(0);
    }

    /**
     * 发给某人的流程类通知条数,按本用例的业务单号筛:同一个库里跑多个用例,
     * 不按单号筛就会把别人的通知算进自己头上;单号进文案是产品要求(收件人要能从通知跳回单据)。
     */
    private long workflowNotificationsTo(Actor actor, Long userId) {
        List<Long> messageIds = messageMapper.selectList(Wrappers.<SysNotifyMessage>lambdaQuery()
                .eq(SysNotifyMessage::getBizType, "workflow")
                .like(SysNotifyMessage::getContent, businessKey(actor))).stream()
                .map(SysNotifyMessage::getMessageId)
                .toList();
        if (messageIds.isEmpty()) {
            return 0L;
        }
        return recipientMapper.selectCount(Wrappers.<SysNotifyRecipient>lambdaQuery()
                .eq(SysNotifyRecipient::getUserId, userId)
                .in(SysNotifyRecipient::getMessageId, messageIds));
    }

    /** 人工审批流:候选组绑真实角色,办理人未指派 */
    private static String approvalBpmn(Actor actor) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://snail.platform/task">
                  <process id="%s" name="%s 验收流程" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
                    <userTask id="approve" name="角色审批" flowable:candidateGroups="%s"/>
                    <sequenceFlow id="f2" sourceRef="approve" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(actor.key(), actor.key(), String.valueOf(REAL_ROLE_ID));
    }
}
