<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';

import {
  activateModel,
  getDefinitionXml,
  listModelDefinitions,
  pageModels,
  suspendModel,
  type WorkflowDefinitionVO,
  type WorkflowModelVO,
} from '@/api/workflow/model';
import AppIcon from '@/components/AppIcon.vue';
import BpmnCanvas from '@/views/modeler/designer/BpmnCanvas.vue';

const router = useRouter();

const models = ref<WorkflowModelVO[]>([]);
const modelTotal = ref(0);
const loadingModels = ref(false);
const query = ref('');
const activeFilter = ref<'all' | 'published' | 'suspended'>('all');
const selectedModelId = ref<number | null>(null);

const definitions = ref<WorkflowDefinitionVO[]>([]);
const loadingDefinitions = ref(false);
const currentXml = ref('');
const currentXmlLoading = ref(false);
const currentXmlError = ref('');

const xmlCache = new Map<string, string>();
let selectionSeq = 0;

const definedModels = computed(() =>
  models.value.filter((item) => item.deployedVersion != null || !!item.processDefinitionId)
);

const counts = computed(() => ({
  all: definedModels.value.length,
  versions: definedModels.value.reduce((sum, item) => sum + (item.deployedVersion ?? 0), 0),
  published: definedModels.value.filter((item) => item.status === 1).length,
  suspended: definedModels.value.filter((item) => item.status === 2).length,
}));

const filteredModels = computed(() => {
  const keyword = query.value.trim().toLowerCase();
  return definedModels.value.filter((item) => {
    if (keyword && !`${item.name} ${item.modelKey}`.toLowerCase().includes(keyword)) {
      return false;
    }
    if (activeFilter.value === 'published' && item.status !== 1) return false;
    if (activeFilter.value === 'suspended' && item.status !== 2) return false;
    return true;
  });
});

const selected = computed(
  () => definedModels.value.find((item) => item.modelId === selectedModelId.value) ?? null
);

const isSuspended = computed(() => selected.value?.status === 2);

const currentVersion = computed<WorkflowDefinitionVO | null>(
  () => definitions.value.find((item) => item.deployed) ?? definitions.value[0] ?? null
);

const definitionStatus = computed(() => {
  if (isSuspended.value) return { label: '已停用', cls: 'pill--danger' };
  if (selected.value?.status === 1) return { label: '生效中', cls: 'pill--teal' };
  return { label: '未发布', cls: 'pill--slate' };
});

const statusHint = computed(() => {
  if (isSuspended.value) return '新发起已被阻止';
  if (selected.value?.status === 1) return '当前版本可发起';
  return '尚未发布定义';
});

function formatTime(value?: string): string {
  if (!value) return '—';
  return value.slice(0, 19).replace('T', ' ');
}

async function loadModels(): Promise<void> {
  loadingModels.value = true;
  try {
    const result = await pageModels({ pageNum: 1, pageSize: 200 });
    models.value = result.rows;
    modelTotal.value = result.total;
  } finally {
    loadingModels.value = false;
  }
}

async function selectModel(modelId: number | null): Promise<void> {
  const seq = ++selectionSeq;
  selectedModelId.value = modelId;
  definitions.value = [];
  currentXml.value = '';
  currentXmlError.value = '';
  currentXmlLoading.value = false;
  if (modelId == null) return;

  loadingDefinitions.value = true;
  try {
    const list = await listModelDefinitions(modelId);
    if (seq !== selectionSeq) return;
    definitions.value = list;
    const current = list.find((item) => item.deployed) ?? list[0];
    if (current) {
      await loadCurrentXml(current.definitionId, seq);
    }
  } catch {
    if (seq === selectionSeq) {
      ElMessage.error('版本列表读取失败');
    }
  } finally {
    if (seq === selectionSeq) {
      loadingDefinitions.value = false;
    }
  }
}

