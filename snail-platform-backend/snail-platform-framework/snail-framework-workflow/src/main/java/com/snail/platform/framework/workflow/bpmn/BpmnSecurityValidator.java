package com.snail.platform.framework.workflow.bpmn;

import com.snail.platform.framework.core.exception.BizException;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * 职责:BPMN 发布前的安全闸门——非法流程定义在进引擎之前一律拒绝(方案 §5)。
 * 上游:WorkflowEngineGateway 的部署动作;下游:Flowable 的 BpmnParse。
 * 口径:BPMN 是可执行代码,发布权限等同代码部署权限。因此本校验器不是"格式检查",而是能力裁剪:
 * v1 只交人工审批所需的最小元素集,任何能触达服务器、其它租户或外部网络的构造都直接拒绝——
 * scriptTask/serviceTask/监听器/委托 Bean 是 RCE,httpTask/mailTask 是 SSRF 与数据外带,
 * callActivity 是跨租户跳转,`${bean.method()}` 是绕开以上所有裁剪的后门。
 * 解析姿势:自建 DocumentBuilderFactory 并禁 DOCTYPE,不依赖引擎的 enable-safe-xml(那只是兜底,不是闸门)。
 * 演进:元素/属性白名单是常量,后续逐项开白(定时边界事件、子流程、会签)只改这里,不改调用方。
 */
@Component
public class BpmnSecurityValidator {

    /**
     * XML 体积上限:设计器产出的审批流程远小于此,超限视为攻击或误用。
     * 公开是给业务侧存的草稿用同一把尺——草稿与发布的体积口径必须一致,否则
     * "存得进去、发不出来"会变成一个只能靠猜的错;两处各自写一遍数字才是问题。
     */
    public static final int MAX_XML_BYTES = 2 * 1024 * 1024;

    /** 元素节点上限:防"超多节点"把解析与部署拖慢 */
    private static final int MAX_ELEMENTS = 200;

    /** BPMN 2.0 模型命名空间,可执行元素都在这儿 */
    private static final String BPMN_MODEL_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    /** Flowable 扩展命名空间:只准出现登记过的属性,元素级扩展(监听器等)一律拒绝 */
    private static final String FLOWABLE_NS = "http://flowable.org/bpmn";

    /** 声明性命名空间:XMLSchema 实例标记(xsi:type)与设计器写法,不驱动执行 */
    private static final Set<String> DECLARATIVE_NAMESPACES = Set.of(
            XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI,
            XMLConstants.XML_NS_URI);

    /** 纯展示命名空间(图形信息),不驱动执行,元素与属性整体放行 */
    private static final Set<String> DISPLAY_NAMESPACES = Set.of(
            "http://www.omg.org/spec/BPMN/20100524/DI",
            "http://www.omg.org/DD",
            "http://www.omg.org/spec/DD/20100524/DC",
            "http://www.omg.org/spec/DD/20100524/DI");

    /** v1 可执行元素白名单:容器 + 人工审批所需节点 + 连线条件 + 设计器写出的流转引用 */
    private static final Set<String> ALLOWED_ELEMENTS = Set.of(
            "definitions", "process",
            "startEvent", "endEvent", "userTask",
            "exclusiveGateway", "parallelGateway", "sequenceFlow",
            "conditionExpression", "incoming", "outgoing");

    /** 允许出现在模型元素上的无命名空间属性 */
    private static final Set<String> ALLOWED_ATTRIBUTES = Set.of(
            "id", "name", "sourceRef", "targetRef", "isExecutable",
            "targetNamespace", "expressionLanguage", "typeLanguage", "namespace");

    /** 允许的 Flowable 扩展属性:只用于绑审批人,不用于绑 Bean */
    private static final Set<String> ALLOWED_FLOWABLE_ATTRIBUTES = Set.of("assignee", "candidateGroups");

