package com.snail.platform.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.snail.platform.framework.workflow.engine.WorkflowTask;
import com.snail.platform.function.workflow.model.dto.WorkflowDefinitionVO;
import com.snail.platform.function.workflow.model.dto.WorkflowModelCreateRequest;
import com.snail.platform.function.workflow.model.dto.WorkflowModelQuery;
import com.snail.platform.function.workflow.model.dto.WorkflowModelUpdateRequest;
import com.snail.platform.function.workflow.model.dto.WorkflowModelVO;
import com.snail.platform.function.workflow.model.service.WorkflowModelService;
import com.snail.platform.function.workflow.template.dto.WorkflowTemplateCreateRequest;
import com.snail.platform.function.workflow.template.dto.WorkflowTemplateQuery;
import com.snail.platform.function.workflow.template.dto.WorkflowTemplateUpdateRequest;
import com.snail.platform.function.workflow.template.service.WorkflowTemplateService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 职责:P2 验收——模型 CRUD、草稿与发布指针、版本、停用/激活、平台模板目录在真实库与真实引擎上全通。
 * 上游:snail-platform-start 完整上下文(需本机 MySQL,SNAIL_DB_* 未设则整类跳过);
 * 下游:WorkflowModelService、WorkflowTemplateService,引擎侧一律经服务与网关触达,测试不直连 ACT_*。
 * 口径:三条验收线来自方案 §8 的 P2 行——非法 BPMN 全部被拒、同 key 多版本、旧版本可查可挂起;
 * 另外把平台的四条数据口径钉住:草稿保存即对齐 model_key、key 终身占用、删除要有在途闸、模板与模型分租户可见性。
 * 数据隔离:流程标识与身份 ID 由同一时间戳派生,用例之间零串扰,也不要求库是干净的。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SNAIL_DB_USERNAME", matches = ".+")
class WorkflowModelFlowTest {

    /** 平台默认租户,sys_workflow_model 归它 */
    private static final Long TENANT_A = 1L;

    /** 只做隔离断言的租户,库里不必真实存在 */
    private static final Long TENANT_B = 990000002L;

    /** 被测:流程模型服务 */
    @Autowired
    private WorkflowModelService modelService;

    /** 被测:流程模板服务 */
    @Autowired
    private WorkflowTemplateService templateService;

    /** 在途实例判据与办理动作走网关,测试不碰引擎类型 */
    @Autowired
    private WorkflowEngineGateway gateway;

    /** 用例身份:同一后缀派生流程标识、办理人与候选角色 */
    private record Actor(String key, Long userId, Long roleId) {

        LoginUser approver() {
            return new LoginUser(userId, TENANT_A, "验收租户", "wf_approver_" + key, "流程验收员",
                    null, null, 1L, List.of(new LoginUser.RoleInfo(roleId, "auditor", "审核员")));
        }
    }

    @BeforeEach
    void setUpContext() {
        TenantContextHolder.set(TENANT_A);
    }

    @AfterEach
    void clearContext() {
        LoginUserContextHolder.clear();
        TenantContextHolder.clear();
    }

    /** 建模型→存草稿→发布:草稿里的默认 id 被对齐成 model_key,发布回写三件套指针 */
    @Test
    void createSaveDraftAndPublish() {
        Actor actor = actor("wfPublish");
        Long modelId = modelService.create(new WorkflowModelCreateRequest(actor.key(), "验收流程",
                "办公", "P2 验收用", null, designerBpmn("Process_1", actor)));

        modelService.saveDraft(modelId, designerBpmn("Process_1", actor));
        String draft = modelService.draft(modelId).xml();
        assertTrue(draft.contains("id=\"" + actor.key() + "\""),
                "草稿保存后流程标识必须与 model_key 一致:" + firstLine(draft));
        assertFalse(draft.contains("Process_1"), "默认标识应已被改名");

        WorkflowDefinitionVO published = modelService.publish(modelId);
        assertEquals(1, published.version(), "首次发布版本应为 1");
        assertEquals(actor.key(), published.definitionKey());
        assertTrue(published.deployed(), "刚发布的版本就是当前指针");

        WorkflowModelVO detail = modelService.detail(modelId);
        assertEquals(1, detail.status().intValue(), "发布后模型状态应为已发布");
        assertEquals(1, detail.deployedVersion().intValue());
        assertNotNull(detail.processDefinitionId());
        assertNotNull(detail.publishTime());
        assertTrue(detail.hasDraft());
    }

