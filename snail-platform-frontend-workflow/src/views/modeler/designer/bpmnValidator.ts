/**
 * 前端白名单校验器：完整镜像后端 BpmnSecurityValidator 规则（含附录 7.2 白名单改动后口径）。
 * 保存/发布前本地拦截，error 阻断、warn 仅提示；target 指向元素 id 供画布定位标红。
 */
import type { FlowElementLike } from './flowableExtension';
import { getMultiInstance, readFlowableAttr } from './flowableExtension';
import { ACTION_BUTTONS, NOTIFY_TYPES } from './flowType';

export interface ValidationIssue {
  level: 'error' | 'warn';
  /** 元素 id：画布/面板定位用 */
  target?: string;
  message: string;
}

export interface ModelerLike {
  get(service: 'elementRegistry'): { getAll: () => FlowElementLike[] };
  get(service: 'canvas'): { getRootElements: () => FlowElementLike[] };
  get(service: 'bpmnFactory'): { create: (type: string, props?: Record<string, unknown>) => unknown };
}

/** v1 元素白名单（BPMN 模型命名空间 localName） */
const ALLOWED_TYPES = new Set([
  'bpmn:Process',
  'bpmn:StartEvent',
  'bpmn:EndEvent',
  'bpmn:UserTask',
  'bpmn:ExclusiveGateway',
  'bpmn:ParallelGateway',
  'bpmn:SequenceFlow',
]);

const MAX_ELEMENT_COUNT = 200;
const MAX_XML_BYTES = 2 * 1024 * 1024;
const PROCESS_ID_PATTERN = /^[a-z][A-Za-z0-9_]{0,63}$/;

/** 表达式字符白名单：刻意不含 . ( ) [ ] , # @ */
const EXPRESSION_OPERATORS = "+-*/%<>=!&|~?:'\"";

function isScalarExpressionBody(body: string): boolean {
  return [...body].every((c) => c === '_' || c === ' ' || /[A-Za-z0-9]/.test(c) || EXPRESSION_OPERATORS.includes(c));
}

function scanExpressionText(text: string, where: string, issues: ValidationIssue[]): void {
  let cursor = 0;
  while ((cursor = text.indexOf('${', cursor)) >= 0) {
    const end = text.indexOf('}', cursor);
    if (end < 0) {
      issues.push({ level: 'error', message: `${where}：表达式缺少闭合括号` });
      return;
    }
    const body = text.slice(cursor + 2, end);
    if (!isScalarExpressionBody(body)) {
      issues.push({
        level: 'error',
        message: `${where}：表达式只允许读取标量变量，不允许方法调用或属性导航（\${${body}}）`,
      });
    }
    cursor = end + 1;
  }
}

/** 码值注册表：与后端动作存在性对齐（作废/委托为预留码位，配置合法但任务中心暂不渲染） */
const ACTION_CODES = new Set(ACTION_BUTTONS.map((b) => b.code));
const NOTIFY_CODES = new Set(['', ...NOTIFY_TYPES.map((n) => n.code)]);

function validateElement(el: FlowElementLike, issues: ValidationIssue[]): void {
  const bo = el.businessObject;
  const type = bo.$type as string;
  const id = (bo.id as string) ?? '';
  const where = `${type}${id ? `(${id})` : ''}`;

  if (!ALLOWED_TYPES.has(type)) {
    issues.push({ level: 'error', target: id, message: `元素不在 v1 白名单内：${type}` });
    return;
  }

  // 所有属性值统一过表达式扫描（含 assignee=${assignee} 等引用写法）
  for (const [key, value] of Object.entries(bo)) {
    if (key.startsWith('$') || typeof value !== 'string') continue;
    scanExpressionText(value, `${where}.${key}`, issues);
  }

  if (type === 'bpmn:UserTask') {
    validateUserTask(el, id, where, issues);
  }

  if (type === 'bpmn:SequenceFlow') {
    const cond = bo.conditionExpression as { body?: string } | undefined;
    const body = cond?.body?.trim() ?? '';
    if (body && !body.startsWith('${')) {
      issues.push({ level: 'error', target: id, message: `${where}：条件表达式必须形如 \${...}` });
    } else if (body) {
      scanExpressionText(body, where, issues);
    }
  }

  const mi = getMultiInstance(el);
  if (mi) {
    const condBody = (mi.completionCondition as { body?: string } | undefined)?.body?.trim() ?? '';
    if (condBody && !condBody.startsWith('${')) {
      issues.push({ level: 'error', target: id, message: `${where}：完成条件必须形如 \${...}` });
    } else if (condBody) {
      scanExpressionText(condBody, where, issues);
    }
  }
}

