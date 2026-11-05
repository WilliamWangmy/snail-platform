package com.snail.platform.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.snail.platform.framework.core.context.LoginUser;
import com.snail.platform.framework.core.context.LoginUserContextHolder;
import com.snail.platform.framework.core.context.TenantContextHolder;
import com.snail.platform.framework.workflow.engine.WorkflowEngineGateway;
import com.snail.platform.framework.workflow.engine.WorkflowNodeNotifyConfig;
import com.snail.platform.function.external.api.MailPort;
import com.snail.platform.function.external.entity.SysExternalCallLog;
import com.snail.platform.function.external.mapper.SysExternalCallLogMapper;
import com.snail.platform.function.notify.entity.SysNotifyMessage;
import com.snail.platform.function.notify.entity.SysNotifyOutbound;
import com.snail.platform.function.notify.entity.SysNotifyRecipient;
import com.snail.platform.function.notify.mapper.SysNotifyMessageMapper;
import com.snail.platform.function.notify.mapper.SysNotifyOutboundMapper;
import com.snail.platform.function.notify.mapper.SysNotifyRecipientMapper;
import com.snail.platform.function.workflow.api.WorkflowStartCommand;
import com.snail.platform.function.workflow.instance.service.WorkflowInstanceService;
import com.snail.platform.function.workflow.model.dto.WorkflowModelCreateRequest;
import com.snail.platform.function.workflow.model.dto.WorkflowDefinitionVO;
import com.snail.platform.function.workflow.model.service.WorkflowModelService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 职责:节点通知验收——notifyType=mail 的节点只投邮件不落收件行、未配置节点回落站内信(存量不回归,方案 D3)、
 * BPMN 自定义属性经网关读回比对(D9 保留性实测断言)。
 * 上游:snail-platform-start 完整上下文(需本机 MySQL/Valkey,SNAIL_DB_* 未设则整类跳过);
 * 下游:模型域发布→网关发起→通知落库/出站,真实链路,替身只替 MailPort。
 * 口径:开发库无 SMTP 配置,MailPort 换成内存替身并置为可用——专证"通道按 notifyType 精确投递与过滤",
 * SMTP 协议细节归 external 网关自己的测试;出站投递在专用线程,断言前按流水行轮询等待。
 * 数据隔离:每个用例按时间戳派生流程标识,消息按业务单号筛,残留与既有流程测试同口径。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SNAIL_DB_USERNAME", matches = ".+")
class WorkflowNodeNotifyFlowTest {

    /** 平台默认租户 */
    private static final Long TENANT_A = 1L;

    /** 种子超管:发起人与唯一收件人,候选组绑它的角色 */
    private static final Long REAL_USER_ID = 1L;

    private static final Long REAL_ROLE_ID = 1L;

    /** 出站投递 500ms 轮询 + 真实落库,5s 窗口足够 */
    private static final long AWAIT_MS = 5000;

    @Autowired
    private WorkflowModelService modelService;

    /** 发起走业务编排层:remindTodo 挂在它身上,直接 gateway.start 不会产生通知 */
    @Autowired
    private WorkflowInstanceService instanceService;

    @Autowired
    private WorkflowEngineGateway gateway;

    @Autowired
    private SysNotifyMessageMapper messageMapper;

    @Autowired
    private SysNotifyRecipientMapper recipientMapper;

    @Autowired
    private SysNotifyOutboundMapper outboundMapper;

    @Autowired
    private SysExternalCallLogMapper externalCallLogMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 邮件端口替身:开发库无 SMTP,置可用后由通知链路真实调用,投递内容就地记录 */
    @MockitoBean
    private MailPort mailPort;

    private final List<MailPort.MailMessage> sentMails = new ArrayList<>();

    /** 短信模板编码:开发库无阿里云报备,测试注入占位编码打通 mock 厂商链路 */
    @DynamicPropertySource
    static void smsTemplate(DynamicPropertyRegistry registry) {
        registry.add("snail.notify.sms.templates.todo", () -> "SMS_TEST_TODO");
    }

