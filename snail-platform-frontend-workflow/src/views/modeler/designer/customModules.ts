/**
 * bpmn-js 自定义模块：元素栏裁剪（v1 六项）、ContextPad/ReplaceMenu 裁剪与中文化（2.1 白名单）、
 * 连线规则（仅 sequenceFlow、禁连开始事件）、新建开始/结束事件默认值（id=start|end/名=开始|结束）、
 * 新连线默认条件（网关出线 ${approved}）、Task→UserTask 统一映射、驳回线红色虚线渲染。
 * 注册方式见 BpmnCanvas.vue 的 additionalModules。
 */
import BaseRenderer from 'diagram-js/lib/draw/BaseRenderer';
import CommandInterceptor from 'diagram-js/lib/command/CommandInterceptor';
import type { Connection } from 'diagram-js/lib/model/Types';
import { createLine } from 'diagram-js/lib/util/RenderUtil';
import BpmnElementFactory from 'bpmn-js/lib/features/modeling/ElementFactory';
import { append } from 'tiny-svg';

import { FLOW_CONDITIONS, detectFlowType } from './flowType';

/* ---------------------------------- 元素工厂（Task→UserTask） ---------------------------------- */

/**
 * v1 白名单只开放用户任务：任何途径新建的 bpmn:Task（ContextPad 追加任务等）统一映射为
 * bpmn:UserTask。拦截点在 createElement（createShape/create/replace 全部经此汇聚），
 * 映射发生在元素落盘前，撤销/重做语义与直接创建 UserTask 一致。
 */
class FlowElementFactory extends BpmnElementFactory {
  static $inject = ['bpmnFactory', 'moddle'];
}

const BaseCreateElement = BpmnElementFactory.prototype.createElement as unknown as (
  this: BpmnElementFactory,
  elementType: string,
  attrs?: Record<string, unknown>,
) => unknown;

FlowElementFactory.prototype.createElement = function (
  this: BpmnElementFactory,
  elementType: 'root' | 'shape' | 'connection',
  attrs?: Record<string, unknown>,
) {
  if (elementType === 'shape' && attrs?.type === 'bpmn:Task') {
    return BaseCreateElement.call(this, elementType, { ...attrs, type: 'bpmn:UserTask' });
  }
  return BaseCreateElement.call(this, elementType, attrs);
} as unknown as typeof FlowElementFactory.prototype.createElement;

/* ---------------------------------- 元素栏 ---------------------------------- */

const REJECT_COLOR = '#ef7269';

interface PaletteDeps {
  create: { start: (event: Event, shape: unknown) => void };
  elementFactory: { createShape: (attrs: { type: string }) => unknown };
  handTool: { activateHand: (event: Event) => void };
  lassoTool: { activateSelection: (event: Event) => void };
  spaceTool: { activateSelection: (event: Event) => void };
  globalConnect: { start: (event: Event) => void };
}

/** v1 元素栏：4 个通用工具 + 5 个可创建元素（连线走 global-connect 工具） */
class FlowPaletteProvider {
  static $inject = [
    'palette',
    'create',
    'elementFactory',
    'handTool',
    'lassoTool',
    'spaceTool',
    'globalConnect',
  ];

  constructor(palette: { registerProvider: (p: unknown) => void }, deps: PaletteDeps) {
    this.deps = deps;
    palette.registerProvider(this);
  }

  private deps: PaletteDeps;

