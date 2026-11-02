<script setup lang="ts">
/**
 * 设计器页：顶栏（返回/模型信息/全屏/保存草稿/发布）+ bpmn-js 画布 + 右侧属性面板。
 * 数据流：draft 读取 → 编辑 → 本地校验（error 阻断）→ 保存/发布；离开脏守卫；Fullscreen API 全屏。
 */
import { computed, ref } from 'vue';
import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';

import {
  getModel,
  getModelDraft,
  publishModel,
  saveModelDraft,
  type WorkflowModelVO,
} from '@/api/workflow/model';

import BpmnCanvas from './BpmnCanvas.vue';
import { validateModel, validateXmlSize, type ValidationIssue } from './bpmnValidator';
import { blankXml } from './flowType';
import type { FlowElementLike } from './flowableExtension';
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

const route = useRoute();
const router = useRouter();

const modelId = Number(route.params.modelId);
const model = ref<WorkflowModelVO>();
const xml = ref('');
const loading = ref(true);
const dirty = ref(false);
const saving = ref(false);
const publishing = ref(false);

const selectedId = ref<string | null>(null);
const selectedType = ref<string | null>(null);
const services = ref<BpmnServices>();

const issues = ref<ValidationIssue[]>([]);
const showIssues = ref(false);

const canvasRef = ref<InstanceType<typeof BpmnCanvas>>();
const designerRoot = ref<HTMLElement>();

const statusLabel = computed(() => {
  if (!model.value) return '';
  if (model.value.deployedVersion) return `已发布 v${model.value.deployedVersion}`;
  return model.value.status === 1 ? '已启用' : '已停用';
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
  const diagram = canvasRef.value?.getModeler() as Diagram | null;
  if (!diagram) return;
  const modeling = getSvc<BpmnServices['modeling']>(diagram, 'modeling');
  const bpmnFactory = getSvc<BpmnServices['bpmnFactory']>(diagram, 'bpmnFactory');
  if (modeling && bpmnFactory) {
    services.value = { modeling, bpmnFactory };
  }
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
  const canvas = getSvc<CanvasLike>(canvasRef.value?.getModeler() as Diagram | null, 'canvas');
  if (!canvas) return null;
  return canvas.getRootElements().find((el) => el.businessObject.$type === 'bpmn:Process') ?? null;
});

function onSelectionChanged(sel: { id: string; $type: string } | null): void {
  selectedId.value = sel?.id ?? null;
  selectedType.value = sel?.$type ?? null;
}

/* ---------------------------------- 校验与保存/发布 ---------------------------------- */

function runValidation(): ValidationIssue[] {
  const diagram = canvasRef.value?.getModeler() as Diagram | null;
  return diagram ? validateModel(diagram as unknown as Parameters<typeof validateModel>[0]) : [];
}

