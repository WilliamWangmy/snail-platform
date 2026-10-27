package com.snail.platform.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.snail.platform.framework.core.context.LoginUser;
import com.snail.platform.framework.core.context.LoginUserContextHolder;
import com.snail.platform.framework.core.context.TenantContextHolder;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.workflow.engine.WorkflowEngineGateway;
import com.snail.platform.framework.workflow.engine.WorkflowInstance;
import com.snail.platform.function.workflow.api.WorkflowApi;
import com.snail.platform.function.workflow.api.WorkflowApi.StartResult;
import com.snail.platform.function.workflow.api.WorkflowOutcome;
import com.snail.platform.function.workflow.api.WorkflowOutcomeListener;
import com.snail.platform.function.workflow.api.WorkflowStartCommand;
import com.snail.platform.function.workflow.api.WorkflowStatus;
import com.snail.platform.function.workflow.instance.dto.WorkflowInstanceQuery;
import com.snail.platform.function.workflow.instance.dto.WorkflowInstanceVO;
import com.snail.platform.function.workflow.instance.dto.WorkflowTraceVO;
import com.snail.platform.function.workflow.instance.service.WorkflowInstanceService;
import com.snail.platform.function.workflow.model.dto.WorkflowModelCreateRequest;
import com.snail.platform.function.workflow.model.service.WorkflowModelService;
import com.snail.platform.function.workflow.task.dto.WorkflowHandleRequest;
import com.snail.platform.function.workflow.task.dto.WorkflowTaskQuery;
import com.snail.platform.function.workflow.task.dto.WorkflowTodoVO;
import com.snail.platform.function.workflow.task.entity.SysWorkflowTaskRecord;
import com.snail.platform.function.workflow.task.service.WorkflowTaskRecordService;
import com.snail.platform.function.workflow.task.service.WorkflowTaskService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 职责:P4 业务接入验收——业务单据经 WorkflowApi 发起、结果经 WorkflowOutcomeListener 回写、撤销清空待办、轨迹成页。
 * 上游:snail-platform-start 完整上下文(需本机 MySQL,SNAIL_DB_* 未设则整类跳过);
 * 下游:workflow 的 api 契约与实例/任务两个 service,示例单据表由本类自己在测试库建。
 * 口径:示例业务侧(单据表 + 监听器)刻意留在测试线——平台不发货 demo 表,但"业务模块只依赖 api 包"这条边界
 * 必须真跑一遍才算数:发起与写单同事务、结果回调幂等、回调写坏不影响已成立的审批、一单一条由平台挡。
 * 断言重点是回写链路的三条硬账:flow_status 与 WorkflowStatus 对齐、撤销后待办清空、重复派发不再改状态。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SNAIL_DB_USERNAME", matches = ".+")
class WorkflowBusinessFlowTest {

    /** 平台默认租户 */
    private static final Long TENANT_A = 1L;

    /** 种子超管:发起人用真实存在的人,轨迹与通知才拿得到档案 */
    private static final Long REAL_USER_ID = 1L;

    /** 种子内置角色 admin:候选组按 roleId 匹配,用真实角色才展开得出待办 */
    private static final Long REAL_ROLE_ID = 1L;

    /** 示例单据的业务类型:发起与监听器认领必须同值 */
    private static final String BUSINESS_TYPE = "sample-order";

    /** 示例单据表:业务侧自己那张表,平台不参与建表 */
    private static final String ORDER_TABLE = "sample_order";

    @Autowired
    private WorkflowApi workflowApi;

    @Autowired
    private WorkflowInstanceService instanceService;

    @Autowired
    private WorkflowTaskService taskService;

    @Autowired
    private WorkflowTaskRecordService recordService;

    @Autowired
    private WorkflowModelService modelService;

    @Autowired
    private WorkflowEngineGateway gateway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SampleOrderListener sampleOrderListener;