  getPaletteEntries(): () => Record<string, unknown> {
    const { create, elementFactory, handTool, lassoTool, spaceTool, globalConnect } = this.deps;
    const createElement = (type: string) => (event: Event) => {
      create.start(event, elementFactory.createShape({ type }));
    };

    return function () {
      // 以函数形态返回：丢弃默认调色板全部条目，只保留 v1 白名单内的元素
      return {
        'hand-tool': {
          group: 'tools',
          className: 'bpmn-icon-hand-tool',
          title: '移动画布',
          action: { click: (event: Event) => handTool.activateHand(event) },
        },
        'lasso-tool': {
          group: 'tools',
          className: 'bpmn-icon-lasso-tool',
          title: '框选元素',
          action: { click: (event: Event) => lassoTool.activateSelection(event) },
        },
        'space-tool': {
          group: 'tools',
          className: 'bpmn-icon-space-tool',
          title: '调整间距',
          action: { click: (event: Event) => spaceTool.activateSelection(event) },
        },
        'global-connect-tool': {
          group: 'tools',
          className: 'bpmn-icon-connection-multi',
          title: '连线',
          action: { click: (event: Event) => globalConnect.start(event) },
        },
        'tool-separator': { group: 'tools', separator: true },
        'create.start-event': {
          group: 'event',
          className: 'bpmn-icon-start-event-none',
          title: '开始事件',
          action: { click: createElement('bpmn:StartEvent'), dragstart: createElement('bpmn:StartEvent') },
        },
        'create.end-event': {
          group: 'event',
          className: 'bpmn-icon-end-event-none',
          title: '结束事件',
          action: { click: createElement('bpmn:EndEvent'), dragstart: createElement('bpmn:EndEvent') },
        },
        'create.user-task': {
          group: 'activity',
          className: 'bpmn-icon-user',
          title: '用户任务',
          action: { click: createElement('bpmn:UserTask'), dragstart: createElement('bpmn:UserTask') },
        },
        'create.exclusive-gateway': {
          group: 'gateway',
          className: 'bpmn-icon-gateway-xor',
          title: '排他网关',
          action: {
            click: createElement('bpmn:ExclusiveGateway'),
            dragstart: createElement('bpmn:ExclusiveGateway'),
          },
        },
        'create.parallel-gateway': {
          group: 'gateway',
          className: 'bpmn-icon-gateway-parallel',
          title: '并行网关',
          action: {
            click: createElement('bpmn:ParallelGateway'),
            dragstart: createElement('bpmn:ParallelGateway'),
          },
        },
      };
    };
  }
}

/* ---------------------------------- 连线规则 ---------------------------------- */

interface ConnectRuleEvent {
  context?: { target?: { type?: string }; newTarget?: { type?: string } };
  newTarget?: { type?: string };
  target?: { type?: string };
}

/** 连线规则：不允许连线到开始事件（其余 sequenceFlow 规则由 bpmn-js 默认规则覆盖） */
class FlowConnectRules {
  static $inject = ['eventBus'];

  constructor(eventBus: {
    on: (topics: string[], priority: number, handler: (event: ConnectRuleEvent) => false | undefined) => void;
  }) {
    eventBus.on(['connect.canExecute', 'reconnect.canExecute'], 2000, (event) => {
      const context = event.context ?? event;
      const target = context?.target ?? context?.newTarget;
      if (target?.type === 'bpmn:StartEvent') {
        return false;
      }
      return undefined;
    });
  }
}

/* ------------------------- ContextPad / ReplaceMenu 裁剪与中文化 ------------------------- */

/**
 * 2.1 支持清单的扳手白名单：key = businessObject.$type，value = 保留条目（ReplaceOptions 的
 * actionName id）。未命中类型不裁剪（无法经调色板/ContextPad 创建的类型本就不会出现）。
 */
const REPLACE_WHITELIST: Record<string, Set<string>> = {
  // bpmn:Task 不在 v1 白名单：扳手菜单只保留「换为用户任务」（新建 Task 也会被元素工厂映射为 UserTask）
  'bpmn:Task': new Set(['replace-with-user-task']),
  'bpmn:UserTask': new Set(['replace-with-user-task']),
  'bpmn:StartEvent': new Set(['replace-with-none-start', 'replace-with-none-end', 'replace-with-timer-start', 'replace-with-message-start']),
  'bpmn:EndEvent': new Set(['replace-with-none-start', 'replace-with-none-end', 'replace-with-terminate-end']),
  'bpmn:ExclusiveGateway': new Set(['replace-with-parallel-gateway', 'replace-with-inclusive-gateway']),
  'bpmn:ParallelGateway': new Set(['replace-with-exclusive-gateway', 'replace-with-inclusive-gateway']),
  'bpmn:SequenceFlow': new Set(['replace-with-sequence-flow', 'replace-with-default-flow', 'replace-with-conditional-flow']),
  'bpmn:IntermediateThrowEvent': new Set(['replace-with-timer-intermediate-catch']),
  'bpmn:IntermediateCatchEvent': new Set(['replace-with-timer-intermediate-catch']),
  'bpmn:BoundaryEvent': new Set(['replace-with-timer-boundary', 'replace-with-non-interrupting-timer-boundary']),
};

