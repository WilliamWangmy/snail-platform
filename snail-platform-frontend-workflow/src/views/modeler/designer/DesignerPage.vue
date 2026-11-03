<script setup lang="ts">
/**
 * 设计器页：顶栏（返回/模型信息/校验/保存/发布/全屏）+ 外部元素栏 + bpmn-js 画布 + 属性面板。
 * 数据流：draft 读取 → 编辑（本地实时校验）→ 保存/发布；离开脏守卫；Fullscreen API 全屏。
 */
import { computed, onBeforeUnmount, ref, shallowRef } from 'vue';
import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';

import {
  getModel,
  getModelDraft,
  publishModel,
  saveModelDraft,
  type WorkflowModelVO,
} from '@/api/workflow/model';
import AppIcon from '@/components/AppIcon.vue';
import { useAuthStore } from '@/stores/auth';

import BpmnCanvas from './BpmnCanvas.vue';
import { validateModel, validateXmlSize, type ValidationIssue } from './bpmnValidator';
import { blankXml } from './flowType';
import type { FlowElementLike } from './flowableExtension';
import EventPanel from './panel/EventPanel.vue';
import GatewayPanel from './panel/GatewayPanel.vue';
import ProcessPanel from './panel/ProcessPanel.vue';
import SequenceFlowPanel from './panel/SequenceFlowPanel.vue';
import UserTaskPanel from './panel/UserTaskPanel.vue';

type Diagram = { get: (service: string) => unknown };

interface ElementRegistryLike {
  get: (id: string) => FlowElementLike;
  getAll: () => FlowElementLike[];
}
interface CanvasLike {
  getRootElements: () => FlowElementLike[];
  scrollToElement: (el: unknown) => void;
}
interface SelectionLike {
  select: (els: unknown[]) => void;
}

function getSvc<T>(diagram: Diagram | null, service: string): T | null {
  return diagram ? (diagram.get(service) as T) : null;
}

interface BpmnServices {
  modeling: {
    updateProperties: (el: unknown, props: Record<string, unknown>) => void;
    updateModdleProperties: (el: unknown, moddleEl: unknown, props: Record<string, unknown>) => void;
  };
  bpmnFactory: { create: (type: string, props?: Record<string, unknown>) => Record<string, unknown> };
}

const TYPE_META: Record<string, { label: string; title: string; icon: string }> = {
  'bpmn:Process': { label: 'PROCESS', title: '流程', icon: 'workflow' },
  'bpmn:StartEvent': { label: 'START EVENT', title: '开始事件', icon: 'circle' },
  'bpmn:EndEvent': { label: 'END EVENT', title: '结束事件', icon: 'target' },
  'bpmn:UserTask': { label: 'USER TASK', title: '用户任务', icon: 'user' },
  'bpmn:ExclusiveGateway': { label: 'EXCLUSIVE GATEWAY', title: '排他网关', icon: 'diamond' },
  'bpmn:ParallelGateway': { label: 'PARALLEL GATEWAY', title: '并行网关', icon: 'git-branch' },
  'bpmn:SequenceFlow': { label: 'SEQUENCE FLOW', title: '连线', icon: 'connect' },
};

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

const modelId = Number(route.params.modelId);
const model = ref<WorkflowModelVO>();
const xml = ref('');
const loading = ref(true);
const dirty = ref(false);
// 画布命令版本号：bpmn 业务对象不是响应式的，面板据此重算名称等字段
const elementRevision = ref(0);
const saving = ref(false);
const publishing = ref(false);

const selectedId = ref<string | null>(null);
const selectedType = ref<string | null>(null);
// bpmn-js 服务与模型对象不能被 Vue 深层代理，否则事件重绘时触发 Proxy 不变量错误
const services = shallowRef<BpmnServices>();

const issues = ref<ValidationIssue[]>([]);
const validated = ref(false);
const validationVisible = ref(false);
const publishVisible = ref(false);
const inspectorTab = ref<'properties' | 'checks'>('properties');

const activeTool = ref('pointer');
const createType = ref('');
const zoom = ref(100);

const canvasRef = ref<InstanceType<typeof BpmnCanvas>>();
const canvasReady = ref(false);
const designerRoot = ref<HTMLElement>();

