/**
 * 设计器领域常量与码表：流转类型、办理按钮、通知方式、执行方式、通过条件。
 * 与《流程管理平台-建模详细设计》5.3 节一一对应；码表改这里即可，面板组件不写死文案。
 */

/** 办理按钮码位（actionButtons 属性逗号拼接存储） */
export interface ActionButtonDef {
  code: string;
  label: string;
  /** 默认选中且禁用（审批恒在） */
  fixed?: boolean;
  /** 后端动作未上线：可选但任务中心不渲染 */
  pending?: boolean;
}

export const ACTION_BUTTONS: ActionButtonDef[] = [
  { code: 'approve', label: '审批', fixed: true },
  { code: 'reject', label: '驳回' },
  { code: 'transfer', label: '转派' },
  { code: 'withdraw', label: '撤销' },
  { code: 'void', label: '作废', pending: true },
  { code: 'delegate', label: '委托', pending: true },
];

export const DEFAULT_ACTION_BUTTONS = 'approve,reject,transfer';

/** 通知方式码位（notifyType 属性，缺省不通知） */
export const NOTIFY_TYPES: Array<{ code: string; label: string; pending: boolean }> = [
  { code: '', label: '不通知', pending: false },
  { code: 'sms', label: '短信', pending: true },
  { code: 'mail', label: '邮件', pending: true },
  { code: 'inner', label: '站内消息', pending: true },
];

/** 审批模式（维度一：任务生成方式） */
export type ApproveMode = 'single' | 'multi';

/** 执行方式（维度二之一，仅多人） */
export type ExecMode = 'competitive' | 'parallel' | 'serial';

/** 通过条件（维度二之二，仅并行/串行） */
export type PassCondition = 'all' | 'any' | 'ratio' | 'votes' | 'veto';

export const EXEC_MODE_LABELS: Record<ExecMode, string> = {
  competitive: '竞争（抢签）',
  parallel: '并行',
  serial: '串行',
};

/** 默认人员集合变量名（发起流程时需传入该变量） */
export const DEFAULT_COLLECTION = 'assigneeList';

/** 多实例元素变量（userTask 本体 assignee 引用它） */
export const ELEMENT_VARIABLE = 'assignee';

/**
 * 通过条件 → completionCondition 表达式。
 * 比例类刻意用整数乘法：后端表达式白名单禁小数点，0.6 会被拒。
 */
export function buildCompletionCondition(kind: PassCondition, value?: number): string | undefined {
  switch (kind) {
    case 'all':
      return undefined; // 全员通过：引擎默认语义，不写
    case 'any':
      return '${nrOfCompletedInstances >= 1}';
    case 'ratio': {
      const percent = Math.min(100, Math.max(1, Math.round(value ?? 100)));
      return `\${100 * nrOfCompletedInstances >= ${percent} * nrOfInstances}`;
    }
    case 'votes': {
      const votes = Math.max(1, Math.round(value ?? 1));
      return `\${nrOfCompletedInstances >= ${votes}}`;
    }
    case 'veto':
      return '${approved == false}';
  }
}

/** 流转类型（连线面板） */
export type FlowType = 'normal' | 'reject';

export const FLOW_CONDITIONS: Record<FlowType, string> = {
  normal: '${approved}',
  reject: '${!approved}',
};

export const FLOW_TYPE_LABELS: Record<FlowType, string> = {
  normal: '正常提交',
  reject: '驳回',
};

/** 由连线 name/条件表达式判定流转类型（画布渲染与面板回显共用） */
export function detectFlowType(name?: string, conditionBody?: string): FlowType {
  if (name === '驳回') return 'reject';
  const body = (conditionBody ?? '').trim();
  if (body === '${!approved}' || body === '${approved == false}' || body === '${approved != true}') {
    return 'reject';
  }
  return 'normal';
}

/** 空白模型初始 XML（结构与后端 v1 白名单一致，发布闸门要求 userTask>=1 故预置骨架） */
export const BLANK_XML_TEMPLATE = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                   xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                   xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                   xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                   xmlns:flowable="http://flowable.org/bpmn"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   id="defs_blank" targetNamespace="http://flowable.org/bpmn">
  <bpmn2:process id="__MODEL_KEY__" name="__MODEL_NAME__" isExecutable="true">
    <bpmn2:startEvent id="start" name="开始"/>
    <bpmn2:sequenceFlow id="flow1" sourceRef="start" targetRef="task_first"/>
    <bpmn2:userTask id="task_first" name="审批"/>
    <bpmn2:sequenceFlow id="flow2" sourceRef="task_first" targetRef="end"/>
    <bpmn2:endEvent id="end" name="结束"/>
  </bpmn2:process>
  <bpmndi:BPMNDiagram id="di_blank">
    <bpmndi:BPMNPlane id="plane_blank" bpmnElement="__MODEL_KEY__">
      <bpmndi:BPMNShape id="start_di" bpmnElement="start">
        <dc:Bounds x="180" y="180" width="36" height="36"/>
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="task_first_di" bpmnElement="task_first">
        <dc:Bounds x="270" y="158" width="100" height="80"/>
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="end_di" bpmnElement="end">
        <dc:Bounds x="430" y="180" width="36" height="36"/>
      </bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="flow1_di" bpmnElement="flow1">
        <di:waypoint x="216" y="198"/>
        <di:waypoint x="270" y="198"/>
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow2_di" bpmnElement="flow2">
        <di:waypoint x="370" y="198"/>
        <di:waypoint x="430" y="198"/>
      </bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn2:definitions>
`;

export function blankXml(modelKey: string, modelName: string): string {
  return BLANK_XML_TEMPLATE.replaceAll('__MODEL_KEY__', modelKey).replaceAll('__MODEL_NAME__', modelName);
}