    /** 同 key 二次发布产新版本:两版并存可查,当前指针指向新版,旧版仍可读原文 */
    @Test
    void sameKeyKeepsMultipleVersions() {
        Actor actor = actor("wfVersion");
        Long modelId = modelService.create(new WorkflowModelCreateRequest(actor.key(), "多版本流程",
                null, null, null, designerBpmn(actor.key(), actor)));
        modelService.publish(modelId);

        modelService.saveDraft(modelId, designerBpmn(actor.key(), actor, "二审"));
        WorkflowDefinitionVO second = modelService.publish(modelId);
        assertEquals(2, second.version(), "同 key 再发布应递增版本");

        List<WorkflowDefinitionVO> versions = modelService.definitions(modelId);
        assertEquals(2, versions.size(), "版本列表应含两个版本");
        assertEquals(2, versions.get(0).version(), "列表按版本倒序");
        assertTrue(versions.get(0).deployed(), "当前指针应指向 v2");
        assertFalse(versions.get(1).deployed());

        String oldXml = modelService.definitionXml(versions.get(1).definitionId()).xml();
        assertTrue(oldXml.contains("name=\"审批\""), "旧版本原文应仍是发布时的内容:" + firstLine(oldXml));
        assertTrue(modelService.definitionXml(versions.get(0).definitionId()).xml().contains("二审"),
                "新版本原文应含改动");
    }

    /** 非法 BPMN:草稿存得下(半成品要能存),发布一定被拒且不落引擎定义,指针保持为空 */
    @Test
    void illegalBpmnRejectedAtPublish() {
        Actor actor = actor("wfIllegal");
        Long modelId = modelService.create(new WorkflowModelCreateRequest(actor.key(), "越界流程",
                null, null, null, scriptTaskBpmn(actor)));
        modelService.saveDraft(modelId, scriptTaskBpmn(actor));

        BizException rejected = assertThrows(BizException.class, () -> modelService.publish(modelId));
        assertTrue(rejected.getMessage().contains("scriptTask"), "应点名被拒元素:" + rejected.getMessage());

        WorkflowModelVO detail = modelService.detail(modelId);
        assertEquals(0, detail.status().intValue(), "发布失败后模型仍是草稿");
        assertNull(detail.processDefinitionId(), "指针不得留下半态");
        assertTrue(gateway.findDefinitions(actor.key(), false).isEmpty(), "被拒的定义不得落引擎");
    }

    /** 停用即挂起当前定义,激活恢复;在途实例不受停用影响(单参挂起重载) */
    @Test
    void suspendAndActivateCurrentDefinition() {
        Actor actor = actor("wfSuspend");
        Long modelId = modelService.create(new WorkflowModelCreateRequest(actor.key(), "停用流程",
                null, null, null, designerBpmn(actor.key(), actor)));
        WorkflowDefinitionVO published = modelService.publish(modelId);

        modelService.suspend(modelId);
        assertEquals(2, modelService.detail(modelId).status().intValue(), "停用后模型状态应为 2");
        assertTrue(modelService.definitions(modelId).get(0).suspended(), "当前定义应已挂起");

        modelService.activate(modelId);
        assertEquals(1, modelService.detail(modelId).status().intValue());
        assertFalse(modelService.definitions(modelId).get(0).suspended(), "激活后应恢复可发起");

        // 停用的只是"发起能力":发起一单再停用,在途任务仍可见可办
        WorkflowInstance instance = gateway.start(actor.key(), "bk-" + actor.key(), Map.of());
        modelService.suspend(modelId);
        WorkflowTask task = gateway.findTodo(0, 50).stream()
                .filter(row -> row.instanceId().equals(instance.id())).findFirst().orElseThrow();
        gateway.complete(task.id(), Map.of());
        assertTrue(gateway.requireInstance(instance.id()).ended(), "停用不该中断在途实例");
        modelService.activate(modelId);
        assertNotNull(published.definitionId());
    }

    /** 删除要有在途闸:有在跑的单子不许删;办完之后删得掉,引擎历史不受影响 */
    @Test
    void deleteGuardsRunningInstances() {
        Actor actor = actor("wfDelete");
        Long modelId = modelService.create(new WorkflowModelCreateRequest(actor.key(), "待删流程",
                null, null, null, designerBpmn(actor.key(), actor)));
        modelService.publish(modelId);
        WorkflowInstance instance = gateway.start(actor.key(), "bk-" + actor.key(), Map.of());

        BizException blocked = assertThrows(BizException.class, () -> modelService.delete(modelId));
        assertTrue(blocked.getMessage().contains("在途"), "拒绝应点名原因:" + blocked.getMessage());

        WorkflowTask task = gateway.findTodo(0, 50).stream()
                .filter(row -> row.instanceId().equals(instance.id())).findFirst().orElseThrow();
        gateway.complete(task.id(), Map.of());
        modelService.delete(modelId);
        assertThrows(BizException.class, () -> modelService.detail(modelId), "逻辑删后模型不可再取");
        assertEquals(1L, gateway.findDone(0, 50).stream()
                .filter(row -> row.instanceId().equals(instance.id())).count(), "引擎历史应保留");
    }