const errorCount = computed(() => issues.value.filter((issue) => issue.level === 'error').length);
const warnCount = computed(() => issues.value.filter((issue) => issue.level === 'warn').length);

const statusLabel = computed(() => {
  const value = model.value;
  if (!value) return '加载中';
  if (value.status === 2) return '已停用';
  if (value.deployedVersion) return `已发布 v${value.deployedVersion}`;
  return value.status === 1 ? '已启用' : '草稿';
});

const statusPillClass = computed(() => {
  const value = model.value;
  if (!value) return 'pill--slate';
  if (value.status === 2) return 'pill--amber';
  if (value.deployedVersion) return 'pill--teal';
  return value.status === 1 ? 'pill--blue' : 'pill--slate';
});

const nextVersion = computed(() => (model.value?.deployedVersion ?? 0) + 1);

const currentVersionText = computed(() => {
  const value = model.value;
  if (!value?.deployedVersion) return '暂无发布版本';
  const time = value.publishTime ? value.publishTime.slice(0, 10) : '';
  return `v${value.deployedVersion}${time ? ` · ${time}` : ''}`;
});

/* ---------------------------------- 加载 ---------------------------------- */

async function load(): Promise<void> {
  loading.value = true;
  try {
    const [modelVo, draft] = await Promise.all([getModel(modelId), getModelDraft(modelId)]);
    model.value = modelVo;
    xml.value = draft.xml?.trim() || blankXml(draft.modelKey, draft.name);
  } finally {
    loading.value = false;
  }
}

void load();

function onImported(): void {
  canvasReady.value = true;
  const diagram = canvasRef.value?.getModeler() as Diagram | null;
  if (!diagram) return;
  const modeling = getSvc<BpmnServices['modeling']>(diagram, 'modeling');
  const bpmnFactory = getSvc<BpmnServices['bpmnFactory']>(diagram, 'bpmnFactory');
  if (modeling && bpmnFactory) {
    services.value = { modeling, bpmnFactory };
  }
  refreshValidation();
}

function onImportError(message: string): void {
  ElMessage.error(message);
}

/* ---------------------------------- 选中与面板 ---------------------------------- */

const selectedElement = computed<FlowElementLike | null>(() => {
  const registry = getSvc<ElementRegistryLike>(canvasRef.value?.getModeler() as Diagram | null, 'elementRegistry');
  if (!registry || !selectedId.value) return processElement.value;
  const el = registry.get(selectedId.value);
  if (el && selectedType.value?.startsWith('bpmn:')) return el;
  return processElement.value;
});

const processElement = computed<FlowElementLike | null>(() => {
  // canvasRef 先于画布导入完成赋值，这里依赖导入完成信号重新取根元素
  if (!canvasReady.value) return null;
  const canvas = getSvc<CanvasLike>(canvasRef.value?.getModeler() as Diagram | null, 'canvas');
  if (!canvas) return null;
  return canvas.getRootElements().find((el) => el.businessObject.$type === 'bpmn:Process') ?? null;
});

const inspectorMeta = computed(() => {
  void elementRevision.value;
  const element = selectedElement.value;
  const type = String(element?.businessObject.$type ?? '');
  const meta = TYPE_META[type] ?? { label: 'ELEMENT', title: '元素', icon: 'sliders' };
  const name = String(element?.businessObject.name ?? '').trim();
  return {
    title: name || meta.title,
    sub: `${meta.label} · ${String(element?.businessObject.id ?? '—')}`,
    icon: meta.icon,
  };
});

function onSelectionChanged(sel: { id: string; $type: string } | null): void {
  selectedId.value = sel?.id ?? null;
  selectedType.value = sel?.$type ?? null;
}

/* ---------------------------------- 校验 ---------------------------------- */

function runValidation(): ValidationIssue[] {
  const diagram = canvasRef.value?.getModeler() as Diagram | null;
  return diagram ? validateModel(diagram as unknown as Parameters<typeof validateModel>[0]) : [];
}

function refreshValidation(openDialog = false): void {
  issues.value = runValidation();
  validated.value = true;
  if (openDialog) {
    validationVisible.value = true;
  }
}

let validationTimer: number | undefined;

function onChanged(): void {
  dirty.value = true;
  elementRevision.value += 1;
  window.clearTimeout(validationTimer);
  validationTimer = window.setTimeout(() => {
    issues.value = runValidation();
    validated.value = true;
  }, 500);
}

