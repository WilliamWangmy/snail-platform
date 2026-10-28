package com.snail.platform.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.snail.platform.framework.core.context.LoginUser;
import com.snail.platform.framework.core.context.LoginUserContextHolder;
import com.snail.platform.framework.core.context.TenantContextHolder;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.workflow.engine.WorkflowDeployment;
import com.snail.platform.framework.workflow.engine.WorkflowEngineGateway;
import com.snail.platform.framework.workflow.engine.WorkflowInstance;
import com.snail.platform.framework.workflow.engine.WorkflowTask;
import com.snail.platform.framework.workflow.engine.WorkflowTraceStep;
import com.snail.platform.framework.workflow.tenant.WorkflowTenantBridge;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 职责:P1 网关验收——部署、发起、办理、查询四条链路在网关上全通,且四道闸门(租户/身份/变量/异常)拦得住。
 * 上游:snail-platform-start 完整上下文(需本机 MySQL,SNAIL_DB_* 未设则整类跳过);
 * 下游:WorkflowEngineGateway 与 WorkflowTenantBridge,一律按平台身份上下文驱动,不直接触引擎。
 * 口径:M0 spike 证的是"引擎按平台规矩能跑",本类证的是"平台规矩只能从网关走"——
 * 所以断言重点在拒绝路径:跨租户看不见、无登录人不写、非白名单 BPMN 不部署、非标量变量不入流程。
 * 数据隔离:每个用例按同一时间戳生成流程标识与该用例专属的办理人/角色,待办计数互不串扰,也不要求库是干净的。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SNAIL_DB_USERNAME", matches = ".+")
class WorkflowGatewayFlowTest {

    /** 平台默认租户 ID,与 sys_tenant 同值;引擎侧由网关转成字符串 */
    private static final Long TENANT_A = 1L;

    /** 只做隔离断言的租户,库里不必真实存在 */
    private static final Long TENANT_B = 990000001L;

    @Autowired
    private WorkflowEngineGateway gateway;

    @Autowired
    private WorkflowTenantBridge tenantBridge;

    @Autowired
    private RepositoryService repositoryService;

    /** 用例身份:同一后缀派生出流程标识、办理人 ID 与候选角色 ID,保证待办计数只算自己 */
    private record Actor(String key, Long userId, Long outsiderId, Long roleId) {

        /** 办理人快照:带候选角色 */
        LoginUser approver() {
            return new LoginUser(userId, TENANT_A, "验收租户", "gw_approver_" + key, "流程验收员",
                    null, null, 1L, List.of(new LoginUser.RoleInfo(roleId, "auditor", "审核员")), false);
        }