    /** 标识终身占用:删掉的模型仍占唯一键,同 key 重建必须被拒 */
    @Test
    void modelKeyIsNeverReleased() {
        Actor actor = actor("wfReuse");
        Long modelId = modelService.create(new WorkflowModelCreateRequest(actor.key(), "占位流程",
                null, null, null, designerBpmn(actor.key(), actor)));
        modelService.delete(modelId);

        BizException replay = assertThrows(BizException.class,
                () -> modelService.create(new WorkflowModelCreateRequest(actor.key(), "重名流程",
                        null, null, null, designerBpmn(actor.key(), actor))));
        assertTrue(replay.getMessage().contains("已存在"), "应点名标识占用:" + replay.getMessage());
    }

    /** 模型表在租户拦截器内:换一个租户既查不到分页也取不到详情 */
    @Test
    void modelsAreTenantScoped() {
        Actor actor = actor("wfScope");
        Long modelId = modelService.create(new WorkflowModelCreateRequest(actor.key(), "隔离流程",
                null, null, null, designerBpmn(actor.key(), actor)));

        TenantContextHolder.set(TENANT_B);
        assertEquals(0L, modelService.page(new WorkflowModelQuery(1, 20, null, actor.key(), null, null)).total(),
                "他租户不得看到本租户模型");
        assertThrows(BizException.class, () -> modelService.detail(modelId), "跨租户取详情视作不存在");
        assertThrows(BizException.class, () -> modelService.saveDraft(modelId, designerBpmn(actor.key(), actor)),
                "跨租户不得改他人草稿");
    }

    /** 平台模板:全局一份,租户能选能看原文;建模型时按租户 key 改名后发布,定义 key 落在租户自己名下 */
    @Test
    void templateFeedsModelCreation() {
        Actor actor = actor("wfTemplate");
        String templateKey = "tplLevel" + actor.key().substring(10);
        Long templateId = templateService.create(new WorkflowTemplateCreateRequest(templateKey,
                "通用二级审批", "办公", "租户需自行绑定审批角色", templateBpmn(templateKey)));
        TenantContextHolder.set(TENANT_B);
        assertEquals(1L, templateService.options().stream()
                .filter(row -> row.templateId().equals(templateId)).count(), "模板目录全局一份,各租户都可选");
        TenantContextHolder.set(TENANT_A);

        Long modelId = modelService.create(new WorkflowModelCreateRequest(actor.key(), "按模板建的流程",
                null, null, templateId, null));
        String draft = modelService.draft(modelId).xml();
        assertTrue(draft.contains("id=\"" + actor.key() + "\""), "模板标识应已被改成租户 key");
        assertEquals(0, modelService.detail(modelId).status().intValue(), "从模板建出的模型仍是草稿");

        modelService.publish(modelId);
        assertEquals(actor.key(), gateway.findDefinitions(actor.key(), true).get(0).key(),
                "发布后的定义 key 必须是租户自己的标识");
        assertEquals(1L, modelService.page(new WorkflowModelQuery(1, 20, null, actor.key(), null, null)).total());

        // 改名回滚不了:模板原文仍是全局那份,不受租户模型发布影响
        assertEquals(templateKey, templateService.xml(templateId).templateKey());
        assertTrue(templateService.xml(templateId).xml().contains("id=\"" + templateKey + "\""),
                "模板原文不得被建模型动作改写");
    }

    /** 模板入库前过同一把白名单尺:发不出去的模板不该进目录 */
    @Test
    void templateContentMustBePublishable() {
        Actor actor = actor("wfBadTpl");
        BizException rejected = assertThrows(BizException.class,
                () -> templateService.create(new WorkflowTemplateCreateRequest("tplBad" + actor.key().substring(10),
                        "越界模板", null, null, scriptTaskBpmn(actor))));
        assertTrue(rejected.getMessage().contains("scriptTask"), "应点名被拒元素:" + rejected.getMessage());

        TenantContextHolder.set(TENANT_B);
        assertEquals(0L, templateService.page(new WorkflowTemplateQuery(1, 20, "越界模板", null, null, null)).total(),
                "被拒的模板不得落库");
    }