onBeforeUnmount(() => window.clearTimeout(validationTimer));

function locateIssue(issue: ValidationIssue): void {
  validationVisible.value = false;
  if (!issue.target) return;
  const diagram = canvasRef.value?.getModeler() as Diagram | null;
  if (!diagram) return;
  const registry = getSvc<ElementRegistryLike>(diagram, 'elementRegistry');
  const canvas = getSvc<CanvasLike>(diagram, 'canvas');
  const selection = getSvc<SelectionLike>(diagram, 'selection');
  if (!registry || !canvas || !selection) return;
  const el = registry.get(issue.target);
  if (!el) return;
  selection.select([el]);
  canvas.scrollToElement(el);
}

/* ---------------------------------- 保存 / 发布 ---------------------------------- */

async function onSave(): Promise<void> {
  if (saving.value) return;
  saving.value = true;
  try {
    refreshValidation();
    if (errorCount.value > 0) {
      validationVisible.value = true;
      ElMessage.error('本地校验未通过，请先修复错误');
      return;
    }
    const exported = await canvasRef.value?.exportXml();
    if (!exported) return;
    const sizeIssues = validateXmlSize(exported);
    if (sizeIssues.length > 0) {
      issues.value = sizeIssues;
      validationVisible.value = true;
      return;
    }
    await saveModelDraft(modelId, exported);
    dirty.value = false;
    ElMessage.success('草稿已保存');
  } finally {
    saving.value = false;
  }
}

function openValidation(): void {
  refreshValidation(true);
}

function openPublish(): void {
  refreshValidation();
  if (errorCount.value > 0) {
    validationVisible.value = true;
    return;
  }
  publishVisible.value = true;
}

function continuePublish(): void {
  if (errorCount.value > 0) return;
  validationVisible.value = false;
  window.setTimeout(() => {
    publishVisible.value = true;
  }, 180);
}

async function confirmPublish(): Promise<void> {
  if (publishing.value) return;
  publishing.value = true;
  try {
    const exported = await canvasRef.value?.exportXml();
    if (!exported) return;
    const sizeIssues = validateXmlSize(exported);
    if (sizeIssues.length > 0) {
      issues.value = [...issues.value, ...sizeIssues];
      publishVisible.value = false;
      validationVisible.value = true;
      return;
    }
    // 草稿与发布同源：先存草稿再发布，保证发布内容与画布一致
    await saveModelDraft(modelId, exported);
    const definition = await publishModel(modelId);
    dirty.value = false;
    model.value = await getModel(modelId);
    publishVisible.value = false;
    ElMessage.success(`发布成功，已生成 v${definition.version}`);
  } finally {
    publishing.value = false;
  }
}

/* ---------------------------------- 工具与缩放 ---------------------------------- */

function pickTool(tool: 'pointer' | 'hand' | 'connect', event: MouseEvent): void {
  canvasRef.value?.setTool(tool, event);
}

function createElement(type: string, event: MouseEvent): void {
  createType.value = type;
  canvasRef.value?.startCreate(type, event);
}

function onToolChanged(tool: string): void {
  activeTool.value = tool;
  if (tool !== 'create') {
    createType.value = '';
  }
}

function zoomBy(step: number): void {
  canvasRef.value?.zoomBy(step);
}

function fitCanvas(): void {
  canvasRef.value?.fitCanvas();
}

/* ---------------------------------- 全屏（Fullscreen API + 伪全屏降级） ---------------------------------- */

const fullscreen = ref(false);
const pseudoFullscreen = ref(false);

const fullscreenSupported = computed(() => {
  if (typeof document === 'undefined') return false;
  return Boolean(document.fullscreenEnabled);
});

async function toggleFullscreen(): Promise<void> {
  if (!fullscreenSupported.value) {
    pseudoFullscreen.value = !pseudoFullscreen.value;
    return;
  }
  if (document.fullscreenElement) {
    await document.exitFullscreen();
  } else if (designerRoot.value) {
    await designerRoot.value.requestFullscreen();
  }
}

// Esc 系统退出也要同步按钮状态
if (typeof document !== 'undefined') {
  document.addEventListener('fullscreenchange', () => {
    fullscreen.value = Boolean(document.fullscreenElement);
  });
}