        /** 与流程对象毫无关系的同租户登录人,用于越权断言 */
        LoginUser outsider() {
            return new LoginUser(outsiderId, TENANT_A, "验收租户", "gw_outsider_" + key, "局外人",
                    null, null, 1L, List.of(), false);
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

    /** 部署→发起→候选→办理→轨迹:网关侧闭环,发起人与租户都由网关自己钉 */
    @Test
    void deployStartCompleteAndTrace() {
        Actor actor = actor("gwFlow");
        WorkflowDeployment deployment = gateway.deploy("gw-" + actor.key(), approvalBpmn(actor));
        assertEquals(actor.key(), deployment.processDefinitionKey(), "网关返回的定义 key 应与流程标识一致");
        assertEquals(1, deployment.version(), "首次部署版本应为 1");

        assertFalse(gateway.hasRunningInstance(businessKey(actor, "main")), "发起前不该有在途实例");
        WorkflowInstance instance = gateway.start(actor.key(), businessKey(actor, "main"),
                Map.of("businessTitle", "网关验收单"));
        assertEquals(String.valueOf(actor.userId()), instance.startUserId(), "发起人必须由网关按登录人写入");
        assertTrue(gateway.hasRunningInstance(businessKey(actor, "main")), "发起后应能按业务单号查到在途实例");
        assertEquals(List.of("approve"), instance.currentActivityIds(), "在途节点应停在审批步");

        WorkflowTask todo = todoOf(instance.id());
        assertEquals(List.of(String.valueOf(actor.roleId())), todo.candidateGroups(),
                "候选角色应从引擎身份链接读回,口径是 roleId 字符串");
        assertTrue(todo.assignee() == null, "未指派任务的办理人应为 null");
        assertEquals(1L, gateway.countTodo(), "待办计数与列表口径必须一致");

        gateway.complete(todo.id(), Map.of("approved", Boolean.TRUE));

        WorkflowInstance finished = gateway.requireInstance(instance.id());
        assertTrue(finished.ended(), "办理完成后实例应结束");
        assertNotNull(finished.endTime(), "结束时刻应来自历史表");
        assertEquals(List.of(), finished.currentActivityIds(), "已结束实例不该还有在途节点");

        List<WorkflowTraceStep> trace = gateway.findTrace(instance.id());
        assertTrue(trace.size() >= 3, "轨迹应覆盖开始-审批-结束,实际 " + trace.size() + " 步");
        assertEquals(1L, gateway.findDone(0, 10).stream()
                .filter(row -> row.instanceId().equals(instance.id())).count(), "已办应查得到刚办理的任务");
    }

    /** 未签收按候选角色放行;一旦落到某人名下,别人既不能领也不能办(方案 §6.1) */
    @Test
    void handlingRightFollowsClaimState() {
        Actor actor = actor("gwClaim");
        gateway.deploy("gw-" + actor.key(), approvalBpmn(actor));

        WorkflowInstance candidateCase = gateway.start(actor.key(), businessKey(actor, "candidate"), Map.of());
        WorkflowTask unassigned = todoOf(candidateCase.id());
        LoginUserContextHolder.set(actor.outsider());
        BizException outsiderClaim = assertThrows(BizException.class, () -> gateway.claim(unassigned.id()));
        assertEquals(403, outsiderClaim.getCode(), "非候选角色领取应是 403");
        assertThrows(BizException.class, () -> gateway.complete(unassigned.id(), Map.of()));
        LoginUserContextHolder.set(actor.approver());
        gateway.complete(unassigned.id(), Map.of("approved", Boolean.TRUE));

        WorkflowInstance assignedCase = gateway.start(actor.key(), businessKey(actor, "assigned"), Map.of());
        WorkflowTask mine = todoOf(assignedCase.id());
        gateway.claim(mine.id());
        assertEquals(String.valueOf(actor.userId()), gateway.requireVisibleTask(mine.id()).assignee(),
                "签收后办理人应为当前登录人");
        LoginUserContextHolder.set(actor.outsider());
        BizException taken = assertThrows(BizException.class, () -> gateway.complete(mine.id(), Map.of()));
        assertTrue(taken.getMessage().contains("签收"), "他人已签收的任务应点名原因:" + taken.getMessage());

        LoginUserContextHolder.set(actor.approver());
        gateway.withdraw(assignedCase.id(), "验收撤回");
        assertTrue(gateway.requireInstance(assignedCase.id()).ended(), "撤销后实例应结束");
    }

    /** 转办:改派后原办理人失去办理权、目标人接手——v1 唯一保留的人工改派 */
    @Test
    void transferMovesHandlingRight() {
        Actor actor = actor("gwTransfer");
        gateway.deploy("gw-" + actor.key(), approvalBpmn(actor));
        WorkflowInstance instance = gateway.start(actor.key(), businessKey(actor, "main"), Map.of());
        WorkflowTask task = todoOf(instance.id());

        gateway.claim(task.id());
        gateway.transfer(task.id(), String.valueOf(actor.outsiderId()));
        assertEquals(String.valueOf(actor.outsiderId()), gateway.requireVisibleTask(task.id()).assignee());
        LoginUserContextHolder.set(actor.outsider());
        assertThrows(BizException.class, () -> gateway.transfer(task.id(), " "), "转办目标不得为空");

        LoginUserContextHolder.set(actor.approver());
        BizException lost = assertThrows(BizException.class, () -> gateway.complete(task.id(), Map.of()));
        assertTrue(lost.getMessage().contains("签收"), "原办理人在转办后应无权办理:" + lost.getMessage());

        LoginUserContextHolder.set(actor.outsider());
        gateway.complete(task.id(), Map.of("approved", Boolean.TRUE));
        assertTrue(gateway.requireInstance(instance.id()).ended(), "转办后目标人应能办结");
    }

    /** 跨租户不可见:同 key 在别的租户没定义,既查不到也起不来,别人的任务更碰不到 */
    @Test
    void otherTenantSeesNothing() {
        Actor actor = actor("gwScope");
        WorkflowDeployment deployment = gateway.deploy("gw-" + actor.key(), approvalBpmn(actor));
        WorkflowInstance instance = gateway.start(actor.key(), businessKey(actor, "main"), Map.of());
        String taskId = todoOf(instance.id()).id();

        TenantContextHolder.set(TENANT_B);
        assertTrue(gateway.findDefinitions(actor.key(), true).isEmpty(), "他租户不得看到本租户定义");
        assertEquals(0L, gateway.countTodo(), "他租户不得看到本租户待办");
        assertThrows(BizException.class, () -> gateway.requireDefinition(deployment.processDefinitionId()),
                "按定义 ID 跨租户读取必须视作不存在");
        assertThrows(BizException.class, () -> gateway.requireVisibleTask(taskId), "跨租户不得读到他人任务");
        assertThrows(BizException.class, () -> gateway.requireInstance(instance.id()), "跨租户不得读到他人实例");
        assertThrows(BizException.class, () -> gateway.start(actor.key(), businessKey(actor, "other"), Map.of()),
                "同 key 在他租户无定义,不得回退命中别人的定义");
    }

    /** 非法 BPMN 在进引擎之前就被拒:部署记录不得留下半个坏定义 */
    @Test
    void illegalBpmnNeverReachesEngine() {
        Actor actor = actor("gwScript");
        BizException scriptTask = assertThrows(BizException.class,
                () -> gateway.deploy("gw-" + actor.key(), scriptTaskBpmn(actor)));
        assertTrue(scriptTask.getMessage().contains("scriptTask"), "应点名被拒的元素:" + scriptTask.getMessage());
        assertEquals(0L, definitionCount(actor.key()), "被拒的定义不得落库");

        Actor methodActor = actor("gwMethod");
        BizException expression = assertThrows(BizException.class,
                () -> gateway.deploy("gw-" + methodActor.key(), methodCallBpmn(methodActor)));
        assertTrue(expression.getMessage().contains("表达式"), "应点名表达式越界:" + expression.getMessage());
        assertEquals(0L, definitionCount(methodActor.key()), "被拒的定义不得落库");

        Actor emptyActor = actor("gwEmpty");
        assertThrows(BizException.class, () -> gateway.deploy("gw-" + emptyActor.key(), noUserTaskBpmn(emptyActor)),
                "v1 只开放人工审批流程,没有用户任务的定义必须被拒");
    }

    /** 变量口径:非标量、外部指定发起人、空值都拒绝,合法标量放行 */
    @Test
    void variablePolicyGuardsStart() {
        Actor actor = actor("gwVars");
        gateway.deploy("gw-" + actor.key(), approvalBpmn(actor));

        BizException collection = assertThrows(BizException.class,
                () -> gateway.start(actor.key(), businessKey(actor, "object"), Map.of("payload", List.of("a"))));
        assertTrue(collection.getMessage().contains("payload"), "应点名越界的变量键:" + collection.getMessage());

        assertThrows(BizException.class,
                () -> gateway.start(actor.key(), businessKey(actor, "initiator"), Map.of("initiator", "999")),
                "发起人由网关注入,不接受外部传值");

        BizException nullValue = assertThrows(BizException.class,
                () -> gateway.start(actor.key(), businessKey(actor, "nil"), Collections.singletonMap("nil", null)));
        assertTrue(nullValue.getMessage().contains("nil"), "拒绝信息应点名变量键:" + nullValue.getMessage());

        assertNotNull(gateway.start(actor.key(), businessKey(actor, "ok"), Map.of("amount", 1200.5D)),
                "标量变量应放行");
    }

    /** 没有登录人或没有租户身份时,网关一律拒绝:引擎侧不得出现无主或他主的流程对象 */
    @Test
    void writesRequireIdentityAndTenant() {
        Actor actor = actor("gwGuard");
        gateway.deploy("gw-" + actor.key(), approvalBpmn(actor));

        LoginUserContextHolder.clear();
        BizException noUser = assertThrows(BizException.class,
                () -> gateway.start(actor.key(), businessKey(actor, "main"), Map.of()));
        assertEquals(403, noUser.getCode(), "缺登录人应是 403 拒绝,不是 500");

        LoginUserContextHolder.set(actor.approver());
        TenantContextHolder.clear();
        assertThrows(BizException.class, () -> gateway.deploy("gw-" + actor.key() + "x", approvalBpmn(actor)),
                "缺租户身份不得部署");
        assertThrows(BizException.class, () -> gateway.countTodo(), "缺租户身份不得查询");
    }

    /** 租户桥:引擎作业线程没有登录人,租户只能按实例反查,退出必恢复调用方原身份 */
    @Test
    void tenantBridgeResolvesInstanceOwner() {
        Actor actor = actor("gwBridge");
        gateway.deploy("gw-" + actor.key(), approvalBpmn(actor));
        WorkflowInstance instance = gateway.start(actor.key(), businessKey(actor, "main"), Map.of());

        LoginUserContextHolder.clear();
        TenantContextHolder.set(TENANT_B);
        assertEquals(TENANT_A, tenantBridge.runForInstance(instance.id(), TenantContextHolder::get),
                "回调应在实例所属租户下执行");
        assertEquals(TENANT_B, TenantContextHolder.get(), "桥退出后必须恢复调用方原租户");

        // 桥里只有租户没有登录人:这正是引擎作业线程的处境,网关必须拒绝任何以人的身份写的动作
        BizException noIdentity = assertThrows(BizException.class,
                () -> tenantBridge.runForInstance(instance.id(),
                        () -> gateway.start(actor.key(), businessKey(actor, "job"), Map.of())));
        assertEquals(403, noIdentity.getCode(), "作业线程不得替用户发起流程");

        assertThrows(BizException.class, () -> tenantBridge.runForInstance("not-existing", () -> "never"),
                "反查不到实例即回调来晚,必须显式失败");
    }

    /* ------------------------------ 辅助 ------------------------------ */

    /** 生成用例身份:后缀同时用作流程标识与身份 ID,保证用例之间零串扰 */
    private Actor actor(String prefix) {
        String tag = String.valueOf(System.nanoTime() % 100_000_000L);
        long seed = Long.parseLong(tag);
        TenantContextHolder.set(TENANT_A);
        Actor actor = new Actor(prefix + tag, 910_000_000_000L + seed, 930_000_000_000L + seed,
                920_000_000_000L + seed);
        LoginUserContextHolder.set(actor.approver());
        return actor;
    }

    private static String businessKey(Actor actor, String caseName) {
        return "bk-" + actor.key() + "-" + caseName;
    }

    /** 指定实例的当前待办:走网关,不从测试里直接触引擎 */
    private WorkflowTask todoOf(String processInstanceId) {
        return gateway.findTodo(0, 50).stream()
                .filter(row -> row.instanceId().equals(processInstanceId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("实例没有产生我的待办:" + processInstanceId));
    }

    /** 被拒定义是否真的没落库:直接查引擎,证明闸门在引擎之前 */
    private long definitionCount(String key) {
        return repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(key)
                .processDefinitionTenantId(String.valueOf(TENANT_A))
                .count();
    }

    /** 审批流:候选组绑 roleId;发起人由网关注入变量,不写 flowable:initiator(扩展属性不在白名单内) */
    private static String approvalBpmn(Actor actor) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://snail.platform/gw">
                  <process id="%s" name="网关验收流程" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
                    <userTask id="approve" name="角色审批" flowable:candidateGroups="%s"/>
                    <sequenceFlow id="f2" sourceRef="approve" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(actor.key(), String.valueOf(actor.roleId()));
    }

    /** 越界元素:scriptTask 等于把服务器执行权交给画图的人 */
    private static String scriptTaskBpmn(Actor actor) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://snail.platform/gw">
                  <process id="%s" name="越界脚本" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="boom"/>
                    <scriptTask id="boom" name="执行脚本" scriptFormat="groovy">
                      <script>println 1</script>
                    </scriptTask>
                    <sequenceFlow id="f2" sourceRef="boom" targetRef="approve"/>
                    <userTask id="approve" name="审批" flowable:assignee="%s"/>
                    <sequenceFlow id="f3" sourceRef="approve" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(actor.key(), actor.userId());
    }

    /** 越界表达式:方法调用是绕开元素白名单的后门 */
    private static String methodCallBpmn(Actor actor) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://snail.platform/gw">
                  <process id="%s" name="越界表达式" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
                    <userTask id="approve" name="审批" flowable:assignee="${execution.getVariable('who')}"/>
                    <sequenceFlow id="f2" sourceRef="approve" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(actor.key());
    }

    /** 没有用户任务的定义:v1 只开放人工审批 */
    private static String noUserTaskBpmn(Actor actor) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://snail.platform/gw">
                  <process id="%s" name="没有人工环节" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(actor.key());
    }
}