    /** 流程标识形态:与平台 model_key 口径一致,小写字母开头 */
    private static final Pattern PROCESS_ID_PATTERN = Pattern.compile("[a-z][A-Za-z0-9_]{0,63}");

    /**
     * 表达式里允许出现的字符:标识符、空白与标量运算符。
     * 刻意不含 . ( ) [ ] , # @ ——这一条同时禁掉属性导航、方法调用、类型引用与参数传递,
     * ${bean.method()}、${execution.getVariable('x')}、${T(java.lang.Runtime)} 全部落网。
     */
    private static final String EXPRESSION_OPERATORS = "+-*/%<>=!&|~?:'\"";

    /**
     * 校验一份 BPMN XML 是否允许部署;任一条不过即 BizException.badRequest。
     * 文案点名被拒的元素或属性,设计器与前端可直接定位到配错的那一处。
     * 返回流程标识(process id),发布方据此核对"模型 key 与定义 key 是否一致"。
     */
    public String validate(String xml) {
        if (xml == null || xml.isBlank()) {
            throw BizException.badRequest("流程定义为空");
        }
        if (xml.getBytes(StandardCharsets.UTF_8).length > MAX_XML_BYTES) {
            throw BizException.badRequest("流程定义体积超过 2MB");
        }
        Element root = parse(xml).getDocumentElement();
        if (!BPMN_MODEL_NS.equals(root.getNamespaceURI()) || !"definitions".equals(root.getLocalName())) {
            throw BizException.badRequest("根元素必须是 BPMN definitions");
        }

        List<Element> elements = collectElements(root);
        if (elements.size() > MAX_ELEMENTS) {
            throw BizException.badRequest("流程节点数超过上限 " + MAX_ELEMENTS);
        }
        Shape shape = inspectShape(elements);
        requireExecutableShape(shape);
        for (Element element : elements) {
            checkElement(element);
            checkAttributes(element);
            checkExpressions(element);
        }
        if (!PROCESS_ID_PATTERN.matcher(shape.processKey).matches()) {
            throw BizException.badRequest("流程标识不合法(小写字母开头,仅字母数字下划线):" + shape.processKey);
        }
        return shape.processKey;
    }