const inFullscreen = computed(() => fullscreen.value || pseudoFullscreen.value);

/* ---------------------------------- 离开脏守卫 ---------------------------------- */

onBeforeRouteLeave(async () => {
  if (!dirty.value) return true;
  try {
    await ElMessageBox.confirm('当前流程图有未保存的修改，离开将丢失。', '离开确认', {
      type: 'warning',
      confirmButtonText: '直接离开',
      cancelButtonText: '取消',
      distinguishCancelAndClose: true,
    });
    return true;
  } catch (action) {
    if (action === 'cancel') {
      await onSave();
      return true;
    }
    return false;
  }
});

function goBack(): void {
  void router.push('/modeler');
}
</script>

<template>
  <div
    ref="designerRoot"
    v-loading="loading"
    class="designer-shell"
    :class="{ 'pseudo-fullscreen': pseudoFullscreen }"
  >
    <div class="designer-bar">
      <button
        class="designer-back"
        type="button"
        @click="goBack"
      >
        <AppIcon name="arrow-left" />返回列表
      </button>

      <div class="designer-title">
        <h1>{{ model?.name || '流程设计器' }}</h1>
        <span class="mono">{{ model?.modelKey }}</span>
        <span
          class="pill"
          :class="statusPillClass"
        >{{ statusLabel }}</span>
        <span
          v-if="dirty"
          class="unsaved"
        >未保存</span>
      </div>

      <div class="designer-actions">
        <el-tooltip
          content="本地校验结果"
          placement="bottom"
        >
          <el-button
            size="small"
            class="badge-btn"
            @click="openValidation"
          >
            <AppIcon name="shield" /><span class="btn-label">校验结果</span>
            <span
              v-if="issues.length"
              class="badge"
            >{{ issues.length }}</span>
          </el-button>
        </el-tooltip>
        <el-tooltip
          content="保存草稿"
          placement="bottom"
        >
          <el-button
            size="small"
            :loading="saving"
            @click="onSave"
          >
            <AppIcon name="save" /><span class="btn-label">保存草稿</span>
          </el-button>
        </el-tooltip>
        <el-tooltip
          content="发布流程"
          placement="bottom"
        >
          <el-button
            type="primary"
            size="small"
            :loading="publishing"
            @click="openPublish"
          >
            <AppIcon name="send" /><span class="btn-label">发布</span>
          </el-button>
        </el-tooltip>
        <el-tooltip
          :content="inFullscreen ? '退出全屏' : '全屏'"
          placement="bottom"
        >
          <button
            class="icon-btn"
            type="button"
            :aria-label="inFullscreen ? '退出全屏' : '全屏'"
            @click="toggleFullscreen"
          >
            <AppIcon :name="inFullscreen ? 'minimize' : 'maximize'" />
          </button>
        </el-tooltip>
      </div>
    </div>

    <div class="designer-body">
      <div
        class="palette"
        role="toolbar"
        aria-label="流程元素工具栏"
      >
        <el-tooltip
          content="平移画布"
          placement="right"
        >
          <el-button
            text
            class="palette-btn"
            :class="{ active: activeTool === 'hand' }"
            aria-label="平移画布"
            :disabled="loading"
            @click="(event: MouseEvent) => pickTool('hand', event)"
          >
            <AppIcon name="hand" />
          </el-button>
        </el-tooltip>
        <el-tooltip
          content="选择元素"
          placement="right"
        >
          <el-button
            text
            class="palette-btn"
            :class="{ active: activeTool === 'pointer' }"
            aria-label="选择元素"
            :disabled="loading"
            @click="(event: MouseEvent) => pickTool('pointer', event)"
          >
            <AppIcon name="pointer" />
          </el-button>
        </el-tooltip>
        <el-tooltip
          content="连接元素"
          placement="right"
        >
          <el-button
            text
            class="palette-btn"
            :class="{ active: activeTool === 'connect' }"
            aria-label="连接元素"
            :disabled="loading"
            @click="(event: MouseEvent) => pickTool('connect', event)"
          >
            <AppIcon name="connect" />
          </el-button>
        </el-tooltip>

        <div class="palette-sep"></div>

        <el-tooltip
          content="开始事件"
          placement="right"
        >
          <el-button
            text
            class="palette-btn"
            :class="{ active: activeTool === 'create' && createType === 'bpmn:StartEvent' }"
            aria-label="开始事件"
            :disabled="loading"
            @click="(event: MouseEvent) => createElement('bpmn:StartEvent', event)"
          >
            <AppIcon name="circle" />
          </el-button>
        </el-tooltip>
        <el-tooltip
          content="用户任务"
          placement="right"
        >
          <el-button
            text
            class="palette-btn"
            :class="{ active: activeTool === 'create' && createType === 'bpmn:UserTask' }"
            aria-label="用户任务"
            :disabled="loading"
            @click="(event: MouseEvent) => createElement('bpmn:UserTask', event)"
          >
            <AppIcon name="square" />
          </el-button>
        </el-tooltip>
        <el-tooltip
          content="排他网关"
          placement="right"
        >
          <el-button
            text
            class="palette-btn"
            :class="{ active: activeTool === 'create' && createType === 'bpmn:ExclusiveGateway' }"
            aria-label="排他网关"
            :disabled="loading"
            @click="(event: MouseEvent) => createElement('bpmn:ExclusiveGateway', event)"
          >
            <AppIcon name="diamond" />
          </el-button>
        </el-tooltip>
        <el-tooltip
          content="并行网关"
          placement="right"
        >
          <el-button
            text
            class="palette-btn"
            :class="{ active: activeTool === 'create' && createType === 'bpmn:ParallelGateway' }"
            aria-label="并行网关"
            :disabled="loading"
            @click="(event: MouseEvent) => createElement('bpmn:ParallelGateway', event)"
          >
            <AppIcon name="git-branch" />
          </el-button>
        </el-tooltip>
        <el-tooltip
          content="结束事件"
          placement="right"
        >
          <el-button
            text
            class="palette-btn"
            :class="{ active: activeTool === 'create' && createType === 'bpmn:EndEvent' }"
            aria-label="结束事件"
            :disabled="loading"
            @click="(event: MouseEvent) => createElement('bpmn:EndEvent', event)"
          >
            <AppIcon name="target" />
          </el-button>
        </el-tooltip>
      </div>

      <div class="canvas-stage">
        <BpmnCanvas
          ref="canvasRef"
          :xml="xml"
          :readonly="false"
          @imported="onImported"
          @import-error="onImportError"
          @selection-changed="onSelectionChanged"
          @changed="onChanged"
          @zoom-changed="zoom = $event"
          @tool-changed="onToolChanged"
        />

        <div class="canvas-hint">
          <span>拖拽调整布局</span>
          <span><kbd>SPACE</kbd> 平移</span>
          <span><kbd>Ctrl</kbd> + 滚轮缩放</span>
        </div>

        <div class="zoom-bar">
          <el-tooltip
            content="缩小"
            placement="top"
          >
            <el-button
              text
              class="zoom-btn"
              aria-label="缩小"
              @click="zoomBy(-10)"
            >
              <AppIcon name="zoom-out" />
            </el-button>
          </el-tooltip>
          <span class="zoom-value">{{ zoom }}%</span>
          <el-tooltip
            content="放大"
            placement="top"
          >
            <el-button
              text
              class="zoom-btn"
              aria-label="放大"
              @click="zoomBy(10)"
            >
              <AppIcon name="zoom-in" />
            </el-button>
          </el-tooltip>
          <div class="palette-sep zoom-sep"></div>
          <el-tooltip
            content="适应画布"
            placement="top"
          >
            <el-button
              text
              class="zoom-btn"
              aria-label="适应画布"
              @click="fitCanvas"
            >
              <AppIcon name="fit" />
            </el-button>
          </el-tooltip>
        </div>
      </div>

      <aside
        class="inspector"
        aria-label="元素属性面板"
      >
        <div class="inspector-head">
          <span class="tile"><AppIcon :name="inspectorMeta.icon" /></span>
          <div class="inspector-head-copy">
            <h2>{{ inspectorMeta.title }}</h2>
            <p>{{ inspectorMeta.sub }}</p>
          </div>
        </div>

        <el-tabs
          v-model="inspectorTab"
          class="flow-tabs inspector-tabs"
        >
          <el-tab-pane
            name="properties"
            label="属性"
          >
            <div class="inspector-body">
              <ProcessPanel
                v-if="selectedElement?.businessObject.$type === 'bpmn:Process' && services && model"
                :key="`process-${model.modelKey}`"
                :element="selectedElement"
                :model-key="model.modelKey"
                :modeling="services.modeling"
                :revision="elementRevision"
              />
              <EventPanel
                v-else-if="(selectedElement?.businessObject.$type === 'bpmn:StartEvent' || selectedElement?.businessObject.$type === 'bpmn:EndEvent') && services"
                :key="`event-${selectedElement.businessObject.id}`"
                :element="selectedElement"
                :modeling="services.modeling"
                :revision="elementRevision"
              />
              <GatewayPanel
                v-else-if="(selectedElement?.businessObject.$type === 'bpmn:ExclusiveGateway' || selectedElement?.businessObject.$type === 'bpmn:ParallelGateway') && services"
                :key="`gateway-${selectedElement.businessObject.id}`"
                :element="selectedElement"
                :modeling="services.modeling"
                :revision="elementRevision"
              />
              <UserTaskPanel
                v-else-if="selectedElement?.businessObject.$type === 'bpmn:UserTask' && services"
                :key="`task-${selectedElement.businessObject.id}`"
                :element="selectedElement"
                :services="services"
                :revision="elementRevision"
              />
              <SequenceFlowPanel
                v-else-if="selectedElement?.businessObject.$type === 'bpmn:SequenceFlow' && services"
                :key="`flow-${selectedElement.businessObject.id}`"
                :element="selectedElement"
                :services="services"
                :revision="elementRevision"
              />
              <div
                v-else
                class="inspector-empty"
              >
                {{ selectedElement ? '该元素类型暂无可配置项' : '点击画布中的元素配置属性' }}
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane name="checks">
            <template #label>
              校验<span
                v-if="issues.length"
                class="tab-count"
              >{{ issues.length }}</span>
            </template>
            <div class="inspector-body">
              <el-alert
                v-if="!validated"
                class="flow-alert"
                type="info"
                :closable="false"
                show-icon
                title="尚未校验"
              >
                编辑后会自动校验；也可点击下方按钮手动执行。
              </el-alert>
              <el-alert
                v-else-if="errorCount > 0"
                class="flow-alert flow-alert--error"
                type="error"
                :closable="false"
                show-icon
                :title="`${errorCount} 个错误必须修复`"
              >
                保存与发布都会被阻断，点击条目可在画布中定位。
              </el-alert>
              <el-alert
                v-else
                class="flow-alert flow-alert--warning"
                type="warning"
                :closable="false"
                show-icon
                :title="warnCount > 0 ? `校验通过 · ${warnCount} 个警告` : '本地校验通过'"
              >
                {{ warnCount > 0 ? '警告不会阻断保存与发布，建议逐项修复。' : '可直接保存草稿或发布。' }}
              </el-alert>

              <div class="issue-list checks-list">
                <div
                  v-for="(issue, index) in issues"
                  :key="`${issue.target ?? 'model'}-${index}`"
                  class="issue"
                  :class="issue.level === 'error' ? 'error' : 'warn'"
                  @click="locateIssue(issue)"
                >
                  <AppIcon :name="issue.level === 'error' ? 'x-circle' : 'alert'" />
                  <div>
                    <b>{{ issue.message }}</b>
                    <span>{{ issue.target ? `点击定位到 ${issue.target}` : '结构性问题，请检查流程图整体' }}</span>
                  </div>
                </div>
                <div
                  v-if="validated && issues.length === 0"
                  class="issue ok"
                  @click="refreshValidation()"
                >
                  <AppIcon name="check-circle" />
                  <div>
                    <b>流程结构与表达式校验通过</b>
                    <span>白名单、表达式与配置码值均符合发布要求。</span>
                  </div>
                </div>
              </div>

              <el-button
                size="small"
                class="recheck-btn"
                @click="refreshValidation()"
              >
                <AppIcon name="refresh" />重新校验
              </el-button>
            </div>
          </el-tab-pane>
        </el-tabs>

        <div class="inspector-foot">
          修改先写入本地草稿，点击「保存草稿」后持久化。
        </div>
      </aside>
    </div>

    <el-dialog
      v-model="validationVisible"
      class="flow-dialog"
      width="560px"
      align-center
      :show-close="false"
    >
      <template #header>
        <div class="dialog-head">
          <div class="dialog-head-copy">
            <div class="dialog-kicker">
              LOCAL VALIDATION
            </div>
            <h2 class="dialog-title">
              本地校验结果
            </h2>
            <p class="dialog-desc">
              {{ errorCount }} 个错误 · {{ warnCount }} 个警告 · 点击条目可在画布中定位。
            </p>
          </div>
          <button
            class="icon-btn dialog-close"
            type="button"
            aria-label="关闭"
            @click="validationVisible = false"
          >
            <AppIcon name="x" />
          </button>
        </div>
      </template>

      <div class="issue-list">
        <div
          v-for="(issue, index) in issues"
          :key="`dialog-${issue.target ?? 'model'}-${index}`"
          class="issue"
          :class="issue.level === 'error' ? 'error' : 'warn'"
          @click="locateIssue(issue)"
        >
          <AppIcon :name="issue.level === 'error' ? 'x-circle' : 'alert'" />
          <div>
            <b>{{ issue.message }}</b>
            <span>{{ issue.target ? `点击定位到 ${issue.target}` : '结构性问题，请检查流程图整体' }}</span>
          </div>
        </div>
        <div
          v-if="issues.length === 0"
          class="issue ok"
        >
          <AppIcon name="check-circle" />
          <div>
            <b>流程结构与表达式校验通过</b>
            <span>白名单、表达式与配置码值均符合发布要求。</span>
          </div>
        </div>
      </div>

      <el-alert
        class="flow-alert validation-alert"
        :closable="false"
        show-icon
        :type="errorCount > 0 ? 'error' : 'info'"
        :title="errorCount > 0 ? '错误会阻断保存与发布' : '警告不会阻断保存与发布'"
      >
        {{ errorCount > 0 ? '逐条修复后重新校验，再继续发布。' : '可继续发布，建议发布前处理警告项。' }}
      </el-alert>

      <template #footer>
        <div class="dialog-foot">
          <el-button @click="refreshValidation()">
            <AppIcon name="refresh" />重新校验
          </el-button>
          <span class="foot-note">{{ errorCount > 0 ? '修复错误后才能继续发布' : '检查无误后可继续发布' }}</span>
          <el-button @click="validationVisible = false">
            关闭
          </el-button>
          <el-button
            type="primary"
            :disabled="errorCount > 0"
            @click="continuePublish"
          >
            <AppIcon name="arrow-right" />继续发布
          </el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog
      v-model="publishVisible"
      class="flow-dialog"
      width="580px"
      align-center
      :show-close="false"
    >
      <template #header>
        <div class="dialog-head">
          <div class="dialog-head-copy">
            <div class="dialog-kicker">
              PUBLISH DEFINITION
            </div>
            <h2 class="dialog-title">
              发布流程
            </h2>
            <p class="dialog-desc">
              发布会保存当前草稿、部署新定义并立即生效。
            </p>
          </div>
          <button
            class="icon-btn dialog-close"
            type="button"
            aria-label="关闭"
            @click="publishVisible = false"
          >
            <AppIcon name="x" />
          </button>
        </div>
      </template>

      <div class="task-summary publish-summary">
        <span class="tile"><AppIcon name="workflow" /></span>
        <div class="task-summary-copy">
          <b>{{ model?.name }}</b>
          <span>{{ model?.modelKey }} · 发布人 {{ auth.displayName || '当前用户' }}</span>
        </div>
        <el-tag
          class="flow-tag"
          type="warning"
        >
          V{{ nextVersion }}
        </el-tag>
      </div>

      <div class="form-data">
        <div class="form-data-row">
          <dt>当前发布版本</dt>
          <dd>{{ currentVersionText }}</dd>
        </div>
        <div class="form-data-row">
          <dt>本地校验</dt>
          <dd><span class="pill pill--teal">通过 · {{ warnCount }} 警告</span></dd>
        </div>
        <div class="form-data-row">
          <dt>生效方式</dt>
          <dd>立即生效</dd>
        </div>
        <div class="form-data-row">
          <dt>草稿处理</dt>
          <dd>发布前自动保存当前画布</dd>
        </div>
      </div>

      <el-alert
        class="flow-alert flow-alert--warning publish-alert"
        type="warning"
        :closable="false"
        show-icon
      >
        发布不会影响已启动实例：在途实例继续按 v{{ model?.deployedVersion ?? '—' }} 执行，新发起的实例使用 v{{ nextVersion }}。
      </el-alert>

      <template #footer>
        <div class="dialog-foot">
          <span class="foot-note">发布前会自动保存当前草稿</span>
          <el-button @click="publishVisible = false">
            取消
          </el-button>
          <el-button
            type="primary"
            :loading="publishing"
            @click="confirmPublish"
          >
            <AppIcon name="send" />确认发布 v{{ nextVersion }}
          </el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.pseudo-fullscreen {
  position: fixed;
  inset: 0;
  z-index: 2000;
  height: 100vh;
}