/** 扳手条目中文文案（待接入 = 运行端能力未跟上，见 2.1 配套机制③） */
const REPLACE_LABELS: Record<string, string> = {
  'replace-with-user-task': '用户任务',
  'replace-with-none-start': '开始事件',
  'replace-with-none-end': '结束事件',
  'replace-with-timer-start': '定时开始事件（待接入）',
  'replace-with-message-start': '消息开始事件（待接入）',
  'replace-with-terminate-end': '终止结束事件（待接入）',
  'replace-with-exclusive-gateway': '排他网关',
  'replace-with-parallel-gateway': '并行网关',
  'replace-with-inclusive-gateway': '相容网关（待接入）',
  'replace-with-sequence-flow': '普通连线',
  'replace-with-default-flow': '默认分支（兜底）',
  'replace-with-conditional-flow': '条件分支',
  'replace-with-timer-intermediate-catch': '定时中间事件（待接入）',
  'replace-with-timer-boundary': '定时边界事件（待接入）',
  'replace-with-non-interrupting-timer-boundary': '非中断定时边界事件（待接入）',
};

const PAD_TITLES: Record<string, string> = {
  'append.append-task': '追加任务',
  'append.gateway': '追加网关',
  'append.end-event': '追加结束事件',
  'append.text-annotation': '追加文本注记',
  replace: '改变元素类型',
  connect: '连线到其他元素',
  delete: '删除',
};

interface MenuEntry {
  label?: string;
  title?: string;
  action?: unknown;
}

type MenuEntries = Record<string, MenuEntry>;

/**
 * 裁剪器：ContextPad 删「追加中间/边界事件」（none-throwing 属排除清单，定时中间事件
 * 由后续批次以专用入口补回），扳手弹窗按 2.1 白名单裁剪；中英文文案就地覆盖，
 * BpmnCanvas 的 contextPad.open 钩子保留兜底。
 */
class FlowPadTrimmer {
  static $inject = ['contextPad', 'popupMenu'];

  constructor(
    contextPad: { registerProvider: (priority: number, provider: unknown) => void },
    popupMenu: { registerProvider: (id: string, priority: number, provider: unknown) => void },
  ) {
    // 默认 provider 注册在 1000，此处 500 在其后执行，收到的是累积后的条目
    contextPad.registerProvider(500, this);
    popupMenu.registerProvider('bpmn-replace', 500, this);
  }

  getContextPadEntries(): (entries: MenuEntries) => MenuEntries {
    return (entries) => {
      delete entries['append.intermediate-event'];
      for (const [id, entry] of Object.entries(entries)) {
        const title = PAD_TITLES[id];
        if (title) {
          entry.title = title;
        }
      }
      return entries;
    };
  }

  getPopupMenuEntries(target: { businessObject?: { $type?: string } }): (entries: MenuEntries) => MenuEntries {
    const allowed = REPLACE_WHITELIST[target?.businessObject?.$type ?? ''];
    return (entries) => {
      if (!allowed) {
        return entries;
      }
      const next: MenuEntries = {};
      for (const [id, entry] of Object.entries(entries)) {
        if (!allowed.has(id)) {
          continue;
        }
        const label = REPLACE_LABELS[id];
        next[id] = label ? { ...entry, label } : entry;
      }
      return next;
    };
  }

  getPopupMenuHeaderEntries(): (entries: MenuEntries) => MenuEntries {
    return (entries) => {
      const next: MenuEntries = {};
      for (const [id, entry] of Object.entries(entries)) {
        if (id === 'toggle-parallel-mi') {
          next[id] = { ...entry, title: '并行多实例（每人一票，全部办完通过）' };
        } else if (id === 'toggle-sequential-mi') {
          next[id] = { ...entry, title: '串行多实例（依次办理）' };
        }
        // toggle-loop（Loop 循环）属 2.1 排除清单，丢弃
      }
      return next;
    };
  }
}

/* ---------------------------------- 新建事件默认值 ---------------------------------- */

interface ShapeCreateEvent {
  context?: {
    shape?: { businessObject?: Record<string, unknown> };
  };
}

/** 新建开始/结束事件的默认节点标识与名称 */
const EVENT_DEFAULTS: Record<string, { id: string; name: string }> = {
  'bpmn:StartEvent': { id: 'start', name: '开始' },
  'bpmn:EndEvent': { id: 'end', name: '结束' },
};

/** 现存元素 id 集合（含流程图内全部元素） */
function collectUsedIds(elementRegistry: {
  getAll: () => Array<{ businessObject?: { id?: unknown } }>;
}): Set<string> {
  const used = new Set<string>();
  for (const el of elementRegistry.getAll()) {
    const id = el.businessObject?.id;
    if (typeof id === 'string') {
      used.add(id);
    }
  }
  return used;
}