async function loadCurrentXml(definitionId: string, seq: number): Promise<void> {
  currentXmlLoading.value = true;
  currentXmlError.value = '';
  try {
    const cached = xmlCache.get(definitionId);
    const xml = cached ?? (await getDefinitionXml(definitionId)).xml;
    if (!cached) xmlCache.set(definitionId, xml);
    if (seq === selectionSeq) currentXml.value = xml;
  } catch {
    if (seq === selectionSeq) currentXmlError.value = '当前定义 XML 读取失败';
  } finally {
    if (seq === selectionSeq) currentXmlLoading.value = false;
  }
}

function ensureSelection(): void {
  if (!filteredModels.value.some((item) => item.modelId === selectedModelId.value)) {
    void selectModel(filteredModels.value[0]?.modelId ?? null);
  }
}

watch([query, activeFilter], ensureSelection);

onMounted(() => {
  void loadModels()
    .then(ensureSelection)
    .catch(() => undefined);
});

async function refresh(): Promise<void> {
  try {
    await loadModels();
    if (selectedModelId.value != null) {
      await selectModel(selectedModelId.value);
    } else {
      ensureSelection();
    }
    ElMessage.success('流程定义已刷新');
  } catch {
    // 请求层已提示
  }
}

const statusVisible = ref(false);
const statusSubmitting = ref(false);
const statusTarget = ref<WorkflowModelVO | null>(null);

function openStatus(): void {
  if (!selected.value) return;
  statusTarget.value = selected.value;
  statusVisible.value = true;
}

async function confirmStatus(): Promise<void> {
  const target = statusTarget.value;
  if (!target || statusSubmitting.value) return;
  statusSubmitting.value = true;
  const suspending = target.status !== 2;
  try {
    if (suspending) {
      await suspendModel(target.modelId);
      ElMessage.warning('已停用，新发起将被阻止');
    } else {
      await activateModel(target.modelId);
      ElMessage.success('已启用，可重新发起');
    }
    statusVisible.value = false;
    await loadModels();
    await selectModel(target.modelId);
  } finally {
    statusSubmitting.value = false;
  }
}

const previewVisible = ref(false);
const previewTab = ref('diagram');
const previewVersion = ref<WorkflowDefinitionVO | null>(null);
const previewXml = ref('');
const previewXmlLoading = ref(false);
let previewSeq = 0;

async function openPreview(version: WorkflowDefinitionVO | null): Promise<void> {
  if (!version) return;
  previewVersion.value = version;
  previewTab.value = 'diagram';
  previewXml.value = '';
  previewVisible.value = true;

  const seq = ++previewSeq;
  previewXmlLoading.value = true;
  try {
    const cached = xmlCache.get(version.definitionId);
    const xml = cached ?? (await getDefinitionXml(version.definitionId)).xml;
    if (!cached) xmlCache.set(version.definitionId, xml);
    if (seq === previewSeq) previewXml.value = xml;
  } catch {
    if (seq === previewSeq) {
      ElMessage.error('定义 XML 读取失败');
      previewVisible.value = false;
    }
  } finally {
    if (seq === previewSeq) previewXmlLoading.value = false;
  }
}

async function copyXml(): Promise<void> {
  if (!previewXml.value) return;
  try {
    await navigator.clipboard.writeText(previewXml.value);
    ElMessage.success('已复制定义 XML');
  } catch {
    ElMessage.warning('复制失败，请手动选择文本复制');
  }
}

function openDesigner(): void {
  if (!selected.value) return;
  void router.push(`/modeler/${selected.value.modelId}/edit`);
}

function goToModels(): void {
  void router.push('/modeler');
}
</script>

