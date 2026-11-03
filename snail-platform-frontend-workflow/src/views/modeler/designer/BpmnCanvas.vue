<script setup lang="ts">
/**
 * bpmn-js 画布封装：编辑（Modeler）/ 只读（NavigatedViewer）双模式。
 * 对外暴露外部元素栏所需的工具切换、建元素、缩放能力；缩放与工具状态经事件回传。
 * 监听容器尺寸变化（全屏切换/窗口缩放）自动 canvas.resized()。
 */
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import Modeler from 'bpmn-js/lib/Modeler';
import NavigatedViewer from 'bpmn-js/lib/NavigatedViewer';

import { flowCustomModules } from './customModules';
import { FLOWABLE_MODDLE_DESCRIPTOR } from './flowableExtension';

const props = defineProps<{
  /** BPMN XML（首帧或外部重载时导入） */
  xml: string;
  /** true 时为只读查看器（pan/zoom 可用，编辑关闭） */
  readonly?: boolean;
}>();

const emit = defineEmits<{
  /** 首次导入成功（用于回显完成信号） */
  imported: [];
  /** 导入失败（message） */
  importError: [message: string];
  /** 选中元素变化（businessObject.$type + id），null 表示清空 */
  selectionChanged: [element: { id: string; $type: string } | null];
  /** 本地编辑发生（脏标记来源） */
  changed: [];
  /** 画布缩放百分比（100 = 原始比例） */
  zoomChanged: [percent: number];
  /** 当前工具：pointer / hand / lasso / space / connect / create */
  toolChanged: [tool: string];
}>();

type ToolName = 'pointer' | 'hand' | 'lasso' | 'space' | 'connect';

interface CanvasService {
  zoom(): number;
  zoom(target: number | string): number | string;
  viewbox(): { scale: number };
}
interface ToggleTool {
  isActive: () => boolean;
  toggle: () => void;
}
interface HandService extends ToggleTool {
  activateHand: (event: Event) => void;
}
interface SelectionToolService extends ToggleTool {
  activateSelection: (event: Event) => void;
}
interface ConnectService extends ToggleTool {
  start: (event: Event) => void;
}
interface CreateService {
  start: (event: Event, shape: unknown) => void;
}
interface ElementFactoryService {
  createShape: (attrs: { type: string }) => unknown;
}
interface EventBusService {
  on: (events: string | string[], handler: (event: never) => void) => void;
}

/* 深色画布：形状/描边/外置标签由渲染器配置控制，其余组件观感由 --bio-* 变量控制 */
const LABEL_FONT = '"PingFang SC", "Microsoft YaHei", sans-serif';
const CANVAS_STYLE = {
  // renderer/text 的配置按模块名注入，放顶层不生效
  bpmnRenderer: {
    defaultFillColor: '#0c222c',
    defaultStrokeColor: '#6f8d8e',
    defaultLabelColor: '#d8e8e5',
  },
  textRenderer: {
    defaultStyle: { fontFamily: LABEL_FONT, fontSize: 12 },
    externalStyle: { fontFamily: LABEL_FONT, fontSize: 12 },
  },
};

/* ContextPad（点选节点的浮动工具盘）悬浮提示中文化：按图标类名映射，条目集合仍是 bpmn-js 默认全量 */
const CONTEXT_PAD_TITLES: Record<string, string> = {
  'bpmn-icon-start-event-none': '追加开始事件',
  'bpmn-icon-end-event-none': '追加结束事件',
  'bpmn-icon-intermediate-event-none': '追加中间/边界事件',
  'bpmn-icon-gateway-none': '追加网关',
  'bpmn-icon-task': '追加任务',
  'bpmn-icon-text-annotation': '追加文本注记',
  'bpmn-icon-screw-wrench': '改变元素类型',
  'bpmn-icon-trash': '删除',
  'bpmn-icon-connection-multi': '连线到其他元素',
};

function localizeContextPad(event: { current?: { html?: HTMLElement } }): void {
  const html = event.current?.html;
  if (!html) return;
  for (const el of Array.from(html.querySelectorAll('.entry'))) {
    const icon = Array.from(el.classList).find((c) => CONTEXT_PAD_TITLES[c]);
    if (icon) {
      el.setAttribute('title', CONTEXT_PAD_TITLES[icon]);
    }
  }
}

const stageRef = ref<HTMLDivElement>();

let diagram: Modeler | NavigatedViewer | null = null;
let resizeObserver: ResizeObserver | null = null;
let imported = false;

function getService<T>(name: string): T | null {
  return diagram ? (diagram.get(name) as T) : null;
}

const TOOL_NAMES: Record<string, string> = {
  hand: 'hand',
  lasso: 'lasso',
  space: 'space',
  'global-connect': 'connect',
};

function emitZoom(): void {
  const canvas = getService<CanvasService>('canvas');
  if (!canvas) return;
  emit('zoomChanged', Math.round(canvas.viewbox().scale * 100));
}