/**
 * 新建开始/结束事件默认值：节点标识 start/end（被占用时自动追加序号）、名称「开始/结束」。
 * 仅覆盖引擎自动生成的 id（Event_xxx），用户已自定义或模板导入的 id 不动。
 * shape.append 内部会嵌套执行 shape.create，故只挂 shape.create 即可覆盖全部新建入口。
 */
class FlowEventDefaults extends CommandInterceptor {
  static $inject = ['eventBus', 'elementRegistry'];

  constructor(
    eventBus: unknown,
    elementRegistry: {
      getAll: () => Array<{ businessObject?: { id?: unknown } }>;
    },
  ) {
    super(eventBus as never);
    this.on('shape.create.preExecute', (event: ShapeCreateEvent) => {
      const bo = event.context?.shape?.businessObject;
      const defaults = bo?.$type ? EVENT_DEFAULTS[bo.$type as string] : undefined;
      if (!bo || !defaults) return;
      if (!bo.name) {
        bo.name = defaults.name;
      }
      const id = typeof bo.id === 'string' ? bo.id : '';
      if (id.startsWith('Event_')) {
        const used = collectUsedIds(elementRegistry);
        let next = defaults.id;
        for (let n = 2; used.has(next); n++) {
          next = `${defaults.id}${n}`;
        }
        bo.id = next;
      }
    });
  }
}

/* ---------------------------------- 新连线默认条件 ---------------------------------- */
interface ConnectionCreateEvent {
  context?: {
    source?: { businessObject?: { $type?: string } };
    connection?: { businessObject?: Record<string, unknown> };
  };
}

/**
 * 新连线默认条件：来源为排他网关时默认写通过条件 ${approved}
 * （等价于流转类型「正常提交」，用户仍可在连线面板改成「驳回」）。
 * 在 preExecute 阶段写入 businessObject：随建线命令一体撤销/重做，且重放不覆盖用户改动。
 */
class FlowLineDefaults extends CommandInterceptor {
  static $inject = ['eventBus', 'bpmnFactory'];

  constructor(
    eventBus: unknown,
    bpmnFactory: { create: (type: string, props?: Record<string, unknown>) => Record<string, unknown> },
  ) {
    super(eventBus as never);
    this.on('connection.create.preExecute', (event: ConnectionCreateEvent) => {
      const bo = event.context?.connection?.businessObject;
      if (!bo) return;
      if (event.context?.source?.businessObject?.$type === 'bpmn:ExclusiveGateway') {
        bo.conditionExpression = bpmnFactory.create('bpmn:FormalExpression', {
          body: FLOW_CONDITIONS.normal,
        });
      }
    });
  }
}

/* ---------------------------------- 驳回线渲染 ---------------------------------- */

const REJECT_PRIORITY = 1500;

/**
 * 驳回线：红色虚线。连线名称不在此渲染——bpmn-js 已为具名连线生成外部标签
 * （LabelBehavior），自定义文本会和标准标签重复显示。
 */
class RejectFlowRenderer extends BaseRenderer {
  constructor(eventBus: unknown) {
    super(eventBus as never, REJECT_PRIORITY);
  }

  override canRender(element: Connection): boolean {
    return element.type === 'bpmn:SequenceFlow' && element.waypoints.length > 0
      && detectFlowType(
        element.businessObject?.name,
        element.businessObject?.conditionExpression?.body,
      ) === 'reject';
  }

  override drawConnection(visuals: SVGElement, connection: Connection): SVGElement {
    const line = createLine(connection.waypoints, {
      stroke: REJECT_COLOR,
      strokeWidth: 2,
      strokeDasharray: '6, 4',
    });
    append(visuals, line);
    return line;
  }
}

export const flowCustomModules = {
  __init__: [
    'flowPadTrimmer',
    'flowPaletteProvider',
    'flowConnectRules',
    'flowEventDefaults',
    'flowLineDefaults',
    'rejectFlowRenderer',
  ],
  // 覆盖 bpmn-js 内置元素工厂：新建 bpmn:Task 统一映射为 bpmn:UserTask
  elementFactory: ['type', FlowElementFactory],
  flowConnectRules: ['type', FlowConnectRules],
  flowPadTrimmer: ['type', FlowPadTrimmer],
  flowPaletteProvider: ['type', FlowPaletteProvider],
  flowEventDefaults: ['type', FlowEventDefaults],
  flowLineDefaults: ['type', FlowLineDefaults],
  rejectFlowRenderer: ['type', RejectFlowRenderer],
};