    /** 建示例单据表(测试库专属,平台迁移脚本里没有它);语句幂等,每个用例前确认一次 */
    @BeforeEach
    void createOrderTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sample_order (
                  order_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '单据主键',
                  tenant_id BIGINT NOT NULL COMMENT '归属租户',
                  order_no VARCHAR(64) NOT NULL COMMENT '业务单号,与 businessType 组成关联键',
                  amount DECIMAL(12,2) NOT NULL COMMENT '金额,进流程变量',
                  flow_status TINYINT NOT NULL DEFAULT 0 COMMENT '0 未提交 1 审批中 2 通过 3 驳回 4 撤销',
                  flow_instance_id VARCHAR(64) DEFAULT NULL COMMENT '发起返回的实例 ID',
                  create_time DATETIME NOT NULL COMMENT '建单时刻',
                  PRIMARY KEY (order_id),
                  UNIQUE KEY uk_sample_order_no (order_no)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '业务接入验收示例表'
                """);
    }

    @BeforeEach
    void setUpContext() {
        TenantContextHolder.set(TENANT_A);
        sampleOrderListener.received.clear();
        sampleOrderListener.failNext = false;
    }

    @AfterEach
    void clearContext() {
        LoginUserContextHolder.clear();
        TenantContextHolder.clear();
    }

    /** 端到端:发起即落台账与实例 ID,审批通过后回调把单据改成"通过",状态查询与之一致 */
    @Test
    void approvalOutcomeWritesBackToBusinessOrder() {
        Fixture fixture = fixture("p4Approve");
        newOrder(fixture);
        LoginUserContextHolder.set(initiator());
        StartResult started = startOrder(fixture);
        assertEquals(1, orderFlowStatus(fixture.orderNo()), "发起后单据应停在审批中");
        assertEquals(started.instanceId(), orderInstanceId(fixture.orderNo()), "实例 ID 由业务侧自存,列表不 join 引擎");

        SysWorkflowTaskRecord startRecord = recordOf(started.instanceId(), WorkflowTaskRecordService.ACTION_START);
        assertEquals(BUSINESS_TYPE, startRecord.getBusinessType(), "发起行的业务类型是后续动作的关联依据");
        assertEquals(fixture.orderNo(), startRecord.getBusinessKey());

        LoginUserContextHolder.set(fixture.approver());
        taskService.approve(todoIdOf(fixture.approver(), started.instanceId()),
                new WorkflowHandleRequest("预算够,同意"));

        assertEquals(2, orderFlowStatus(fixture.orderNo()), "通过回调要把单据落到已通过");
        assertEquals(List.of(WorkflowStatus.APPROVED), sampleOrderListener.statusesFor(fixture.orderNo()));
        assertEquals(WorkflowStatus.APPROVED, workflowApi.status(BUSINESS_TYPE, fixture.orderNo()));
        assertTrue(gateway.requireInstance(started.instanceId()).ended(), "单节点流程办结后实例应结束");
    }

    /** 一单一条:同类型同单号有在途就拒绝再发;驳回后重新提交开出新实例 */
    @Test
    void duplicateStartIsRejectedUntilTheFlowIsSettled() {
        Fixture fixture = fixture("p4Dup");
        newOrder(fixture);
        LoginUserContextHolder.set(initiator());
        StartResult first = startOrder(fixture);

        BizException repeated = assertThrows(BizException.class, () -> startOrder(fixture),
                "同一单据在途期间不得开出第二条流程");
        assertTrue(repeated.getMessage().contains("在途"), "应点名原因:" + repeated.getMessage());

        LoginUserContextHolder.set(fixture.approver());
        taskService.reject(todoIdOf(fixture.approver(), first.instanceId()),
                new WorkflowHandleRequest("预算不足,退回"));
        assertEquals(3, orderFlowStatus(fixture.orderNo()), "驳回要把单据退回未通过");
        assertEquals(WorkflowStatus.REJECTED, workflowApi.status(BUSINESS_TYPE, fixture.orderNo()));

        LoginUserContextHolder.set(initiator());
        StartResult second = startOrder(fixture);
        assertTrue(!second.instanceId().equals(first.instanceId()), "驳回后允许重新提交");
        assertEquals(WorkflowStatus.RUNNING, workflowApi.status(BUSINESS_TYPE, fixture.orderNo()),
                "状态永远按最新一条实例判");
        assertEquals(1, orderFlowStatus(fixture.orderNo()), "重新提交后单据再次进入审批中");
    }

    /** 撤销:待办清空、单据标成已撤销、当时在等的人收到说明 */
    @Test
    void withdrawClearsTodosAndMarksOrderWithdrawn() {
        Fixture fixture = fixture("p4Withdraw");
        newOrder(fixture);
        LoginUserContextHolder.set(initiator());
        StartResult started = startOrder(fixture);

        LoginUserContextHolder.set(fixture.approver());
        assertTrue(!todoIdOf(fixture.approver(), started.instanceId()).isBlank(), "撤销前审批人手里有待办");

        LoginUserContextHolder.set(initiator());
        instanceService.withdraw(started.instanceId(), "重复提交了,先撤回来");

        List<WorkflowTodoVO> todos = taskService.pageTodo(new WorkflowTaskQuery(1, 100)).rows();
        assertTrue(todos.stream().noneMatch(row -> row.instanceId().equals(started.instanceId())),
                "撤销后引擎侧不得残留待办");
        assertEquals(4, orderFlowStatus(fixture.orderNo()), "撤销回调要把单据标成已撤销");
        assertEquals(WorkflowStatus.WITHDRAWN, workflowApi.status(BUSINESS_TYPE, fixture.orderNo()));
        SysWorkflowTaskRecord record = recordOf(started.instanceId(), WorkflowTaskRecordService.ACTION_WITHDRAW);
        assertEquals("重复提交了,先撤回来", record.getComment(), "撤销原因要留在台账里");
        assertEquals(List.of(WorkflowStatus.WITHDRAWN), sampleOrderListener.statusesFor(fixture.orderNo()));
    }

    /** 只有发起人能撤:同租户的别人手里能猜到实例 ID 也不该撤走别人的单 */
    @Test
    void onlyInitiatorCanWithdraw() {
        Fixture fixture = fixture("p4WithdrawByOther");
        newOrder(fixture);
        LoginUserContextHolder.set(initiator());
        StartResult started = startOrder(fixture);

        LoginUserContextHolder.set(fixture.approver());
        BizException forbidden = assertThrows(BizException.class,
                () -> instanceService.withdraw(started.instanceId(), "我来撤"));
        assertEquals(403, forbidden.getCode(), "非发起人撤销应被拒:" + forbidden.getMessage());

        assertEquals(WorkflowStatus.RUNNING, workflowApi.status(BUSINESS_TYPE, fixture.orderNo()),
                "失败的撤销不得动到流程");
        assertTrue(!todoIdOf(fixture.approver(), started.instanceId()).isBlank(), "失败的撤销不得清掉待办");
    }

    /** 回调按 (instanceId, status) 幂等:重复派发不再改单据,单据也不停在旧值 */
    @Test
    void repeatedDispatchDoesNotRewriteTheOrder() {
        Fixture fixture = fixture("p4Idempotent");
        newOrder(fixture);
        LoginUserContextHolder.set(initiator());
        StartResult started = startOrder(fixture);

        LoginUserContextHolder.set(fixture.approver());
        taskService.approve(todoIdOf(fixture.approver(), started.instanceId()),
                new WorkflowHandleRequest("同意"));
        // 重放同一条结果:业务侧的守卫条件让第二次更新落在 0 行,平台不承诺"只叫一次"
        sampleOrderListener.onOutcome(new WorkflowOutcome(BUSINESS_TYPE, fixture.orderNo(),
                started.instanceId(), WorkflowStatus.APPROVED));

        assertEquals(2, orderFlowStatus(fixture.orderNo()), "重复派发不得把单据改到别处");
        assertEquals(2, sampleOrderListener.received.size(), "回调至少送达一次,重复由业务侧吸收");
    }

    /** 回调写坏不影响已成立的审批:流程办结、台账在案,单据停在旧状态由业务侧补偿 */
    @Test
    void brokenListenerDoesNotRollBackApproval() {
        Fixture fixture = fixture("p4BrokenListener");
        newOrder(fixture);
        LoginUserContextHolder.set(initiator());
        StartResult started = startOrder(fixture);

        sampleOrderListener.failNext = true;
        LoginUserContextHolder.set(fixture.approver());
        taskService.approve(todoIdOf(fixture.approver(), started.instanceId()),
                new WorkflowHandleRequest("同意,回调那侧会抛异常"));

        assertTrue(gateway.requireInstance(started.instanceId()).ended(), "审批结果不因回调失败而回滚");
        assertEquals(WorkflowStatus.APPROVED, workflowApi.status(BUSINESS_TYPE, fixture.orderNo()),
                "流程状态读的是台账,不受回调影响");
        assertEquals(1, orderFlowStatus(fixture.orderNo()), "回调失败时单据停在审批中,等平台补偿或人工对齐");
    }

    /** 轨迹页:台账步用当时快照名,在途节点用档案现值;"我发起的"能翻到这条实例 */
    @Test
    void traceShowsLedgerStepsAndCurrentOwner() {
        Fixture fixture = fixture("p4Trace");
        newOrder(fixture);
        LoginUserContextHolder.set(initiator());
        StartResult started = startOrder(fixture);

        // 签收人用种子超管:在途节点要回查档案取现值,档案里没这个人就只能取到空
        taskService.claim(todoIdOf(initiator(), started.instanceId()), new WorkflowHandleRequest("我来跟进"));

        WorkflowTraceVO trace = instanceService.trace(started.instanceId());
        assertEquals(WorkflowStatus.RUNNING, trace.instance().status(), "已签收未办结仍是在途");
        assertEquals("超级管理员", trace.steps().get(0).operatorName(), "发起步的人名是台账快照");
        assertEquals(List.of(WorkflowTaskRecordService.ACTION_START, WorkflowTaskRecordService.ACTION_CLAIM),
                trace.steps().stream().map(WorkflowTraceVO.Step::action).toList());
        assertEquals(1, trace.waiting().size(), "单节点流程在途时只有一个等待节点");
        assertEquals(REAL_USER_ID, trace.waiting().get(0).assigneeId(), "签收后在途节点的办理人是签收人");
        assertEquals("超级管理员", trace.waiting().get(0).assigneeName(),
                "在途节点的姓名回查档案取现值,与台账步的快照名各走各的来源");

        List<WorkflowInstanceVO> startedRows = instanceService
                .pageStarted(new WorkflowInstanceQuery(1, 50, fixture.key())).rows();
        assertEquals(1, startedRows.size(), "我发起的按流程标识筛选后只剩这一条");
        assertEquals(started.instanceId(), startedRows.get(0).instanceId());
        assertEquals(fixture.orderNo(), startedRows.get(0).businessKey());
        assertEquals(REAL_USER_ID, startedRows.get(0).initiatorId());
    }

    /** 关联键的两个半成品与查无此单:参数校验与 NONE 口径 */
    @Test
    void businessKeyAndTypeMustComeTogether() {
        LoginUserContextHolder.set(initiator());
        BizException halfKey = assertThrows(BizException.class,
                () -> workflowApi.start(new WorkflowStartCommand("whatever-key", null,
                        "only-key-no-type", "标题", Map.of())));
        assertTrue(halfKey.getMessage().contains("同时"), "应点名原因:" + halfKey.getMessage());

        assertEquals(WorkflowStatus.NONE, workflowApi.status(BUSINESS_TYPE, "never-used-" + System.nanoTime()));

        BizException withdrawMissing = assertThrows(BizException.class,
                () -> workflowApi.withdraw(BUSINESS_TYPE, "never-used-" + System.nanoTime(), "撤了"));
        assertTrue(withdrawMissing.getMessage().contains("没有可撤销"), "应点名原因:" + withdrawMissing.getMessage());
    }

    /* ------------------------------ 示例业务侧 ------------------------------ */

    /**
     * 示例单据的结果监听器:业务模块接流程只需实现这一个接口。
     * 写回语句带 flow_instance_id 与"当前是审批中"两个条件——重复派发与迟到的旧结果都会被它挡掉,
     * 这正是方案 §7 要求的 (instanceId, outcome) 幂等。
     */
    static class SampleOrderListener implements WorkflowOutcomeListener {

        /** 收到的回调,按顺序留着给断言用 */
        final List<WorkflowOutcome> received = new CopyOnWriteArrayList<>();

        /** 置真则下一次回调抛异常:验证"回调写坏不影响审批" */
        volatile boolean failNext;

        /** 单据表读写 */
        private final JdbcTemplate jdbcTemplate;

        SampleOrderListener(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public String businessType() {
            return BUSINESS_TYPE;
        }

        @Override
        public void onOutcome(WorkflowOutcome outcome) {
            received.add(outcome);
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("示例业务侧故意写坏一次");
            }
            jdbcTemplate.update("UPDATE " + ORDER_TABLE + " SET flow_status = ?"
                            + " WHERE order_no = ? AND flow_instance_id = ? AND flow_status = 1",
                    flowStatusOf(outcome.status()), outcome.businessKey(), outcome.instanceId());
        }

        /** WorkflowStatus → 业务表 flow_status:0 未提交 1 审批中 2 通过 3 驳回 4 撤销 */
        private static int flowStatusOf(WorkflowStatus status) {
            return switch (status) {
                case APPROVED -> 2;
                case REJECTED -> 3;
                case WITHDRAWN -> 4;
                case RUNNING -> 1;
                case NONE -> 0;
            };
        }

        /** 本用例收到的状态序列 */
        List<WorkflowStatus> statusesFor(String businessKey) {
            return received.stream().filter(outcome -> businessKey.equals(outcome.businessKey()))
                    .map(WorkflowOutcome::status).toList();
        }
    }

    /** 示例业务侧装配:平台按 Spring 容器归集监听器,业务模块只管声明 bean */
    @TestConfiguration
    static class SampleBusinessConfiguration {

        @Bean
        SampleOrderListener sampleOrderListener(JdbcTemplate jdbcTemplate) {
            return new SampleOrderListener(jdbcTemplate);
        }
    }

    /* ------------------------------ 辅助 ------------------------------ */

    /** 一个用例的身份包:流程标识同时用作单据编号与假用户 ID 的种子,待办与台账互不串扰 */
    private record Fixture(String key, Long approverId, String approverUsername, String approverNickname) {

        /** 业务单号:示例里与流程标识同值,生产环境是单据自己的编号,两者本就没有关系 */
        String orderNo() {
            return key;
        }

        /** 命中真实角色的审批人:候选组按 roleId 匹配 */
        LoginUser approver() {
            return new LoginUser(approverId, TENANT_A, "示例租户", approverUsername, approverNickname,
                    1L, "总部", 1L, List.of(new LoginUser.RoleInfo(REAL_ROLE_ID, "admin", "超级管理员")));
        }
    }

    /** 种子超管的登录快照:发起人必须是真实存在的人 */
    private static LoginUser initiator() {
        return new LoginUser(REAL_USER_ID, TENANT_A, "默认租户", "admin", "超级管理员",
                1L, "总部", 1L, List.of(new LoginUser.RoleInfo(REAL_ROLE_ID, "admin", "超级管理员")));
    }

    private Fixture fixture(String prefix) {
        String tag = String.valueOf(System.nanoTime() % 100_000_000L);
        TenantContextHolder.set(TENANT_A);
        return new Fixture(prefix + tag, 940_000_000_000L + Long.parseLong(tag),
                "p4_approver_" + tag, "验收审批人");
    }

    /**
     * 示例业务侧的"提交"动作:发起 → 回写实例 ID 与"审批中",两步在同一个方法里,
     * 生产代码要把这个方法标 @Transactional(引擎与业务共用事务,P0 已实证)。
     * 建单与建流程分开:驳回后重新提交是同一张单子再走一遍流程,不是再造一张单。
     */
    private StartResult startOrder(Fixture fixture) {
        StartResult started = workflowApi.start(new WorkflowStartCommand(fixture.key(), BUSINESS_TYPE,
                fixture.key(), "示例采购单 " + fixture.key(), Map.of("amount", 1200.0D)));
        jdbcTemplate.update("UPDATE " + ORDER_TABLE
                + " SET flow_status = 1, flow_instance_id = ? WHERE order_no = ?",
                started.instanceId(), fixture.key());
        return started;
    }

    /** 用例开场:发布定义 + 建一张未提交的单据 */
    private void newOrder(Fixture fixture) {
        publishFlow(fixture);
        jdbcTemplate.update("INSERT INTO " + ORDER_TABLE
                        + " (tenant_id, order_no, amount, flow_status, create_time) VALUES (?, ?, ?, 0, NOW())",
                TENANT_A, fixture.key(), new BigDecimal("1200.00"));
    }

    /** 建模型→发布:定义只能从模型域出来,P2 的闸门在这里同样生效 */
    private void publishFlow(Fixture fixture) {
        LoginUserContextHolder.set(fixture.approver());
        Long modelId = modelService.create(new WorkflowModelCreateRequest(fixture.key(), "业务接入验收流程",
                null, null, null, approvalBpmn(fixture)));
        modelService.publish(modelId);
    }

    /** 某实例在审批人待办里的那张任务 */
    private String todoIdOf(LoginUser approver, String processInstanceId) {
        LoginUserContextHolder.set(approver);
        return taskService.pageTodo(new WorkflowTaskQuery(1, 100)).rows().stream()
                .filter(row -> row.instanceId().equals(processInstanceId))
                .map(WorkflowTodoVO::taskId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("实例没有产生待办:" + processInstanceId));
    }

    /** 台账里指定动作的那一条 */
    private SysWorkflowTaskRecord recordOf(String processInstanceId, String action) {
        List<SysWorkflowTaskRecord> matched = recordService.listByInstance(processInstanceId).stream()
                .filter(row -> action.equals(row.getAction()))
                .toList();
        assertEquals(1, matched.size(), "台账应有且只有一条 " + action);
        return matched.get(0);
    }

    private int orderFlowStatus(String orderNo) {
        Integer status = jdbcTemplate.queryForObject("SELECT flow_status FROM " + ORDER_TABLE
                + " WHERE order_no = ?", Integer.class, orderNo);
        return status == null ? -1 : status;
    }

    private String orderInstanceId(String orderNo) {
        return jdbcTemplate.queryForObject("SELECT flow_instance_id FROM " + ORDER_TABLE
                + " WHERE order_no = ?", String.class, orderNo);
    }

    /** 单节点人工审批流:候选组绑真实角色 */
    private static String approvalBpmn(Fixture fixture) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://snail.platform/business">
                  <process id="%s" name="%s 验收流程" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
                    <userTask id="approve" name="角色审批" flowable:candidateGroups="%s"/>
                    <sequenceFlow id="f2" sourceRef="approve" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(fixture.key(), fixture.key(), String.valueOf(REAL_ROLE_ID));
    }
}