    /** 自建硬化解析器:禁 DOCTYPE、禁外部实体、关 XInclude、不展开实体引用 */
    private Document parse(String xml) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setValidating(false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("BPMN 安全解析器不可用", e);
        } catch (SAXException e) {
            throw BizException.badRequest("流程定义 XML 不合法,或含有被禁止的 DOCTYPE/外部实体:" + e.getMessage());
        } catch (IOException e) {
            throw BizException.badRequest("流程定义 XML 读取失败");
        }
    }

    /** 迭代式收集全部元素(不用 XPath 与递归,免栈溢出与命名空间前缀坑) */
    private List<Element> collectElements(Element root) {
        List<Element> elements = new ArrayList<>();
        Deque<Node> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                elements.add((Element) node);
            }
            for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
                pending.push(child);
            }
        }
        return elements;
    }

    /** 统计流程骨架:process 唯一、startEvent 唯一、至少一个 endEvent 与一个 userTask */
    private Shape inspectShape(List<Element> elements) {
        Shape shape = new Shape();
        for (Element element : elements) {
            if (!BPMN_MODEL_NS.equals(element.getNamespaceURI())) {
                continue;
            }
            switch (element.getLocalName()) {
                case "process" -> {
                    if (shape.processKey != null) {
                        throw BizException.badRequest("一份定义只允许一个 process");
                    }
                    shape.processKey = element.getAttribute("id");
                }
                case "startEvent" -> shape.startEvents++;
                case "endEvent" -> shape.endEvents++;
                case "userTask" -> shape.userTasks++;
                default -> {
                }
            }
        }
        return shape;
    }

    private void requireExecutableShape(Shape shape) {
        if (shape.startEvents != 1) {
            throw BizException.badRequest("流程必须且只能有一个开始事件,当前 " + shape.startEvents + " 个");
        }
        if (shape.endEvents < 1) {
            throw BizException.badRequest("流程缺少结束事件");
        }
        if (shape.userTasks < 1) {
            throw BizException.badRequest("v1 只开放人工审批流程,至少需要一个用户任务");
        }
    }

    /** 元素级裁剪:展示与声明命名空间放行,模型命名空间只认白名单,其余命名空间元素直接拒 */
    private void checkElement(Element element) {
        String namespace = element.getNamespaceURI();
        if (namespace == null || namespace.isEmpty()) {
            throw BizException.badRequest("元素缺少命名空间:" + element.getNodeName());
        }
        if (DISPLAY_NAMESPACES.contains(namespace)) {
            return;
        }
        if (!BPMN_MODEL_NS.equals(namespace)) {
            throw BizException.badRequest("不接受的扩展元素:" + element.getNodeName());
        }
        if (!ALLOWED_ELEMENTS.contains(element.getLocalName())) {
            throw BizException.badRequest("流程元素不在 v1 白名单内:" + element.getLocalName());
        }
    }

    /** 属性级裁剪:模型元素只认结构属性,Flowable 扩展只认审批人绑定 */
    private void checkAttributes(Element element) {
        if (DISPLAY_NAMESPACES.contains(element.getNamespaceURI())) {
            return;
        }
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            String namespace = attribute.getNamespaceURI();
            // xmlns 与 xmlns:前缀是命名空间声明,DOM 里以属性形态出现,不是设计者写进去的业务属性
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(namespace)
                    || XMLConstants.XMLNS_ATTRIBUTE.equals(attribute.getNodeName())) {
                continue;
            }
            if (namespace != null && DECLARATIVE_NAMESPACES.contains(namespace)) {
                continue;
            }
            if (namespace != null && !FLOWABLE_NS.equals(namespace)) {
                throw BizException.badRequest("不接受的扩展属性:" + attribute.getNodeName());
            }
            if (FLOWABLE_NS.equals(namespace)) {
                if (!ALLOWED_FLOWABLE_ATTRIBUTES.contains(attribute.getLocalName())) {
                    throw BizException.badRequest("Flowable 扩展属性不在白名单内:" + attribute.getNodeName());
                }
            } else if (!ALLOWED_ATTRIBUTES.contains(attribute.getNodeName())) {
                throw BizException.badRequest("属性不在白名单内:" + attribute.getNodeName());
            }
        }
    }

    /** 表达式裁剪:属性与元素文本里的 ${...} 都只准读标量变量 */
    private void checkExpressions(Element element) {
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            requireScalarExpression(attributes.item(i).getNodeValue());
        }
        String text = element.getTextContent();
        if (text != null && text.contains("${")) {
            requireScalarExpression(text);
        }
    }

    private void requireScalarExpression(String value) {
        int cursor = 0;
        while ((cursor = value.indexOf("${", cursor)) >= 0) {
            int end = value.indexOf('}', cursor);
            if (end < 0) {
                throw BizException.badRequest("表达式缺少闭合括号:" + value);
            }
            String body = value.substring(cursor + 2, end);
            for (char c : body.toCharArray()) {
                if (!Character.isLetterOrDigit(c) && c != '_' && c != ' '
                        && EXPRESSION_OPERATORS.indexOf(c) < 0) {
                    throw BizException.badRequest("表达式只允许读取标量变量,不允许方法调用或属性导航:${" + body + "}");
                }
            }
            cursor = end + 1;
        }
    }

    /** 流程骨架计数,供结构性验收使用 */
    private static final class Shape {
        /** 流程标识(process id),即发布后的定义 key */
        private String processKey;
        /** 开始事件个数 */
        private int startEvents;
        /** 结束事件个数 */
        private int endEvents;
        /** 用户任务个数 */
        private int userTasks;
    }
}
