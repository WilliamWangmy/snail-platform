/**
 * bpmn-js 自定义模块：元素栏裁剪（v1 六项）、ContextPad/ReplaceMenu 裁剪与中文化（2.1 白名单）、
 * 连线规则（仅 sequenceFlow、禁连开始事件）、驳回线红色虚线渲染。注册方式见 BpmnCanvas.vue 的 additionalModules。
 */
import BaseRenderer from 'diagram-js/lib/draw/BaseRenderer';
import type { Connection } from 'diagram-js/lib/model/Types';
import { createLine } from 'diagram-js/lib/util/RenderUtil';
import { append, create as svgCreate } from 'tiny-svg';

import { detectFlowType } from './flowType';

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
  'bpmn:Task': new Set(['replace-with-task', 'replace-with-user-task']),
  'bpmn:UserTask': new Set(['replace-with-task', 'replace-with-user-task']),
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
  'replace-with-task': '任务',
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

/* ---------------------------------- 驳回线渲染 ---------------------------------- */

const REJECT_PRIORITY = 1500;

/** 驳回线：红色虚线 + “驳回”标签；其余元素交给默认渲染器 */
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

    const name = connection.businessObject?.name;
    if (name) {
      const label = svgCreate('text', {
        fill: REJECT_COLOR,
        fontSize: 12,
        textAnchor: 'middle',
        x: connection.waypoints[0]?.x ?? 0,
        y: (connection.waypoints[0]?.y ?? 0) - 6,
      });
      label.textContent = name;
      append(visuals, label);
    }
    return line;
  }
}

export const flowCustomModules = {
  __init__: ['flowPadTrimmer', 'flowPaletteProvider', 'flowConnectRules', 'rejectFlowRenderer'],
  flowConnectRules: ['type', FlowConnectRules],
  flowPadTrimmer: ['type', FlowPadTrimmer],
  flowPaletteProvider: ['type', FlowPaletteProvider],
  rejectFlowRenderer: ['type', RejectFlowRenderer],
};