/* 顶栏按钮：EP 默认相邻外边距与形态改为示例口径 */
.designer-actions {
  :deep(.el-button + .el-button) {
    margin-left: 0;
  }

  :deep(.el-button) {
    flex: none;
  }
}

.palette {
  :deep(.palette-btn.el-button) {
    width: 40px;
    height: 40px;
    margin: 0;
    padding: 0;
    border: 1px solid transparent;
    border-radius: 9px;
    background: transparent;
    color: var(--text-3);
  }

  :deep(.palette-btn.el-button:hover) {
    border-color: var(--line);
    background: var(--wash-03);
    color: var(--text-1);
    transform: none;
  }

  :deep(.palette-btn.el-button.active) {
    border-color: var(--teal-line);
    background: var(--teal-soft);
    color: var(--teal);
  }

  :deep(.palette-btn.el-button.is-disabled) {
    opacity: 0.35;
  }

  :deep(.palette-btn .ic) {
    width: 18px;
    height: 18px;
  }
}

.zoom-bar {
  :deep(.zoom-btn.el-button) {
    width: 27px;
    height: 27px;
    margin: 0;
    padding: 0;
    border: 0;
    border-radius: 6px;
    background: transparent;
    color: var(--text-2);
  }

  :deep(.zoom-btn.el-button:hover) {
    background: var(--wash-05);
    color: var(--text-1);
    transform: none;
  }

  :deep(.zoom-btn .ic) {
    width: 15px;
    height: 15px;
  }

  .zoom-sep {
    width: 1px;
    height: 16px;
    margin: 0 2px;
  }
}