<template>
  <div class="page-head">
    <div>
      <div class="eyebrow">
        FLOW ASSETS / DEFINITIONS
      </div>
      <h1>流程定义</h1>
      <p>查看已部署的 BPMN 快照、版本指针与启停状态；当前定义决定新发起使用的流程结构。</p>
    </div>
    <div class="page-actions">
      <el-button @click="refresh">
        <AppIcon name="refresh" />刷新
      </el-button>
      <el-button
        type="primary"
        @click="goToModels"
      >
        <AppIcon name="workflow" />前往建模
      </el-button>
    </div>
  </div>

  <div class="split-stats">
    <div class="mini-stat">
      <AppIcon name="layers" /><b>{{ counts.all }}</b><span>有定义的模型</span>
    </div>
    <div class="mini-stat">
      <AppIcon name="history" /><b>{{ counts.versions }}</b><span>累计版本</span>
    </div>
    <div class="mini-stat">
      <AppIcon name="check-circle" /><b>{{ counts.published }}</b><span>生效中</span>
    </div>
    <div class="mini-stat">
      <AppIcon name="pause" /><b>{{ counts.suspended }}</b><span>已停用</span>
    </div>
  </div>

  <div class="master-detail">
    <section
      v-loading="loadingModels"
      class="panel model-list"
      aria-label="有定义的流程模型"
    >
      <div class="model-list-head">
        <el-input
          v-model="query"
          class="flow-search-block"
          placeholder="搜索名称 / 标识"
          aria-label="搜索流程定义"
          clearable
        >
          <template #prefix><AppIcon name="search" /></template>
        </el-input>
        <el-radio-group
          v-model="activeFilter"
          class="flow-choices def-filters"
          size="small"
          aria-label="按状态筛选"
        >
          <el-radio-button value="all">
            全部<span class="num">{{ counts.all }}</span>
          </el-radio-button>
          <el-radio-button value="published">
            生效<span class="num">{{ counts.published }}</span>
          </el-radio-button>
          <el-radio-button value="suspended">
            停用<span class="num">{{ counts.suspended }}</span>
          </el-radio-button>
        </el-radio-group>
      </div>

      <div
        v-if="filteredModels.length"
        class="def-list"
      >
        <button
          v-for="item in filteredModels"
          :key="item.modelId"
          class="model-item"
          :class="{ active: item.modelId === selectedModelId, 'is-suspended': item.status === 2 }"
          type="button"
          :aria-pressed="item.modelId === selectedModelId"
          @click="selectModel(item.modelId)"
        >
          <span
            class="tile"
            :class="item.status === 2 ? 'tile--amber' : ''"
          >
            <AppIcon :name="item.status === 2 ? 'pause' : 'workflow'" />
          </span>
          <span class="model-item-copy">
            <span class="model-item-name">{{ item.name }}</span>
            <span class="model-item-key">{{ item.modelKey }}</span>
          </span>
          <el-tag
            class="flow-tag"
            :type="item.status === 2 ? 'warning' : 'success'"
            disable-transitions
          >
            v{{ item.deployedVersion ?? '—' }}
          </el-tag>
        </button>
      </div>

      <div
        v-else
        class="def-list-empty"
      >
        <el-empty
          class="flow-empty"
          description="没有匹配的流程定义"
        >
          <template #image>
            <span class="empty-icon"><AppIcon name="search" /></span>
          </template>
          <p class="empty-hint">
            试试调整关键词或状态筛选。
          </p>
        </el-empty>
      </div>

      <div class="def-list-foot">
        <AppIcon name="info" />
        <span>仅列出已发布过定义的模型 · 共 {{ counts.all }} 个{{ modelTotal > models.length ? `（已加载 ${models.length} 个模型）` : '' }}</span>
      </div>
    </section>

    <section
      v-if="selected"
      class="panel model-detail definition-panel"
      aria-label="流程定义详情"
    >
      <div class="detail-banner">
        <span
          class="tile"
          :class="isSuspended ? 'tile--amber' : ''"
        >
          <AppIcon :name="isSuspended ? 'pause' : 'workflow'" />
        </span>
        <div class="detail-banner-copy">
          <h2>
            <span>{{ selected.name }}</span>
            <span
              class="pill"
              :class="definitionStatus.cls"
            >{{ definitionStatus.label }}</span>
          </h2>
          <div class="detail-banner-meta">
            <span class="mono">{{ selected.modelKey }}</span>
            <el-tag
              class="flow-tag"
              disable-transitions
            >
              {{ selected.category || '未分类' }}
            </el-tag>
            <el-tag
              class="flow-tag"
              :type="isSuspended ? 'warning' : 'success'"
              disable-transitions
            >
              v{{ selected.deployedVersion ?? '—' }} · {{ isSuspended ? 'SUSPENDED' : 'CURRENT' }}
            </el-tag>
            <span aria-hidden="true">·</span>
            <span>{{ statusHint }}</span>
          </div>
        </div>
        <div class="detail-actions">
          <el-button
            size="small"
            :disabled="!currentVersion"
            @click="openPreview(currentVersion)"
          >
            <AppIcon name="code" />查看定义
          </el-button>
          <el-button
            v-if="selected.status === 1"
            size="small"
            @click="openStatus"
          >
            <AppIcon name="pause" />停用流程
          </el-button>
          <el-button
            v-else-if="selected.status === 2"
            size="small"
            @click="openStatus"
          >
            <AppIcon name="play" />启用流程
          </el-button>
          <el-button
            type="primary"
            size="small"
            @click="openDesigner"
          >
            <AppIcon name="edit" />打开设计器
          </el-button>
        </div>
      </div>

      <div class="definition-meta">
        <div class="meta-cell">
          <span>定义 ID</span>
          <b>{{ currentVersion?.definitionId ?? '—' }}</b>
        </div>
        <div class="meta-cell">
          <span>部署 ID</span>
          <b>{{ currentVersion?.deploymentId ?? '—' }}</b>
        </div>
        <div class="meta-cell">
          <span>发布时间</span>
          <b>{{ formatTime(selected.publishTime) }}</b>
        </div>
        <div class="meta-cell">
          <span>已发布版本</span>
          <b>{{ definitions.length }} 个</b>
        </div>
      </div>

      <div class="panel-head">
        <div>
          <h2 class="panel-title">
            <AppIcon name="git-branch" />当前部署定义
          </h2>
          <p class="panel-sub">
            发起新实例时加载的只读 BPMN 快照
          </p>
        </div>
        <el-tag
          class="flow-tag"
          :type="isSuspended ? 'warning' : 'success'"
          disable-transitions
        >
          v{{ currentVersion?.version ?? selected.deployedVersion ?? '—' }} · {{ isSuspended ? 'SUSPENDED' : 'CURRENT' }}
        </el-tag>
      </div>

      <div
        v-loading="loadingDefinitions || currentXmlLoading"
        class="diagram-preview definition-diagram"
      >
        <div class="preview-toolbar">
          <el-tag
            class="flow-tag"
            disable-transitions
          >
            BPMN 2.0
          </el-tag>
          <el-tag
            class="flow-tag"
            disable-transitions
          >
            READ ONLY
          </el-tag>
        </div>
        <BpmnCanvas
          v-if="currentXml"
          :xml="currentXml"
          :readonly="true"
        />
        <div
          v-else-if="currentXmlError"
          class="diagram-placeholder"
        >
          <el-empty
            class="flow-empty"
            :description="currentXmlError"
          >
            <template #image>
              <span class="empty-icon"><AppIcon name="alert" /></span>
            </template>
          </el-empty>
        </div>
      </div>

      <div class="panel-head">
        <div>
          <h2 class="panel-title">
            <AppIcon name="history" />版本历史
          </h2>
          <p class="panel-sub">
            已部署快照按发布时间倒序排列，当前指针仅有一个
          </p>
        </div>
        <span class="toolbar-note"><AppIcon name="layers" />共 <b>{{ definitions.length }}</b> 个版本</span>
      </div>

      <div
        v-loading="loadingDefinitions"
        class="version-list definition-version-list"
      >
        <div
          v-for="version in definitions"
          :key="version.definitionId"
          class="version-row"
        >
          <span class="version-no">v{{ version.version }}</span>
          <div class="version-main">
            <b>
              {{ version.definitionName || selected.name }}
              <el-tag
                v-if="version.deployed"
                class="flow-tag version-current"
                :type="isSuspended ? 'warning' : 'success'"
                disable-transitions
              >
                当前{{ isSuspended ? ' · 已停用' : '' }}
              </el-tag>
              <el-tag
                v-else-if="version.suspended"
                class="flow-tag version-current"
                type="warning"
                disable-transitions
              >
                已挂起
              </el-tag>
            </b>
            <span>{{ version.definitionKey }} · {{ version.deploymentId || version.definitionId }}</span>
          </div>
          <span
            class="version-state"
            :class="{ 'is-off': version.suspended }"
          >{{ version.deployed ? '在用版本' : version.suspended ? '已挂起' : '历史版本' }}</span>
          <div class="version-action">
            <el-button
              link
              size="small"
              @click="openPreview(version)"
            >
              <AppIcon name="eye" />预览
            </el-button>
          </div>
        </div>
      </div>

      <div class="definition-footnote">
        <AppIcon name="shield" />
        <span>已发布定义是不可变快照。修改草稿不会影响当前运行中的实例；再次发布才会移动定义指针。</span>
      </div>
    </section>

    <section
      v-else
      class="panel model-detail definition-panel"
      aria-label="流程定义详情"
    >
      <div class="empty-detail">
        <el-empty
          class="flow-empty"
          description="请选择左侧流程查看定义"
        >
          <template #image>
            <span class="empty-icon"><AppIcon name="layers" /></span>
          </template>
          <p class="empty-hint">
            选择模型后会加载当前部署定义与历史版本。
          </p>
        </el-empty>
      </div>
    </section>
  </div>

  <el-dialog
    v-model="previewVisible"
    class="flow-dialog preview-dialog"
    width="900px"
    align-center
    :show-close="false"
  >
    <template #header>
      <div class="dialog-head">
        <div class="dialog-head-copy">
          <div class="dialog-kicker">
            DEFINITION SNAPSHOT · READ ONLY
          </div>
          <h2 class="dialog-title">
            定义预览 · v{{ previewVersion?.version ?? '—' }}
          </h2>
          <p class="dialog-desc">
            已部署定义快照 · {{ previewVersion?.definitionId ?? '' }}
          </p>
        </div>
        <button
          class="icon-btn dialog-close"
          type="button"
          aria-label="关闭"
          @click="previewVisible = false"
        >
          <AppIcon name="x" />
        </button>
      </div>
    </template>

    <div class="definition-preview-wrap">
      <el-tabs
        v-model="previewTab"
        class="flow-tabs definition-preview-tabs"
      >
        <el-tab-pane
          label="流程图"
          name="diagram"
        >
          <div
            v-loading="previewXmlLoading"
            class="diagram-preview preview-stage"
          >
            <div class="preview-toolbar">
              <el-tag
                class="flow-tag"
                disable-transitions
              >
                V{{ previewVersion?.version ?? '—' }} · READ ONLY
              </el-tag>
              <button
                class="icon-btn"
                type="button"
                aria-label="复制定义 XML"
                :disabled="!previewXml"
                @click="copyXml"
              >
                <AppIcon name="copy" />
              </button>
            </div>
            <BpmnCanvas
              v-if="previewXml"
              :xml="previewXml"
              :readonly="true"
            />
          </div>
        </el-tab-pane>

        <el-tab-pane
          label="原始 XML"
          name="xml"
        >
          <div class="xml-pane">
            <div class="xml-head">
              <span><span class="mono">{{ previewVersion?.definitionId }}</span> · 只读快照</span>
              <el-button
                size="small"
                :disabled="!previewXml"
                @click="copyXml"
              >
                <AppIcon name="copy" />复制 XML
              </el-button>
            </div>
            <div class="template-preview xml-code">
              <pre><code>{{ previewXml }}</code></pre>
            </div>
            <el-alert
              class="flow-alert"
              type="info"
              :closable="false"
              show-icon
              style="margin-top: 12px"
            >
              预览内容来自已部署定义原文；版本 v{{ previewVersion?.version ?? '—' }} 是不可变快照，不会被草稿覆盖。
            </el-alert>
          </div>
        </el-tab-pane>
      </el-tabs>
      <span class="preview-readonly"><AppIcon name="lock" />只读快照</span>
    </div>

    <template #footer>
      <div class="dialog-foot">
        <span class="foot-note">只读预览 · {{ previewVersion?.definitionId ?? '' }}</span>
        <el-button @click="previewVisible = false">
          关闭
        </el-button>
        <el-button
          type="primary"
          @click="openDesigner"
        >
          打开设计器
        </el-button>
      </div>
    </template>
  </el-dialog>

  <el-dialog
    v-model="statusVisible"
    class="flow-dialog"
    width="540px"
    align-center
    :show-close="false"
  >
    <template #header>
      <div class="dialog-head">
        <div class="dialog-head-copy">
          <div
            class="dialog-kicker"
            :style="{ color: statusTarget?.status === 2 ? 'var(--teal)' : 'var(--amber)' }"
          >
            DEFINITION STATE
          </div>
          <h2 class="dialog-title">
            {{ statusTarget?.status === 2 ? '启用流程' : '停用流程' }}
          </h2>
          <p class="dialog-desc">
            {{ statusTarget?.status === 2
              ? '启用后恢复发起入口，新实例仍使用当前部署定义。'
              : '停用后，新发起请求将被拒绝；在途实例与历史数据不受影响。' }}
          </p>
        </div>
        <button
          class="icon-btn dialog-close"
          type="button"
          aria-label="关闭"
          @click="statusVisible = false"
        >
          <AppIcon name="x" />
        </button>
      </div>
    </template>

    <div
      v-if="statusTarget"
      class="task-summary"
      style="margin-bottom: 14px"
    >
      <span
        class="tile"
        :class="statusTarget.status === 2 ? 'tile--amber' : ''"
      >
        <AppIcon :name="statusTarget.status === 2 ? 'pause' : 'workflow'" />
      </span>
      <div class="task-summary-copy">
        <b>{{ statusTarget.name }}</b>
        <span>{{ statusTarget.modelKey }}</span>
      </div>
      <span
        class="pill"
        :class="statusTarget.status === 2 ? 'pill--danger' : 'pill--teal'"
      >
        {{ statusTarget.status === 2 ? '已停用' : '生效中' }}
      </span>
    </div>

    <el-alert
      v-if="statusTarget"
      class="flow-alert"
      :class="statusTarget.status === 2 ? '' : 'flow-alert--error'"
      :type="statusTarget.status === 2 ? 'warning' : 'error'"
      :closable="false"
      show-icon
    >
      {{ statusTarget.status === 2
        ? `将启用「${statusTarget.name}」，当前定义 v${statusTarget.deployedVersion ?? '—'} 会立即对新发起生效。`
        : `将停用「${statusTarget.name}」，调用方发起时会收到“流程已挂起”的拒绝响应。` }}
    </el-alert>

    <template #footer>
      <div class="dialog-foot">
        <span class="foot-note">模型 {{ statusTarget?.name ?? '' }} · 当前定义 v{{ statusTarget?.deployedVersion ?? '—' }}</span>
        <el-button @click="statusVisible = false">
          取消
        </el-button>
        <el-button
          :type="statusTarget?.status === 2 ? 'primary' : 'danger'"
          :loading="statusSubmitting"
          @click="confirmStatus"
        >
          <AppIcon :name="statusTarget?.status === 2 ? 'play' : 'pause'" />{{ statusTarget?.status === 2 ? '启用流程' : '停用流程' }}
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
.model-list-head .flow-search-block {
  width: 100%;
}

