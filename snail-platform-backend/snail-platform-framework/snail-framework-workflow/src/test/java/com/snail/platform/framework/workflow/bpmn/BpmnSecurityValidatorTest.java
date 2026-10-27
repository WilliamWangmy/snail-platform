package com.snail.platform.framework.workflow.bpmn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.snail.platform.framework.core.exception.BizException;
import org.junit.jupiter.api.Test;

/**
 * 职责:BPMN 安全闸门的离线单测——把元素/属性/表达式三张白名单的正反例钉死,不连库不起上下文。
 * 上游:WorkflowGatewayFlowTest 只跑通"合法定义能部署"这条主路,拒绝矩阵在这里穷举;
 * 下游:BpmnSecurityValidator。
 * 口径:每条负例断言到文案关键词,是为了让"被拒的原因"成为契约的一部分——
 * 设计器将来给用户看的就是这句话,含糊的"校验失败"没人能自助排障。
 * 负例一律写在"骨架合法"的流程里:结构闸门先于元素闸门,否则测到的都是"缺用户任务"。
 */
class BpmnSecurityValidatorTest {

    /** 被测校验器:无状态,直接 new,不起 Spring */
    private final BpmnSecurityValidator validator = new BpmnSecurityValidator();

    /** 最小合法流程:返回流程标识供发布方核对 */
    @Test
    void acceptsMinimalApprovalFlow() {
        assertEquals("leaveApply", validator.validate(wrap("leaveApply", """
                <startEvent id="start"/>
                <sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
                <userTask id="approve" name="审批" flowable:candidateGroups="1001"/>
                <sequenceFlow id="f2" sourceRef="approve" targetRef="end"/>
                <endEvent id="end"/>""")));
    }

    /** 网关与条件表达式是审批分叉的必备件,不能因为"没见过"就拒 */
    @Test
    void acceptsGatewaysAndScalarConditions() {
        assertEquals("p", validator.validate(wrap("p", """
                <startEvent id="start"/>
                <sequenceFlow id="f1" sourceRef="start" targetRef="gw"/>
                <exclusiveGateway id="gw"/>
                <sequenceFlow id="f2" sourceRef="gw" targetRef="join">
                  <conditionExpression xsi:type="tFormalExpression"><![CDATA[${amount > 1000}]]></conditionExpression>
                </sequenceFlow>
                <sequenceFlow id="f3" sourceRef="gw" targetRef="approve"/>
                <parallelGateway id="join"/>
                <sequenceFlow id="f4" sourceRef="join" targetRef="approve"/>
                <userTask id="approve" name="审批" flowable:assignee="${initiator}"/>
                <sequenceFlow id="f5" sourceRef="approve" targetRef="end"/>
                <endEvent id="end"/>""")));
    }

    /** 设计器一定会带图形信息,展示命名空间整体放行,否则画完就能布 */
    @Test
    void acceptsDiagramInterchange() {
        assertEquals("p", validator.validate("""
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                             xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                             targetNamespace="http://snail.platform/test">
                  <process id="p" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
                    <userTask id="approve" name="审批"/>
                    <sequenceFlow id="f2" sourceRef="approve" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                  <bpmndi:BPMNDiagram id="d">
                    <bpmndi:BPMNPlane id="plane" bpmnElement="p">
                      <dc:Bounds x="10" y="20" width="30" height="40"/>
                    </bpmndi:BPMNPlane>
                  </bpmndi:BPMNDiagram>
                </definitions>"""));
    }

    /** 能触达服务器执行权或跨流程跳转的构造一律拒绝:这是"发布权限等同代码部署权限"的根据 */
    @Test
    void rejectsExecutableExtensions() {
        assertRejected("<scriptTask id=\"s\" scriptFormat=\"groovy\"><script>println 1</script></scriptTask>",
                "scriptTask");
        assertRejected("<serviceTask id=\"s\" flowable:class=\"com.snail.Evil\"/>", "serviceTask");
        assertRejected("<callActivity id=\"c\" calledElement=\"other\"/>", "callActivity");
        assertRejected("<sendTask id=\"s\"/>", "sendTask");
        assertRejected("<intermediateCatchEvent id=\"t\"><timerEventDefinition/></intermediateCatchEvent>",
                "intermediateCatchEvent");
        assertRejected("<flowable:executionListener event=\"start\" delegateExpression=\"${bean}\"/>", "扩展元素");
    }