function validateUserTask(el: FlowElementLike, id: string, where: string, issues: ValidationIssue[]): void {
  const mi = getMultiInstance(el);
  const buttons = readFlowableAttr(el, 'actionButtons');
  if (buttons) {
    const invalid = buttons.split(',').map((s) => s.trim()).filter((c) => c && !ACTION_CODES.has(c));
    if (invalid.length > 0) {
      issues.push({ level: 'error', target: id, message: `${where}：办理按钮码值非法（${invalid.join(',')}）` });
    }
  }
  const notify = readFlowableAttr(el, 'notifyType');
  if (!NOTIFY_CODES.has(notify)) {
    issues.push({ level: 'error', target: id, message: `${where}：通知方式码值非法（${notify}）` });
  }

  if (mi) {
    // 多人-并行/串行：userTask 本体应引用元素变量
    const assignee = readFlowableAttr(el, 'assignee');
    const elementVariable = String(mi.elementVariable ?? '');
    if (assignee && assignee !== `\${${elementVariable}}`) {
      issues.push({
        level: 'warn',
        target: id,
        message: `${where}：多人审批下 assignee 建议引用元素变量 \${${elementVariable}}`,
      });
    }
    return;
  }

  // 单人/竞争：审批人绑定检查
  const hasAssignee = Boolean(readFlowableAttr(el, 'assignee'));
  const hasGroups = Boolean(readFlowableAttr(el, 'candidateGroups'));
  const hasCandidates = Boolean(readFlowableAttr(el, 'candidateUsers'));
  if (!hasAssignee && !hasGroups && !hasCandidates) {
    issues.push({ level: 'warn', target: id, message: `${where}：该任务无审批人` });
  }
}

/** 网关出线必须携带条件（流转类型一选即有），否则该分支永远无法到达 */
function validateGatewayBranches(el: FlowElementLike, issues: ValidationIssue[]): void {
  const outgoings = (el as unknown as { outgoing?: FlowElementLike[] }).outgoing ?? [];
  if (outgoings.length <= 1) return;
  for (const flow of outgoings) {
    const body = readCondition(flow);
    if (!body) {
      issues.push({
        level: 'warn',
        target: (flow.businessObject.id as string) ?? '',
        message: `网关出线(${flow.businessObject.id})未配条件，该分支永远无法到达`,
      });
    }
  }
}

function readCondition(flow: FlowElementLike): string {
  const cond = flow.businessObject.conditionExpression as { body?: string } | undefined;
  return cond?.body?.trim() ?? '';
}

/** 外部标签是独立图形元素，businessObject 复用被标注元素，校验与计数都要排除 */
function isDiagramLabel(el: FlowElementLike): boolean {
  return el.type === 'label';
}

/** 画布内联校验：结构 + 白名单 + 表达式 + 分支 */
export function validateModel(modeler: ModelerLike): ValidationIssue[] {
  const issues: ValidationIssue[] = [];
  const registry = modeler.get('elementRegistry').getAll();

  for (const el of registry) {
    if (!isDiagramLabel(el)) {
      validateElement(el, issues);
    }
  }

  // 结构闸门
  const roots = modeler.get('canvas').getRootElements();
  const process = roots.find((el) => el.businessObject.$type === 'bpmn:Process');
  if (process) {
    const children = ((process as unknown as { children?: FlowElementLike[] }).children ?? []).filter(
      (el) => typeof el.businessObject?.$type === 'string' && !isDiagramLabel(el),
    );
    const count = (type: string) => children.filter((el) => el.businessObject.$type === type).length;
    if (count('bpmn:StartEvent') !== 1) {
      issues.push({ level: 'error', message: '流程必须恰好包含一个开始事件' });
    }
    if (count('bpmn:EndEvent') < 1) {
      issues.push({ level: 'error', message: '流程缺少结束事件' });
    }
    if (count('bpmn:UserTask') < 1) {
      issues.push({ level: 'error', message: 'v1 只开放人工审批流程，至少需要一个用户任务' });
    }

    const processId = String(process.businessObject.id ?? '');
    if (!PROCESS_ID_PATTERN.test(processId)) {
      issues.push({
        level: 'error',
        target: processId,
        message: `流程标识必须小写字母开头（[a-z][A-Za-z0-9_]{0,63}）：当前 ${processId}`,
      });
    }

    for (const el of children) {
      const type = el.businessObject.$type as string;
      if (type === 'bpmn:ExclusiveGateway' || type === 'bpmn:ParallelGateway') {
        validateGatewayBranches(el, issues);
      }
    }
  }

  const elementCount = registry.filter((el) => !isDiagramLabel(el)).length;
  if (elementCount > MAX_ELEMENT_COUNT) {
    issues.push({ level: 'error', message: `元素总数超过 ${MAX_ELEMENT_COUNT}` });
  }

  return issues;
}

/** XML 预检：体积（2MB 上限，与后端一致） */
export function validateXmlSize(xml: string): ValidationIssue[] {
  if (new TextEncoder().encode(xml).length > MAX_XML_BYTES) {
    return [{ level: 'error', message: '流程定义体积超过 2MB' }];
  }
  return [];
}