.def-filters {
  width: 100%;
  margin-top: 10px;
  flex-wrap: nowrap;
}

.def-filters :deep(.el-radio-button) {
  flex: 1;
  min-width: 0;
}

.def-filters :deep(.el-radio-button__inner) {
  width: 100%;
  justify-content: center;
}

.def-filters .num {
  font-family: var(--font-mono);
  font-size: 9.5px;
  opacity: 0.8;
}

.model-item .el-tag {
  margin-left: auto;
}

.model-item-name,
.model-item-key {
  display: block;
}

.model-item.is-suspended .model-item-name {
  color: var(--text-3);
}

.def-list {
  max-height: 540px;
  overflow-y: auto;
}

.def-list-empty {
  padding: 10px 0;
}

.def-list-foot {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 11px 14px;
  border-top: 1px solid var(--line);
  color: var(--text-3);
  font-size: 9.5px;
}

.def-list-foot .ic {
  width: 12px;
  height: 12px;
}

.detail-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 7px;
  margin-left: auto;
}

.detail-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.definition-meta {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  padding: 15px 18px;
  border-bottom: 1px solid var(--line);
}

.definition-meta .meta-cell {
  min-width: 0;
}

.definition-diagram {
  height: 320px;
  border-bottom: 1px solid var(--line);
}