    /** 绑 Bean 的扩展属性与元素同样危险:属性白名单只留审批人两枚 */
    @Test
    void rejectsCapabilityAttributes() {
        assertRejected("<userTask id=\"u2\" flowable:delegateExpression=\"${bean}\"/>", "delegateExpression");
        assertRejected("<userTask id=\"u2\" flowable:formKey=\"leave\"/>", "formKey");
        assertRejected("<userTask id=\"u2\" flowable:class=\"com.snail.Evil\"/>", "扩展属性");
    }

    /** 表达式只准读标量:方法调用、属性导航、类型引用都是绕开元素白名单的后门 */
    @Test
    void rejectsExpressionBackdoors() {
        assertRejected("<userTask id=\"u2\" flowable:assignee=\"${execution.getVariable('x')}\"/>", "表达式");
        assertRejected("<userTask id=\"u2\" flowable:assignee=\"${user.name}\"/>", "表达式");
        assertRejected("<userTask id=\"u2\" flowable:assignee=\"${T(java.lang.Runtime)}\"/>", "表达式");
    }

    /** 外部实体不解析:DOCTYPE 直接拒,设计器产物不该有它 */
    @Test
    void rejectsDoctype() {
        BizException error = assertThrows(BizException.class, () -> validator.validate(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<!DOCTYPE definitions [<!ENTITY x \"y\">]>"
                        + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                        + " targetNamespace=\"http://snail.platform/test\">"
                        + "<process id=\"p\" isExecutable=\"true\"><startEvent id=\"start\"/>"
                        + "<userTask id=\"approve\"/><endEvent id=\"end\"/></process></definitions>"));
        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("DOCTYPE"), error.getMessage());
    }

    /** 结构不合格的定义不能进流程:一个开始、有终、必须有人工环节、标识形态合规 */
    @Test
    void rejectsBrokenShape() {
        assertBodyRejected("""
                <startEvent id="s1"/><startEvent id="s2"/>
                <userTask id="approve"/><sequenceFlow id="f" sourceRef="s1" targetRef="approve"/>
                <endEvent id="end"/>""", "只能有一个开始事件");
        assertBodyRejected("<startEvent id=\"start\"/><userTask id=\"approve\"/>", "缺少结束事件");
        assertBodyRejected("<startEvent id=\"start\"/><endEvent id=\"end\"/>", "至少需要一个用户任务");
        assertBodyRejected("""
                <startEvent id="start"/><userTask id="approve"/><endEvent id="end"/>
                <process id="second"/>""", "一个 process");
        assertThrows(BizException.class, () -> validator.validate(
                "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                        + " targetNamespace=\"http://snail.platform/test\"/>"), "空定义应被拒");
        assertThrows(BizException.class, () -> validator.validate(""), "空白入参应被拒");
    }

    /** 流程标识形态与小写字母开头口径一致:它同时是模型 key 与查询条件 */
    @Test
    void rejectsNonLowerCaseProcessKey() {
        BizException error = assertThrows(BizException.class, () -> validator.validate(wrap("LeaveApply", """
                <startEvent id="start"/>
                <userTask id="approve"/>
                <endEvent id="end"/>""")));
        assertTrue(error.getMessage().contains("流程标识不合法"), error.getMessage());
    }

    /* ------------------------------ 辅助 ------------------------------ */

    /** 断言"骨架合法 + 越界片段"被拒且文案点名原因;业务码 400 供设计器直接提示 */
    private void assertRejected(String fragment, String expectedKeyword) {
        assertBodyRejected(SKELETON + "\n" + fragment, expectedKeyword);
    }

    private void assertBodyRejected(String processBody, String expectedKeyword) {
        BizException error = assertThrows(BizException.class,
                () -> validator.validate(wrap("p", processBody)));
        assertEquals(400, error.getCode(), error.getMessage());
        assertTrue(error.getMessage().contains(expectedKeyword),
                "拒绝文案应包含 [" + expectedKeyword + "],实际:" + error.getMessage());
    }

    /** 人工审批的最小骨架:开始→审批→结束 */
    private static final String SKELETON = """
            <startEvent id="start"/>
            <sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
            <userTask id="approve" name="审批"/>
            <sequenceFlow id="f2" sourceRef="approve" targetRef="end"/>
            <endEvent id="end"/>""";

    /** 包一层 definitions 外壳,让每条负例只测它要测的那一点 */
    private static String wrap(String processId, String processBody) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://snail.platform/test">
                  <process id="%s" isExecutable="true">
                %s
                  </process>
                </definitions>
                """.formatted(processId, processBody);
    }
}