    @BeforeEach
    void setUp() {
        TenantContextHolder.set(TENANT_A);
        // 收件人地址要真实存在,邮件/短信通道才有寻址目标;残留与既有流程测试同口径
        jdbcTemplate.update("UPDATE sys_user SET email = ?, phone = ? WHERE user_id = ?",
                "admin-node-notify@example.com", "13800001234", REAL_USER_ID);
        when(mailPort.available()).thenReturn(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            synchronized (sentMails) {
                sentMails.add(invocation.getArgument(0, MailPort.MailMessage.class));
            }
            return null;
        }).when(mailPort).send(any(MailPort.MailMessage.class));
    }

    @AfterEach
    void clearContext() {
        LoginUserContextHolder.clear();
        TenantContextHolder.clear();
    }

    /** notifyType=mail 的节点:邮件出站命中替身,收件行与 sse 行都不存在——精确通道,不广播 */
    @Test
    void mailNodeNotifiesByMailWithoutInboxRow() throws Exception {
        String key = deployFlow("notifyMail", "mail");
        LoginUserContextHolder.set(initiator());
        instanceService.start(new WorkflowStartCommand(key, "node-notify-flow", "bk-" + key,
                "邮件节点验收单", Map.of()));

        long messageId = workflowMessageIdOf("bk-" + key);
        awaitOutbound(messageId, row -> "mail".equals(row.getChannel()) && row.getStatus() == 1);
        synchronized (sentMails) {
            assertTrue(sentMails.stream().anyMatch(mail -> mail.to() != null
                            && mail.to().contains("admin-node-notify@example.com")),
                    "邮件应投到收件人档案地址: " + sentMails);
        }
        assertEquals(0L, recipientRowsOf(messageId), "未勾选站内信的节点不落收件行,收件箱不出现待办提醒");
        assertTrue(outboundRowsOf(messageId).stream().allMatch(row -> "mail".equals(row.getChannel())),
                "不得广播到其它通道: " + outboundRowsOf(messageId).stream().map(SysNotifyOutbound::getChannel).toList());
        assertEquals(0L, smsCallLogRowsOf(messageId), "未勾短信不得触发短信投递");
    }

    /** 未配置 notifyType 的节点回落站内信:收件行在、sse 推送在,邮件与短信不出(存量模型不回归,D3) */
    @Test
    void missingNotifyTypeFallsBackToInner() throws Exception {
        String key = deployFlow("notifyFallback", null);
        LoginUserContextHolder.set(initiator());
        instanceService.start(new WorkflowStartCommand(key, "node-notify-flow", "bk-" + key,
                "回落验收单", Map.of()));

        long messageId = workflowMessageIdOf("bk-" + key);
        assertEquals(1L, recipientRowsOf(messageId), "存量节点待办提醒照旧落收件行");
        awaitOutbound(messageId, row -> "sse".equals(row.getChannel()) && row.getStatus() == 1);
        assertTrue(outboundRowsOf(messageId).stream().noneMatch(row -> "mail".equals(row.getChannel())),
                "未勾邮件就不应出站邮件");
        assertEquals(0L, smsCallLogRowsOf(messageId), "未勾短信就不应出站短信");
        synchronized (sentMails) {
            assertTrue(sentMails.isEmpty(), "回落站内信不得触发邮件投递: " + sentMails);
        }
    }

    /** notifyType=sms 的节点:走真实 mock 短信厂商落 external 流水,收件行不落(方案 D8/M2 验收) */
    @Test
    void smsNodeSendsTemplatedSmsWithoutInboxRow() throws Exception {
        String key = deployFlow("notifySms", "sms");
        LoginUserContextHolder.set(initiator());
        instanceService.start(new WorkflowStartCommand(key, "node-notify-flow", "bk-" + key,
                "短信节点验收单", Map.of()));

        long messageId = workflowMessageIdOf("bk-" + key);
        awaitOutbound(messageId, row -> "sms".equals(row.getChannel()) && row.getStatus() == 1);
        assertEquals(0L, recipientRowsOf(messageId), "纯短信提醒不落收件行");
        synchronized (sentMails) {
            assertTrue(sentMails.isEmpty(), "短信节点不得触发邮件投递: " + sentMails);
        }
        awaitSmsCallLog(messageId, 1);
    }

    /** D9 保留性实测:含全部自定义属性的 XML 部署后经网关读回比对,未知属性必须原样保留 */
    @Test
    void nodeNotifyConfigReadsBackCustomAttributes() {
        String tag = String.valueOf(System.nanoTime() % 100_000_000L);
        String key = "notifyD9" + tag;
        LoginUserContextHolder.set(initiator());
        String bpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://snail.platform/node-notify">
                  <process id="%s" name="%s 验收流程" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
                    <userTask id="approve" name="全属性节点" flowable:candidateGroups="%s"
                              flowable:candidateUsers="960000001" flowable:notifyType="mail,sms"
                              flowable:ccUsers="1,2" flowable:ccGroups="3"/>
                    <sequenceFlow id="f2" sourceRef="approve" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key, key, String.valueOf(REAL_ROLE_ID));
        Long modelId = modelService.create(new WorkflowModelCreateRequest(key, "节点通知配置验收", null, null, null, bpmn));
        WorkflowDefinitionVO definition = modelService.publish(modelId);

        WorkflowNodeNotifyConfig config = gateway.nodeNotifyConfig(definition.definitionId(), "approve");
        assertEquals(Set.of("mail", "sms"), config.notifyTypes(), "notifyType 逗号串应拆分保留");
        assertEquals(List.of("1", "2"), config.ccUsers(), "抄送用户应原样读回");
        assertEquals(List.of("3"), config.ccGroups(), "抄送角色应原样读回");
        assertEquals(WorkflowNodeNotifyConfig.empty(), gateway.nodeNotifyConfig(definition.definitionId(), "start"),
                "非用户任务节点没有通知配置,统一空配置口径");
    }

    /* ------------------------------ 辅助 ------------------------------ */

    private static LoginUser initiator() {
        return new LoginUser(REAL_USER_ID, TENANT_A, "默认租户", "admin", "超级管理员",
                1L, "总部", 1L, List.of(new LoginUser.RoleInfo(REAL_ROLE_ID, "admin", "超级管理员")), false);
    }

    /** 建模型→发布:notifyType 为 null 即存量口径(节点无通知属性) */
    private String deployFlow(String prefix, String notifyType) {
        String tag = String.valueOf(System.nanoTime() % 100_000_000L);
        String key = prefix + tag;
        LoginUserContextHolder.set(initiator());
        String notifyAttr = notifyType == null ? "" : "flowable:notifyType=\"" + notifyType + "\"";
        String bpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://snail.platform/node-notify">
                  <process id="%s" name="%s 验收流程" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
                    <userTask id="approve" name="验收节点" flowable:candidateGroups="%s" %s/>
                    <sequenceFlow id="f2" sourceRef="approve" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key, key, String.valueOf(REAL_ROLE_ID), notifyAttr);
        Long modelId = modelService.create(new WorkflowModelCreateRequest(key, prefix + "验收流程", null, null, null, bpmn));
        modelService.publish(modelId);
        return key;
    }

    /** 本用例业务单号对应的通知消息 ID:文案带单号是产品口径,按它筛才不会把别人的消息算进来 */
    private long workflowMessageIdOf(String businessKey) {
        List<SysNotifyMessage> matched = messageMapper.selectList(Wrappers.<SysNotifyMessage>lambdaQuery()
                .eq(SysNotifyMessage::getBizType, "workflow")
                .like(SysNotifyMessage::getContent, businessKey));
        assertEquals(1, matched.size(), "本单应恰好产生一条通知消息: " + matched.size());
        return matched.get(0).getMessageId();
    }

    private long recipientRowsOf(long messageId) {
        return recipientMapper.selectCount(Wrappers.<SysNotifyRecipient>lambdaQuery()
                .eq(SysNotifyRecipient::getMessageId, messageId));
    }

    private List<SysNotifyOutbound> outboundRowsOf(long messageId) {
        return outboundMapper.selectList(Wrappers.<SysNotifyOutbound>lambdaQuery()
                .eq(SysNotifyOutbound::getMessageId, messageId));
    }

    /** 短信经 external 网关的流水行:caller=notify、biz_id=消息 ID,由 SmsOutboundChannel 写入 */
    private long smsCallLogRowsOf(long messageId) {
        return externalCallLogMapper.selectCount(Wrappers.<SysExternalCallLog>lambdaQuery()
                .eq(SysExternalCallLog::getCaller, "notify")
                .eq(SysExternalCallLog::getBizId, messageId));
    }

    /** 流水落账走异步队列,断言前轮询等待;超时把现有行数摆进错误信息 */
    private void awaitSmsCallLog(long messageId, long expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + AWAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (smsCallLogRowsOf(messageId) >= expected) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("短信流水未达到 " + expected + " 行,现有 " + smsCallLogRowsOf(messageId) + " 行");
    }

    /** 轮询等待出站流水出现命中行;超时把已有行摆进断言消息,失败可读 */
    private void awaitOutbound(long messageId, Predicate<SysNotifyOutbound> match) throws InterruptedException {
        long deadline = System.currentTimeMillis() + AWAIT_MS;
        List<SysNotifyOutbound> rows = List.of();
        while (System.currentTimeMillis() < deadline) {
            rows = outboundRowsOf(messageId);
            if (rows.stream().anyMatch(match)) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("出站流水未出现期望行,现有: " + rows.stream()
                .map(row -> row.getChannel() + "#" + row.getStatus()).toList());
    }
}
