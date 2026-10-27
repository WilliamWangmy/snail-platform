package com.snail.platform.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.job.api.Job;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 职责:M0 兼容性 spike——在真实 Boot 4.1.1 上下文里验证 Flowable 8.0.0 的五条契约
 * (Flyway 先建表、引擎加入业务事务、租户维度隔离、作业可用、标量变量可读回)。
 * 上游:snail-platform-start 完整上下文(需本机 MySQL/Valkey,SNAIL_DB_* 未设则整类跳过);
 * 下游:无断言外设,数据全部落 ACT_* 与一次性 key,不与既有流程测试互扰。
 * 口径:本类只证明"引擎能按平台的规矩跑",不证明业务可用性;M1 网关落地后这些断言上移到网关级测试。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SNAIL_DB_USERNAME", matches = ".+")
class WorkflowEngineSpikeTest {

    /** 平台租户 ID 在引擎侧的字符串形态,与 sys_tenant.tenant_id 同值 */
    private static final String TENANT_A = "1";

    /** 只用于隔离断言的租户,库里不需要真实存在 */
    private static final String TENANT_B = "990000001";

    @Autowired
    private ProcessEngine processEngine;

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private ManagementService managementService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Flyway 必须先于引擎建表:引擎不自动建表,版本号行由迁移脚本写入 */
    @Test
    void engineUsesFlywaySchemaNotSelfCreation() {
        assertEquals("false", processEngine.getProcessEngineConfiguration().getDatabaseSchemaUpdate(),
                "引擎不得自行建表或升级,schema 归 Flyway");
        assertEquals("8.0.0.0", property("schema.version"), "引擎版本行不由迁移写入");
        assertEquals("8.0.0.0", property("common.schema.version"), "公共版本行不由迁移写入");
        assertEquals("8.0.0.0", property("eventregistry.schema.version"),
                "事件注册表版本行不由迁移写入:process starter 传递依赖它,缺表则引擎无法装配");
        assertEquals("create(8.0.0.0)", property("schema.history"), "版本历史行不由迁移写入");
        assertEquals(36, tableCount("ACT") + tableCount("FLW"), "ACT_*/FLW_* 应为 36 张(32 引擎 + 4 事件注册表)");
        assertEquals(0, tableCount("ACT_ID"), "IDM 已关闭,不该出现 ACT_ID_* 表");
        assertEquals(1, count("select count(*) from flyway_schema_history where version = '4'"),
                "V4 迁移未生效,引擎表可能来自引擎自身");
    }

    /** 部署-启动-办理-历史在显式租户下闭环 */
    @Test
    void deployStartAndCompleteWithinTenant() {
        String key = "spikeSimple" + suffix();
        deploy(key, TENANT_A, simpleBpmn(key));

        ProcessInstance instance = runtimeService.createProcessInstanceBuilder()
                .processDefinitionKey(key)
                .tenantId(TENANT_A)
                .businessKey("bk-" + key)
                .start();
        assertNotNull(instance.getId(), "流程实例未启动");

        String taskId = taskService.createTaskQuery().taskTenantId(TENANT_A)
                .processInstanceId(instance.getId()).singleResult().getId();
        taskService.complete(taskId);

        assertNull(runtimeService.createProcessInstanceQuery()
                .processInstanceId(instance.getId()).singleResult(), "实例应已结束");
        assertEquals(1L, historyService.createHistoricProcessInstanceQuery()
                .processInstanceTenantId(TENANT_A)
                .processInstanceBusinessKey("bk-" + key).count(), "历史实例未落库");
    }

    /** 平台不变量:引擎绕过 MP 拦截器,隔离只能靠每次调用显式带租户 */
    @Test
    void definitionsAndTasksAreTenantScoped() {
        String key = "spikeScoped" + suffix();
        deploy(key, TENANT_A, simpleBpmn(key));

        ProcessInstance instance = runtimeService.createProcessInstanceBuilder()
                .processDefinitionKey(key).tenantId(TENANT_A).start();

        assertEquals(1L, taskService.createTaskQuery().taskTenantId(TENANT_A).processInstanceId(instance.getId()).count());
        assertEquals(0L, taskService.createTaskQuery().taskTenantId(TENANT_B).processInstanceId(instance.getId()).count());
        assertEquals(0L, taskService.createTaskQuery().taskTenantId("").processInstanceId(instance.getId()).count());

        // 同 key 在另一租户没有定义,按 key 启动必须失败,不能回退到别人的定义
        assertThrows(Exception.class, () -> runtimeService.createProcessInstanceBuilder()
                .processDefinitionKey(key).tenantId(TENANT_B).start(), "跨租户不得命中他租户定义");
        assertEquals(0L, repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(key).processDefinitionTenantId(TENANT_B).count());
    }

