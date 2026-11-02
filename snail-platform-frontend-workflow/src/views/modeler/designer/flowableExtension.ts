/**
 * Flowable 扩展属性读写工具：面板与画布之间的唯一通道。
 * 所有写入都走 bpmn-js commandStack（可 Ctrl+Z 撤销），禁止直接改 businessObject。
 */
import flowableDescriptor from './flowable.json';

/** 注册进 Modeler 的 moddleExtensions：key 必须是 prefix */
export const FLOWABLE_MODDLE_DESCRIPTOR = flowableDescriptor;

/** 用户任务上的 Flowable 扩展属性全集（与后端白名单一字不差） */
export const USER_TASK_FLOWABLE_ATTRS = [
  'assignee',
  'candidateGroups',
  'candidateUsers',
  'actionButtons',
  'notifyType',
  'ccUsers',
  'ccGroups',
  'collection',
  'elementVariable',
] as const;

export type FlowableAttrName = (typeof USER_TASK_FLOWABLE_ATTRS)[number];

interface ModdleLike {
  $type?: string;
  [key: string]: unknown;
}

/** 元素业务对象的最小形态（避免引入 bpmn-js 内部类型链） */
export interface FlowElementLike {
  id?: string;
  businessObject: ModdleLike;
  [key: string]: unknown;
}

/** modeling 服务的最小形态 */
export interface ModelingLike {
  updateProperties: (el: unknown, props: Record<string, unknown>) => void;
  updateModdleProperties: (el: unknown, moddleEl: unknown, props: Record<string, unknown>) => void;
}

/** 读多实例特征（存在即多人审批） */
export function getMultiInstance(element: FlowElementLike): ModdleLike | undefined {
  const mi = element.businessObject.loopCharacteristics as ModdleLike | undefined;
  return mi?.$type === 'bpmn:MultiInstanceLoopCharacteristics' ? mi : undefined;
}

/** 读单个扩展属性 */
export function readFlowableAttr(element: FlowElementLike, name: FlowableAttrName): string {
  const value = element.businessObject[name];
  return typeof value === 'string' ? value : '';
}

/** 批量写扩展属性（undefined/'' 的键会被删除） */
export function writeFlowableAttrs(
  modeling: ModelingLike,
  element: FlowElementLike,
  attrs: Partial<Record<FlowableAttrName, string | undefined>>,
): void {
  const props: Record<string, string | undefined> = {};
  for (const [key, value] of Object.entries(attrs)) {
    props[key] = value === '' ? undefined : value;
  }
  (modeling.updateProperties as (el: unknown, p: Record<string, unknown>) => void)(element, props);
}

/** 串行/并行判定：isSequential=true 为串行 */
export function isSequentialMultiInstance(mi?: ModdleLike): boolean {
  return Boolean(mi?.isSequential);
}

/**
 * 写完成条件：undefined 表示删除（全员通过）。
 * FormalExpression 必须由 bpmnFactory 创建，moddle 才能正确序列化为子元素。
 */
export function writeCompletionCondition(
  modeling: ModelingLike,
  bpmnFactory: { create: (type: string, props?: Record<string, unknown>) => ModdleLike },
  element: FlowElementLike,
  mi: ModdleLike,
  condition: string | undefined,
): void {
  const value = condition
    ? bpmnFactory.create('bpmn:FormalExpression', { body: condition })
    : undefined;
  (modeling.updateModdleProperties as (
    el: unknown,
    moddleEl: unknown,
    p: Record<string, unknown>,
  ) => void)(element, mi, { completionCondition: value });
}

/** 读连线条件表达式文本（FormalExpression body） */
export function readConditionBody(sequenceFlow: FlowElementLike): string {
  const cond = sequenceFlow.businessObject.conditionExpression as { body?: string } | undefined;
  return typeof cond?.body === 'string' ? cond.body : '';
}

/** 写连线条件（FormalExpression）；undefined 删除条件 */
export function writeConditionBody(
  modeling: ModelingLike,
  bpmnFactory: { create: (type: string, props?: Record<string, unknown>) => ModdleLike },
  element: FlowElementLike,
  condition: string | undefined,
): void {
  const value = condition
    ? bpmnFactory.create('bpmn:FormalExpression', { body: condition })
    : undefined;
  (modeling.updateProperties as (el: unknown, p: Record<string, unknown>) => void)(element, {
    conditionExpression: value,
  });
}