.inspector {
  :deep(.el-input__wrapper),
  :deep(.el-select__wrapper) {
    min-height: 34px;
  }

  :deep(.el-input__inner) {
    height: 32px;
    font-size: 11.5px;
  }

  :deep(.el-select__wrapper) {
    font-size: 11.5px;
  }

  :deep(.el-textarea__inner) {
    font-size: 11.5px;
  }
}

.inspector-tabs {
  display: flex;
  flex: 1;
  min-height: 0;
  flex-direction: column;

  :deep(.el-tabs__header) {
    height: 36px;
    flex: none;
    margin: 0;
    padding: 0 17px;
  }

  :deep(.el-tabs__item) {
    height: 36px;
    margin-right: 18px;
    font-size: 10.5px;
  }

  :deep(.el-tabs__content) {
    flex: 1;
    min-height: 0;
    overflow: hidden;
  }

  :deep(.el-tab-pane) {
    height: 100%;
  }

  :deep(.tab-count) {
    margin-left: 5px;
    font-size: 8px;
  }

  .inspector-body {
    height: 100%;
    overflow: auto;
    padding: 0 17px 24px;
  }
}

.checks-list {
  margin-top: 12px;
}

.recheck-btn {
  margin-top: 12px;

  :deep(.ic) {
    width: 13px;
    height: 13px;
    margin-right: 5px;
  }
}

.inspector-empty {
  padding: 28px 0;
  color: var(--text-3);
  font-size: 11px;
  text-align: center;
}

.validation-alert,
.publish-alert {
  margin-top: 14px;
}

.publish-summary {
  margin-bottom: 15px;
}

@media (max-width: 860px) {
  .designer-actions {
    :deep(.el-button) {
      width: 34px;
      padding: 0;
    }

    :deep(.btn-label),
    :deep(.badge) {
      display: none;
    }

    :deep(.ic) {
      width: 16px;
      height: 16px;
    }
  }
}

@media (max-width: 640px) {
  .designer-title .pill {
    display: none;
  }
}
</style>