async function render(): Promise<void> {
  if (!stageRef.value || !props.xml) return;

  diagram?.destroy();
  const options = {
    container: stageRef.value,
    moddleExtensions: { flowable: FLOWABLE_MODDLE_DESCRIPTOR },
    ...CANVAS_STYLE,
  };
  diagram = props.readonly
    ? new NavigatedViewer(options)
    : new Modeler({ ...options, additionalModules: [flowCustomModules] });

  diagram.on('selection.changed', (event: { newSelection?: Array<{ id: string; businessObject: { $type: string } }> }) => {
    const first = event.newSelection?.[0];
    emit('selectionChanged', first ? { id: first.id, $type: first.businessObject.$type } : null);
  });

  const eventBus = getService<EventBusService>('eventBus');

  if (!props.readonly) {
    diagram.on('commandStack.changed', () => {
      if (imported) {
        emit('changed');
      }
    });

    diagram.on('contextPad.open', localizeContextPad);

    eventBus?.on('tool-manager.update', (event: { tool?: string | null }) => {
      emit('toolChanged', event.tool ? (TOOL_NAMES[event.tool] ?? 'pointer') : 'pointer');
    });
    eventBus?.on('create.end', () => emit('toolChanged', 'pointer'));
    eventBus?.on('create.cancel', () => emit('toolChanged', 'pointer'));
  }

  eventBus?.on('canvas.viewbox.changed', (event: { viewbox?: { scale?: number } }) => {
    emit('zoomChanged', Math.round((event.viewbox?.scale ?? 1) * 100));
  });

  try {
    await diagram.importXML(props.xml);
    imported = true;
    currentXml = props.xml;
    getService<CanvasService>('canvas')?.zoom('fit-viewport');
    emitZoom();
    emit('imported');
  } catch (error) {
    emit('importError', error instanceof Error ? error.message : '流程图导入失败');
  }
}

let currentXml = '';

onMounted(() => {
  void render();

  // 尺寸变化（全屏切换 / 窗口变化）→ 画布自适应
  resizeObserver = new ResizeObserver(() => {
    if (diagram && stageRef.value && stageRef.value.clientWidth > 0) {
      (diagram.get('canvas') as { resized: () => void }).resized();
    }
  });
  if (stageRef.value) {
    resizeObserver.observe(stageRef.value);
  }
});

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  diagram?.destroy();
  diagram = null;
});

watch(() => props.xml, (next) => {
  if (next && next !== currentXml) {
    void render();
  }
});

watch(() => props.readonly, () => {
  void render();
});

/* ---------------------------------- 外部元素栏 ---------------------------------- */

function deactivateTools(): void {
  for (const name of ['handTool', 'lassoTool', 'spaceTool', 'globalConnect']) {
    const tool = getService<ToggleTool>(name);
    if (tool?.isActive()) {
      tool.toggle();
    }
  }
}

/** 工具切换：pointer 回到选择；再次点击当前工具则取消 */
function setTool(tool: ToolName, event: Event): void {
  if (props.readonly || !diagram) return;

  if (tool === 'pointer') {
    deactivateTools();
    emit('toolChanged', 'pointer');
    return;
  }

  const hand = getService<HandService>('handTool');
  const lasso = getService<SelectionToolService>('lassoTool');
  const space = getService<SelectionToolService>('spaceTool');
  const connect = getService<ConnectService>('globalConnect');
  const current = tool === 'hand' ? hand : tool === 'lasso' ? lasso : tool === 'space' ? space : connect;

  if (current?.isActive()) {
    current.toggle();
    emit('toolChanged', 'pointer');
    return;
  }

  deactivateTools();
  if (tool === 'hand') hand?.activateHand(event);
  if (tool === 'lasso') lasso?.activateSelection(event);
  if (tool === 'space') space?.activateSelection(event);
  if (tool === 'connect') connect?.start(event);
  emit('toolChanged', tool);
}

/** 从外部面板创建元素（bpmn-js create 工具） */
function startCreate(type: string, event: Event): void {
  if (props.readonly || !diagram) return;
  const create = getService<CreateService>('create');
  const elementFactory = getService<ElementFactoryService>('elementFactory');
  if (!create || !elementFactory) return;
  deactivateTools();
  create.start(event, elementFactory.createShape({ type }));
  emit('toolChanged', 'create');
}

function zoomBy(step: number): void {
  const canvas = getService<CanvasService>('canvas');
  if (!canvas) return;
  const next = Math.min(4, Math.max(0.2, canvas.zoom() + step / 100));
  canvas.zoom(next);
}

function fitCanvas(): void {
  getService<CanvasService>('canvas')?.zoom('fit-viewport');
  emitZoom();
}

/** 导出 XML（保存/发布前调用） */
async function exportXml(): Promise<string> {
  if (!diagram || props.readonly) {
    throw new Error('只读模式下不可导出');
  }
  const result = await (diagram as Modeler).saveXML({ format: true });
  if (!result.xml) {
    throw new Error('流程图导出失败');
  }
  return result.xml;
}

defineExpose({ exportXml, getModeler: () => diagram, setTool, startCreate, zoomBy, fitCanvas });
</script>

<template>
  <div
    ref="stageRef"
    class="bpmn-canvas"
  ></div>
</template>

<style scoped lang="scss">
.bpmn-canvas {
  width: 100%;
  height: 100%;
  overflow: hidden;
  background: transparent;

  // 元素栏由设计器外壳统一渲染，隐藏 bpmn-js 自带调色板
  :deep(.djs-palette) {
    display: none;
  }
}
</style>