    /** 展示面改名与停用模板:模板下架后租户侧选项不再出现,但已建模型不受影响 */
    @Test
    void updateAffectsOnlyPresentationAndAvailability() {
        Actor actor = actor("wfUpdate");
        Long modelId = modelService.create(new WorkflowModelCreateRequest(actor.key(), "改名前",
                "旧分类", null, null, designerBpmn(actor.key(), actor)));
        modelService.update(modelId, new WorkflowModelUpdateRequest("改名后", "新分类", "备注"));
        WorkflowModelVO detail = modelService.detail(modelId);
        assertEquals("改名后", detail.name());
        assertEquals("新分类", detail.category());

        Long templateId = templateService.create(new WorkflowTemplateCreateRequest(
                "tplOff" + actor.key().substring(10), "待下架模板", null, null,
                templateBpmn("tplOff" + actor.key().substring(10))));
        assertTrue(templateService.options().stream().anyMatch(row -> row.templateId().equals(templateId)),
                "上架模板应出现在租户选项里");
        String xmlBefore = templateService.xml(templateId).xml();

        templateService.update(templateId, new WorkflowTemplateUpdateRequest(
                "已下架模板", null, "只下架不改内容", null, 1));
        assertFalse(templateService.options().stream().anyMatch(row -> row.templateId().equals(templateId)),
                "停用模板不得出现在租户选项里");
        assertEquals(xmlBefore, templateService.xml(templateId).xml(), "不传 XML 即保留原文");

        modelService.publish(modelId);
        assertThrows(BizException.class,
                () -> modelService.create(new WorkflowModelCreateRequest(actor.key() + "x", "坏模板流程",
                        null, null, templateId, null)), "下架模板不得再用于建模型");
    }

    /* ------------------------------ 辅助 ------------------------------ */

    /** 用例身份:后缀取自时间戳,流程标识与身份 ID 同源 */
    private Actor actor(String prefix) {
        String tag = String.valueOf(System.nanoTime() % 100_000_000L);
        long seed = Long.parseLong(tag);
        TenantContextHolder.set(TENANT_A);
        Actor actor = new Actor(prefix + tag, 940_000_000_000L + seed, 950_000_000_000L + seed);
        LoginUserContextHolder.set(actor.approver());
        return actor;
    }

    /** 设计器产物形态:默认 process id 由调用方给定,用于验证保存时的改名 */
    private static String designerBpmn(String processId, Actor actor) {
        return designerBpmn(processId, actor, "审批");
    }

    private static String designerBpmn(String processId, Actor actor, String taskName) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             targetNamespace="http://snail.platform/p2">
                  <process id="%s" name="P2 验收流程" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
                    <userTask id="approve" name="%s" flowable:candidateGroups="%s"/>
                    <sequenceFlow id="f2" sourceRef="approve" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(processId, taskName, String.valueOf(actor.roleId()));
    }

    /** 越界草稿:scriptTask 是 RCE,发布必被拒 */
    private static String scriptTaskBpmn(Actor actor) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://snail.platform/p2">
                  <process id="%s" name="越界流程" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="boom"/>
                    <scriptTask id="boom" name="执行脚本" scriptFormat="groovy">
                      <script>println 1</script>
                    </scriptTask>
                    <sequenceFlow id="f2" sourceRef="boom" targetRef="approve"/>
                    <userTask id="approve" name="审批" flowable:candidateGroups="%s"/>
                    <sequenceFlow id="f3" sourceRef="approve" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(actor.key(), String.valueOf(actor.roleId()));
    }

    /** 平台模板:不含任何租户坐标,审批人留空位由租户自己绑角色 */
    private static String templateBpmn(String processId) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://snail.platform/p2">
                  <process id="%s" name="通用二级审批" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="first"/>
                    <userTask id="first" name="一级审批" flowable:candidateGroups="1"/>
                    <sequenceFlow id="f2" sourceRef="first" targetRef="second"/>
                    <userTask id="second" name="二级审批" flowable:candidateGroups="1"/>
                    <sequenceFlow id="f3" sourceRef="second" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(processId);
    }

    /** 断言失败时只露出 XML 首行,避免整份定义糊满测试报告 */
    private static String firstLine(String xml) {
        return xml == null ? "null" : xml.lines().skip(3).findFirst().orElse("");
    }
}
