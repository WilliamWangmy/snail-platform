/**
 * 设计器示例流程：请假申请（多级审批 + 按天数分支 + 驳回改签）。
 * 供顶栏「加载示例」一键填充画布，演示条件网关、候选人组、发起人改签等典型用法。
 * 注意：模板字符串中的 `${}` 为 Flowable 表达式，已用 \${} 转义。
 */

export const DEMO_LEAVE_XML = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI" xmlns:flowable="http://flowable.org/bpmn" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" id="defs_blank" targetNamespace="http://flowable.org/bpmn">
  <bpmn2:process id="leave_apply" name="请假申请" isExecutable="true">
    <bpmn2:startEvent id="start" name="开始">
      <bpmn2:outgoing>Flow_1w2d7o5</bpmn2:outgoing>
    </bpmn2:startEvent>
    <bpmn2:userTask id="Activity_0wth75p" name="主管审批" flowable:candidateGroups="4" flowable:notifyType="inner" flowable:ccGroups="6">
      <bpmn2:incoming>Flow_1w2d7o5</bpmn2:incoming>
      <bpmn2:outgoing>Flow_0v5dp3i</bpmn2:outgoing>
    </bpmn2:userTask>
    <bpmn2:sequenceFlow id="Flow_1w2d7o5" sourceRef="start" targetRef="Activity_0wth75p" />
    <bpmn2:exclusiveGateway id="Gateway_0j6zfug">
      <bpmn2:incoming>Flow_0v5dp3i</bpmn2:incoming>
      <bpmn2:outgoing>Flow_11bts69</bpmn2:outgoing>
      <bpmn2:outgoing>Flow_07g5u2s</bpmn2:outgoing>
      <bpmn2:outgoing>Flow_0usdl97</bpmn2:outgoing>
    </bpmn2:exclusiveGateway>
    <bpmn2:sequenceFlow id="Flow_0v5dp3i" sourceRef="Activity_0wth75p" targetRef="Gateway_0j6zfug" />
    <bpmn2:userTask id="Activity_1d9ss0d" name="部门领导" flowable:candidateGroups="5">
      <bpmn2:incoming>Flow_11bts69</bpmn2:incoming>
      <bpmn2:outgoing>Flow_1xs6vhc</bpmn2:outgoing>
    </bpmn2:userTask>
    <bpmn2:sequenceFlow id="Flow_11bts69" name="大于2天" sourceRef="Gateway_0j6zfug" targetRef="Activity_1d9ss0d">
      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">\${approved &amp;&amp; day&gt;=2}</bpmn2:conditionExpression>
    </bpmn2:sequenceFlow>
    <bpmn2:exclusiveGateway id="Gateway_0qnq29p">
      <bpmn2:incoming>Flow_1xs6vhc</bpmn2:incoming>
      <bpmn2:outgoing>Flow_1d2lryp</bpmn2:outgoing>
      <bpmn2:outgoing>Flow_1qjzecv</bpmn2:outgoing>
      <bpmn2:outgoing>Flow_1ei37fk</bpmn2:outgoing>
    </bpmn2:exclusiveGateway>
    <bpmn2:sequenceFlow id="Flow_1xs6vhc" sourceRef="Activity_1d9ss0d" targetRef="Gateway_0qnq29p" />
    <bpmn2:userTask id="Activity_0emm1se" name="人力主管" flowable:candidateGroups="6">
      <bpmn2:incoming>Flow_1d2lryp</bpmn2:incoming>
      <bpmn2:outgoing>Flow_1sbe35a</bpmn2:outgoing>
    </bpmn2:userTask>
    <bpmn2:sequenceFlow id="Flow_1d2lryp" name="大于5天" sourceRef="Gateway_0qnq29p" targetRef="Activity_0emm1se">
      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">\${approved &amp;&amp; day&gt;=5}</bpmn2:conditionExpression>
    </bpmn2:sequenceFlow>
    <bpmn2:exclusiveGateway id="Gateway_0copxkb">
      <bpmn2:incoming>Flow_1sbe35a</bpmn2:incoming>
      <bpmn2:outgoing>Flow_18tsrut</bpmn2:outgoing>
      <bpmn2:outgoing>Flow_1l3ha4o</bpmn2:outgoing>
    </bpmn2:exclusiveGateway>
    <bpmn2:sequenceFlow id="Flow_1sbe35a" sourceRef="Activity_0emm1se" targetRef="Gateway_0copxkb" />
    <bpmn2:userTask id="Activity_0exeurp" name="公司领导" flowable:candidateGroups="7">
      <bpmn2:incoming>Flow_18tsrut</bpmn2:incoming>
      <bpmn2:outgoing>Flow_1azsfqo</bpmn2:outgoing>
    </bpmn2:userTask>
    <bpmn2:endEvent id="end" name="结束">
      <bpmn2:incoming>Flow_0chx8ix</bpmn2:incoming>
      <bpmn2:incoming>Flow_0usdl97</bpmn2:incoming>
      <bpmn2:incoming>Flow_1ei37fk</bpmn2:incoming>
    </bpmn2:endEvent>
    <bpmn2:sequenceFlow id="Flow_18tsrut" sourceRef="Gateway_0copxkb" targetRef="Activity_0exeurp">
      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">\${approved}</bpmn2:conditionExpression>
    </bpmn2:sequenceFlow>
    <bpmn2:userTask id="Activity_1u4rj3u" name="申请人修改" flowable:assignee="\${initiator}">
      <bpmn2:incoming>Flow_07g5u2s</bpmn2:incoming>
      <bpmn2:incoming>Flow_1qjzecv</bpmn2:incoming>
      <bpmn2:incoming>Flow_1l3ha4o</bpmn2:incoming>
      <bpmn2:incoming>Flow_0c6glk7</bpmn2:incoming>
    </bpmn2:userTask>
    <bpmn2:sequenceFlow id="Flow_07g5u2s" name="驳回" sourceRef="Gateway_0j6zfug" targetRef="Activity_1u4rj3u">
      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">\${!approved}</bpmn2:conditionExpression>
    </bpmn2:sequenceFlow>
    <bpmn2:sequenceFlow id="Flow_1qjzecv" name="驳回" sourceRef="Gateway_0qnq29p" targetRef="Activity_1u4rj3u">
      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">\${!approved}</bpmn2:conditionExpression>
    </bpmn2:sequenceFlow>
    <bpmn2:sequenceFlow id="Flow_1l3ha4o" name="驳回" sourceRef="Gateway_0copxkb" targetRef="Activity_1u4rj3u">
      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">\${!approved}</bpmn2:conditionExpression>
    </bpmn2:sequenceFlow>
    <bpmn2:exclusiveGateway id="Gateway_0cy5q9a">
      <bpmn2:incoming>Flow_1azsfqo</bpmn2:incoming>
      <bpmn2:outgoing>Flow_0chx8ix</bpmn2:outgoing>
      <bpmn2:outgoing>Flow_0c6glk7</bpmn2:outgoing>
    </bpmn2:exclusiveGateway>
    <bpmn2:sequenceFlow id="Flow_1azsfqo" sourceRef="Activity_0exeurp" targetRef="Gateway_0cy5q9a" />
    <bpmn2:sequenceFlow id="Flow_0chx8ix" sourceRef="Gateway_0cy5q9a" targetRef="end">
      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">\${approved}</bpmn2:conditionExpression>
    </bpmn2:sequenceFlow>
    <bpmn2:sequenceFlow id="Flow_0c6glk7" name="驳回" sourceRef="Gateway_0cy5q9a" targetRef="Activity_1u4rj3u">
      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">\${!approved}</bpmn2:conditionExpression>
    </bpmn2:sequenceFlow>
    <bpmn2:sequenceFlow id="Flow_0usdl97" name="小于2天" sourceRef="Gateway_0j6zfug" targetRef="end">
      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">\${approved &amp;&amp; day&lt;2}</bpmn2:conditionExpression>
    </bpmn2:sequenceFlow>
    <bpmn2:sequenceFlow id="Flow_1ei37fk" name="小于5天" sourceRef="Gateway_0qnq29p" targetRef="end">
      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">\${approved &amp;&amp; day&lt;5}</bpmn2:conditionExpression>
    </bpmn2:sequenceFlow>
  </bpmn2:process>
  <bpmndi:BPMNDiagram id="di_blank">
    <bpmndi:BPMNPlane id="plane_blank" bpmnElement="leave_apply">
      <bpmndi:BPMNShape id="Event_04degxi_di" bpmnElement="start">
        <dc:Bounds x="12" y="222" width="36" height="36" />
        <bpmndi:BPMNLabel>
          <dc:Bounds x="18" y="265" width="24" height="15" />
        </bpmndi:BPMNLabel>
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Activity_0wth75p_di" bpmnElement="Activity_0wth75p">
        <dc:Bounds x="100" y="200" width="100" height="80" />
        <bpmndi:BPMNLabel />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Gateway_0j6zfug_di" bpmnElement="Gateway_0j6zfug" isMarkerVisible="true">
        <dc:Bounds x="255" y="215" width="50" height="50" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Activity_1d9ss0d_di" bpmnElement="Activity_1d9ss0d">
        <dc:Bounds x="450" y="200" width="100" height="80" />
        <bpmndi:BPMNLabel />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Gateway_0qnq29p_di" bpmnElement="Gateway_0qnq29p" isMarkerVisible="true">
        <dc:Bounds x="605" y="215" width="50" height="50" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Activity_0emm1se_di" bpmnElement="Activity_0emm1se">
        <dc:Bounds x="800" y="200" width="100" height="80" />
        <bpmndi:BPMNLabel />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Gateway_0copxkb_di" bpmnElement="Gateway_0copxkb" isMarkerVisible="true">
        <dc:Bounds x="945" y="215" width="50" height="50" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Activity_0exeurp_di" bpmnElement="Activity_0exeurp">
        <dc:Bounds x="1130" y="200" width="100" height="80" />
        <bpmndi:BPMNLabel />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Event_1acus0d_di" bpmnElement="end">
        <dc:Bounds x="1412" y="222" width="36" height="36" />
        <bpmndi:BPMNLabel>
          <dc:Bounds x="1418" y="265" width="24" height="15" />
        </bpmndi:BPMNLabel>
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Activity_1u4rj3u_di" bpmnElement="Activity_1u4rj3u">
        <dc:Bounds x="580" y="470" width="100" height="80" />
        <bpmndi:BPMNLabel />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Gateway_0cy5q9a_di" bpmnElement="Gateway_0cy5q9a" isMarkerVisible="true">
        <dc:Bounds x="1305" y="215" width="50" height="50" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="Flow_1w2d7o5_di" bpmnElement="Flow_1w2d7o5">
        <di:waypoint x="48" y="240" />
        <di:waypoint x="100" y="240" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_0v5dp3i_di" bpmnElement="Flow_0v5dp3i">
        <di:waypoint x="200" y="240" />
        <di:waypoint x="255" y="240" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_11bts69_di" bpmnElement="Flow_11bts69">
        <di:waypoint x="305" y="240" />
        <di:waypoint x="450" y="240" />
        <bpmndi:BPMNLabel>
          <dc:Bounds x="356" y="222" width="44" height="15" />
        </bpmndi:BPMNLabel>
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_1xs6vhc_di" bpmnElement="Flow_1xs6vhc">
        <di:waypoint x="550" y="240" />
        <di:waypoint x="605" y="240" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_1d2lryp_di" bpmnElement="Flow_1d2lryp">
        <di:waypoint x="655" y="240" />
        <di:waypoint x="800" y="240" />
        <bpmndi:BPMNLabel>
          <dc:Bounds x="706" y="222" width="44" height="15" />
        </bpmndi:BPMNLabel>
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_1sbe35a_di" bpmnElement="Flow_1sbe35a">
        <di:waypoint x="900" y="240" />
        <di:waypoint x="945" y="240" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_18tsrut_di" bpmnElement="Flow_18tsrut">
        <di:waypoint x="995" y="240" />
        <di:waypoint x="1130" y="240" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_07g5u2s_di" bpmnElement="Flow_07g5u2s">
        <di:waypoint x="280" y="265" />
        <di:waypoint x="280" y="510" />
        <di:waypoint x="580" y="510" />
        <bpmndi:BPMNLabel>
          <dc:Bounds x="283" y="384.5" width="24" height="15" />
        </bpmndi:BPMNLabel>
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_1qjzecv_di" bpmnElement="Flow_1qjzecv">
        <di:waypoint x="630" y="265" />
        <di:waypoint x="630" y="470" />
        <bpmndi:BPMNLabel>
          <dc:Bounds x="633" y="364.5" width="24" height="15" />
        </bpmndi:BPMNLabel>
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_1l3ha4o_di" bpmnElement="Flow_1l3ha4o">
        <di:waypoint x="970" y="265" />
        <di:waypoint x="970" y="510" />
        <di:waypoint x="680" y="510" />
        <bpmndi:BPMNLabel>
          <dc:Bounds x="973" y="384.5" width="24" height="15" />
        </bpmndi:BPMNLabel>
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_1azsfqo_di" bpmnElement="Flow_1azsfqo">
        <di:waypoint x="1230" y="240" />
        <di:waypoint x="1305" y="240" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_0chx8ix_di" bpmnElement="Flow_0chx8ix">
        <di:waypoint x="1355" y="240" />
        <di:waypoint x="1412" y="240" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_0c6glk7_di" bpmnElement="Flow_0c6glk7">
        <di:waypoint x="1330" y="265" />
        <di:waypoint x="1330" y="510" />
        <di:waypoint x="680" y="510" />
        <bpmndi:BPMNLabel>
          <dc:Bounds x="1333" y="384.5" width="24" height="15" />
        </bpmndi:BPMNLabel>
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_0usdl97_di" bpmnElement="Flow_0usdl97">
        <di:waypoint x="280" y="215" />
        <di:waypoint x="280" y="80" />
        <di:waypoint x="1430" y="80" />
        <di:waypoint x="1430" y="222" />
        <bpmndi:BPMNLabel>
          <dc:Bounds x="833" y="62" width="44" height="15" />
        </bpmndi:BPMNLabel>
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_1ei37fk_di" bpmnElement="Flow_1ei37fk">
        <di:waypoint x="630" y="215" />
        <di:waypoint x="630" y="120" />
        <di:waypoint x="1430" y="120" />
        <di:waypoint x="1430" y="222" />
        <bpmndi:BPMNLabel>
          <dc:Bounds x="1008" y="102" width="44" height="15" />
        </bpmndi:BPMNLabel>
      </bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn2:definitions>
`;
