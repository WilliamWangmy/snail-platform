package com.snail.platform.framework.workflow.bpmn;

import com.snail.platform.framework.core.exception.BizException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/**
 * 职责:将 BPMN 里的流程标识(process id)换成调用方指定的标识,供"从平台模板建模型"落地(方案 §4.2)。
 * 上游:WorkflowModelService 的建模型动作;下游:无(产出仍是 BPMN 文本,发布时还要过 BpmnSecurityValidator)。
 * 为什么需要它:平台模板是全局一份,不预绑审批人、也预绑不了租户自己的流程标识;
 * 而平台的发布口径要求 model_key 与 process id 必须一致(发起按 key 定位最新版本,不一致就会产生孤儿定义)。
 * 两者对上只有一条路:租户建模型时给出自己的 key,把模板里的旧 key 连同图形信息里的引用一起改名。
 * 口径:只改标识与"值等于旧标识的属性"(BPMNDiagram 的 bpmnElement/processRef 属这类),不碰任何业务内容;
 * 改名不是安全边界——写完立刻还要过安全校验器,本类只保证改得动、改得全。
 */
@Component
public class BpmnKeyRewriter {

    /** BPMN 2.0 模型命名空间,与校验器同口径 */
    private static final String BPMN_MODEL_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    /** 图形元素的引用属性名:值指向模型元素的 id,改名必须同步 */
    private static final String BPMN_ELEMENT_ATTRIBUTE = "bpmnElement";

    /** 图形容器指向流程的属性名 */
    private static final String PROCESS_REF_ATTRIBUTE = "processRef";

    /**
     * 把 xml 的流程标识改成 newKey,返回改名后的完整 XML。
     * 原标识与 newKey 相同则原样返回,不做无谓的重排(序列化会改写空白与属性顺序)。
     */
    public String rewrite(String xml, String newKey) {
        if (xml == null || xml.isBlank()) {
            throw BizException.badRequest("流程定义为空");
        }
        Document document = parse(xml);
        Element root = document.getDocumentElement();
        if (!BPMN_MODEL_NS.equals(root.getNamespaceURI()) || !"definitions".equals(root.getLocalName())) {
            throw BizException.badRequest("根元素必须是 BPMN definitions");
        }
        List<Element> processes = childElements(root, "process", BPMN_MODEL_NS);
        if (processes.size() != 1) {
            throw BizException.badRequest("模板必须只含一个流程,当前 " + processes.size() + " 个");
        }
        Element process = processes.get(0);
        String oldKey = process.getAttribute("id");
        if (oldKey.equals(newKey)) {
            return xml;
        }

        process.setAttribute("id", newKey);
        // 引用旧标识的图形属性一并改名,漏掉它们设计器就打不开(平面找不到起点元素)
        for (Element element : allElements(root)) {
            NamedNodeMap attributes = element.getAttributes();
            for (int i = 0; i < attributes.getLength(); i++) {
                Node attribute = attributes.item(i);
                String name = attribute.getLocalName() == null ? attribute.getNodeName() : attribute.getLocalName();
                boolean refLike = BPMN_ELEMENT_ATTRIBUTE.equals(name) || PROCESS_REF_ATTRIBUTE.equals(name);
                if (refLike && oldKey.equals(attribute.getNodeValue())) {
                    attribute.setNodeValue(newKey);
                }
            }
        }
        return serialize(document);
    }

    private Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw BizException.badRequest("流程定义不是合法 XML:" + e.getMessage());
        }
    }

    private static List<Element> childElements(Element parent, String localName, String namespace) {
        List<Element> found = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element
                    && localName.equals(element.getLocalName())
                    && namespace.equals(element.getNamespaceURI())) {
                found.add(element);
            }
        }
        return found;
    }

    private static List<Element> allElements(Element root) {
        List<Element> found = new ArrayList<>();
        collect(root, found);
        return found;
    }

    private static void collect(Element current, List<Element> sink) {
        sink.add(current);
        for (Node node = current.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                collect(element, sink);
            }
        }
    }

    private static String serialize(Document document) {
        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(writer));
            return writer.toString();
        } catch (Exception e) {
            throw BizException.badRequest("流程定义序列化失败:" + e.getMessage());
        }
    }
}
