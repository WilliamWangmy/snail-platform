/**
 * bpmn-js 自定义模块：元素栏裁剪（v1 六项）、连线规则（仅 sequenceFlow、禁连开始事件）、
 * 驳回线红色虚线渲染。注册方式见 BpmnCanvas.vue 的 additionalModules。
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
  __init__: ['flowPaletteProvider', 'flowConnectRules', 'rejectFlowRenderer'],
  flowConnectRules: ['type', FlowConnectRules],
  flowPaletteProvider: ['type', FlowPaletteProvider],
  rejectFlowRenderer: ['type', RejectFlowRenderer],
};