    /** 引擎与业务共用同一事务:业务回滚时引擎写入一并回滚 */
    @Test
    void engineWritesRollBackWithBusinessTransaction() {
        String key = "spikeTx" + suffix();
        deploy(key, TENANT_A, simpleBpmn(key));
        String businessKey = "bk-" + key;

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> template.execute(status -> {
            runtimeService.createProcessInstanceBuilder()
                    .processDefinitionKey(key).tenantId(TENANT_A).businessKey(businessKey).start();
            assertEquals(1L, runtimeService.createProcessInstanceQuery()
                    .processInstanceBusinessKey(businessKey).count(), "同一事务内应可见");
            throw new IllegalStateException("业务失败");
        }));

        assertEquals(0L, runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(businessKey).count(), "回滚后仍在跑说明引擎另开了事务");
        assertEquals(0L, historyService.createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(businessKey).count(), "历史不应残留");
    }

    /** 作业链路可用:定时器作业落库并带租户,可手工执行 */
    @Test
    void timerJobIsCreatedWithTenantAndExecutable() throws InterruptedException {
        String key = "spikeTimer" + suffix();
        deploy(key, TENANT_A, timerBpmn(key));

        ProcessInstance instance = runtimeService.createProcessInstanceBuilder()
                .processDefinitionKey(key).tenantId(TENANT_A).start();
        Job timerJob = managementService.createTimerJobQuery().processInstanceId(instance.getId()).singleResult();
        assertNotNull(timerJob, "定时器作业未创建");
        assertEquals(TENANT_A, timerJob.getTenantId(), "作业未带租户");
        assertTrue(processEngine.getProcessEngineConfiguration().getAsyncExecutor().isActive(), "异步执行器未启动");

        // 作业一到 ACT_RU_JOB 就成了异步执行器的可领用对象,手工执行与它撞车是两条合法路径,
        // 不是并发 bug;乐观锁抛出来就让它跑完,本用例断言的是"作业被消化后实例结束"
        Job executableJob = managementService.moveTimerToExecutableJob(timerJob.getId());
        try {
            managementService.executeJob(executableJob.getId());
        } catch (FlowableOptimisticLockingException takenByAsyncExecutor) {
            // 执行器抢在前面领走了这个作业
        }
        assertTrue(waitUntilEnded(instance.getId()), "作业执行后实例应结束");
    }

    /** 轮询等待实例消失(手工执行与异步执行器两条路径都会把它跑到头) */
    private boolean waitUntilEnded(String instanceId) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (runtimeService.createProcessInstanceQuery().processInstanceId(instanceId).count() == 0) {
                return true;
            }
            Thread.sleep(50L);
        }
        return false;
    }

    /** 变量口径:只允许标量,对象型变量不进流程 */
    @Test
    void scalarVariablesRoundTrip() {
        String key = "spikeVars" + suffix();
        deploy(key, TENANT_A, simpleBpmn(key));
        Map<String, Object> variables = new HashMap<>();
        variables.put("businessTitle", "请假单");
        variables.put("days", 3);
        variables.put("amount", 1200.5D);
        variables.put("urgent", Boolean.TRUE);
        variables.put("initiator", "1001");

        ProcessInstance instance = runtimeService.createProcessInstanceBuilder()
                .processDefinitionKey(key).tenantId(TENANT_A).variables(variables).start();

        Map<String, Object> stored = runtimeService.getVariables(instance.getId());
        assertEquals("请假单", stored.get("businessTitle"));
        assertEquals(3, ((Number) stored.get("days")).intValue());
        assertEquals(1200.5D, ((Number) stored.get("amount")).doubleValue(), 0.0001);
        assertEquals(Boolean.TRUE, stored.get("urgent"));
        List<String> names = List.copyOf(stored.keySet());
        assertTrue(names.containsAll(variables.keySet()), "变量集合缺失: " + names);
    }

    /* ------------------------------ 辅助 ------------------------------ */

    private String property(String name) {
        return jdbcTemplate.queryForObject("select VALUE_ from ACT_GE_PROPERTY where NAME_ = ?",
                String.class, name);
    }

    private int tableCount(String prefix) {
        return count("select count(*) from information_schema.tables"
                + " where table_schema = database() and table_name like ?", prefix + "%");
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private String suffix() {
        return String.valueOf(System.nanoTime() % 100_000_000L);
    }

    private void deploy(String key, String tenantId, String xml) {
        repositoryService.createDeployment()
                .name("spike-" + key)
                .addString(key + ".bpmn20.xml", xml)
                .tenantId(tenantId)
                .deploy();
    }

    private static String simpleBpmn(String key) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://snail.platform/spike">
                  <process id="%s" name="spike" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
                    <userTask id="approve" name="审批" flowable:assignee="1001"/>
                    <sequenceFlow id="f2" sourceRef="approve" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key);
    }

    private static String timerBpmn(String key) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://snail.platform/spike">
                  <process id="%s" name="spike timer" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="wait"/>
                    <intermediateCatchEvent id="wait">
                      <timerEventDefinition><timeDuration>PT2H</timeDuration></timerEventDefinition>
                    </intermediateCatchEvent>
                    <sequenceFlow id="f2" sourceRef="wait" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key);
    }
}