.definition-diagram :deep(.bpmn-canvas),
.preview-stage :deep(.bpmn-canvas) {
  min-width: 0;
  min-height: 0;
}

.diagram-placeholder {
  display: flex;
  height: 100%;
  align-items: center;
  justify-content: center;
}

.definition-version-list {
  border: 0;
  border-radius: 0;
  background: transparent;
}

.version-current {
  margin-left: 6px;
}

.version-state {
  color: var(--text-3);
  font-size: 10.5px;
}

.version-state.is-off {
  color: var(--amber);
}

.version-action {
  display: flex;
  justify-content: flex-end;
}

.definition-footnote {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 12px 18px;
  border-top: 1px solid var(--line);
  color: var(--text-3);
  font-size: 10px;
  line-height: 1.5;
}

.definition-footnote .ic {
  width: 13px;
  height: 13px;
  margin-top: 1px;
  flex: none;
  color: var(--teal);
}

.empty-detail {
  display: grid;
  min-height: 420px;
  place-items: center;
}

.definition-preview-wrap {
  position: relative;
}

.preview-readonly {
  position: absolute;
  top: 15px;
  right: 20px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--text-3);
  font-size: 10px;
  pointer-events: none;
}

.preview-readonly .ic {
  width: 12px;
  height: 12px;
}

.definition-preview-tabs :deep(.el-tabs__header) {
  padding-right: 160px;
}

.preview-dialog :deep(.el-dialog__body) {
  padding: 0;
}

.preview-dialog .diagram-preview {
  border: 0;
  border-radius: 0;
}

.preview-stage {
  height: 460px;
}

.xml-pane {
  padding: 16px 20px 20px;
}

.xml-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
  color: var(--text-3);
  font-size: 10px;
}

.xml-code {
  margin-top: 0;
  background: rgba(3, 13, 17, 0.72);
}

.xml-code pre {
  max-height: 346px;
  font-size: 10.5px;
  line-height: 1.7;
  white-space: pre;
}

.dialog-foot {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.dialog-foot .foot-note {
  margin-right: auto;
}

.dialog-foot :deep(.el-button + .el-button) {
  margin-left: 0;
}

@media (max-width: 1080px) {
  .definition-meta {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .detail-actions {
    width: 100%;
    margin-left: 0;
  }
}

@media (max-width: 560px) {
  .definition-meta {
    grid-template-columns: 1fr;
  }

  .detail-actions :deep(.el-button) {
    flex: 1;
  }

  .xml-pane {
    padding: 14px;
  }
}
</style>