function locateIssue(issue: ValidationIssue): void {
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

async function onSave(): Promise<void> {
  if (saving.value) return;
  saving.value = true;
  try {
    issues.value = runValidation();
    if (issues.value.some((i) => i.level === 'error')) {
      showIssues.value = true;
      ElMessage.error('本地校验未通过，请先修复错误');
      return;
    }
    const exported = await canvasRef.value?.exportXml();
    if (!exported) return;
    const sizeIssues = validateXmlSize(exported);
    if (sizeIssues.length > 0) {
      issues.value = sizeIssues;
      showIssues.value = true;
      return;
    }
    await saveModelDraft(modelId, exported);
    dirty.value = false;
    ElMessage.success('草稿已保存');
  } finally {
    saving.value = false;
  }
}

async function onPublish(): Promise<void> {
  if (publishing.value) return;
  publishing.value = true;
  try {
    issues.value = runValidation();
    if (issues.value.some((i) => i.level === 'error')) {
      showIssues.value = true;
      ElMessage.error('本地校验未通过，请先修复错误');
      return;
    }
    const exported = await canvasRef.value?.exportXml();
    if (!exported) return;
    const sizeIssues = validateXmlSize(exported);
    if (sizeIssues.length > 0) {
      issues.value = sizeIssues;
      showIssues.value = true;
      return;
    }
    // 草稿与发布同源：先存草稿再发布，保证发布内容与画布一致
    await saveModelDraft(modelId, exported);
    await publishModel(modelId);
    dirty.value = false;
    model.value = await getModel(modelId);
    ElMessage.success('发布成功');
  } finally {
    publishing.value = false;
  }
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
    class="designer"
    :class="{ 'pseudo-fullscreen': pseudoFullscreen }"
  >
    <!-- 设计器顶栏（全屏态下依然可用） -->
    <div class="designer-bar">
      <el-button
        text
        @click="goBack"
      >
        ← 返回列表
      </el-button>
      <div class="bar-title">
        <span class="model-name">{{ model?.name }}</span>
        <span class="model-key">{{ model?.modelKey }}</span>
        <el-tag
          size="small"
          :type="model?.deployedVersion ? 'success' : 'info'"
        >
          {{ statusLabel }}
        </el-tag>
        <el-tag
          v-if="dirty"
          size="small"
          type="warning"
          effect="plain"
        >
          未保存
        </el-tag>
      </div>
      <div class="bar-actions">
        <el-button
          :type="fullscreenSupported ? 'default' : 'info'"
          @click="toggleFullscreen"
        >
          {{ inFullscreen ? '退出全屏' : (fullscreenSupported ? '全屏' : '伪全屏') }}
        </el-button>
        <el-popover
          v-model:visible="showIssues"
          placement="bottom-end"
          :width="420"
          trigger="click"
        >
          <template #reference>
            <el-badge
              :value="issues.length"
              :hidden="issues.length === 0"
              type="danger"
            >
              <el-button>校验结果</el-button>
            </el-badge>
          </template>
          <div
            v-if="issues.length === 0"
            class="issue-empty"
          >
            暂无校验问题
          </div>
          <div
            v-for="(issue, index) in issues"
            :key="index"
            class="issue-item"
            :class="issue.level"
            @click="locateIssue(issue)"
          >
            <span class="issue-level">{{ issue.level === 'error' ? '错误' : '警告' }}</span>
            <span class="issue-message">{{ issue.message }}</span>
          </div>
        </el-popover>
        <el-button
          :loading="saving"
          @click="onSave"
        >
          保存草稿
        </el-button>
        <el-button
          type="primary"
          :loading="publishing"
          @click="onPublish"
        >
          发布
        </el-button>
      </div>
    </div>

    <!-- 三栏主体：bpmn-js 自带左侧元素栏（已裁剪） + 画布 + 右侧面板 -->
    <div class="designer-body">
      <div class="canvas-stage">
        <BpmnCanvas
          ref="canvasRef"
          :xml="xml"
          :readonly="false"
          @imported="onImported"
          @import-error="onImportError"
          @selection-changed="onSelectionChanged"
          @changed="dirty = true"
        />
      </div>
      <aside class="prop-panel">
        <ProcessPanel
          v-if="selectedElement?.businessObject.$type === 'bpmn:Process' && services && model"
          :key="`process-${model.modelKey}`"
          :element="selectedElement"
          :model-key="model.modelKey"
          :modeling="services.modeling"
        />
        <UserTaskPanel
          v-else-if="selectedElement?.businessObject.$type === 'bpmn:UserTask' && services"
          :key="`task-${selectedElement.businessObject.id}`"
          :element="selectedElement"
          :services="services"
        />
        <SequenceFlowPanel
          v-else-if="selectedElement?.businessObject.$type === 'bpmn:SequenceFlow' && services"
          :key="`flow-${selectedElement.businessObject.id}`"
          :element="selectedElement"
          :services="services"
        />
        <div
          v-else
          class="panel-placeholder"
        >
          {{ selectedElement ? '该元素类型暂无可配置项' : '点击画布中的元素配置属性' }}
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped lang="scss">
.designer {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: calc(100vh - var(--topnav-height, 56px));
  background: var(--el-bg-color);
}

.pseudo-fullscreen {
  position: fixed;
  inset: 0;
  z-index: 2000;
  height: 100vh;
}

.designer-bar {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-shrink: 0;
  height: 48px;
  padding: 0 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
}

.bar-title {
  display: flex;
  flex: 1;
  align-items: center;
  gap: 10px;
  min-width: 0;

  .model-name {
    font-weight: 600;
    color: var(--el-text-color-primary);
  }

  .model-key {
    font-family: monospace;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
}

.bar-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.designer-body {
  display: flex;
  flex: 1;
  min-height: 0;
}

.canvas-stage {
  flex: 1;
  min-width: 0;
}

.prop-panel {
  width: 340px;
  flex-shrink: 0;
  overflow-y: auto;
  border-left: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
}

.panel-placeholder {
  padding: 24px 14px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  text-align: center;
}

.issue-empty {
  padding: 8px 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  text-align: center;
}

.issue-item {
  display: flex;
  gap: 8px;
  padding: 6px 4px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  font-size: 12px;
  cursor: pointer;

  &:hover {
    background: var(--el-fill-color-light);
  }

  .issue-level {
    flex-shrink: 0;
    font-weight: 600;
  }

  &.error .issue-level {
    color: var(--el-color-danger);
  }

  &.warn .issue-level {
    color: var(--el-color-warning);
  }

  .issue-message {
    word-break: break-all;
  }
}
</style>
