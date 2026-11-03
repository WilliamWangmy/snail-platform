<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';

import {
  activateModel,
  deleteModel,
  pageModels,
  suspendModel,
  type WorkflowModelVO,
} from '@/api/workflow/model';
import AppIcon from '@/components/AppIcon.vue';
import { useTable } from '@/composables/useTable';

import DefinitionListDialog from './components/DefinitionListDialog.vue';
import ModelCreateDialog from './components/ModelCreateDialog.vue';

const router = useRouter();

type ModelQuery = {
  modelKey?: string;
  name?: string;
  category?: string;
  status?: number;
  [key: string]: unknown;
};

const { rows, total, loading, query, search } = useTable<WorkflowModelVO, ModelQuery>(pageModels, {
  pageNum: 1,
  pageSize: 10,
});

const pageNum = computed({
  get: () => query.value.pageNum ?? 1,
  set: (value: number) => {
    query.value.pageNum = value;
  },
});

const pageSize = computed(() => query.value.pageSize ?? 10);

const filters = reactive({
  keyword: '',
  category: '',
  statusKey: '',
});

const STATUS_FILTERS = [
  { key: 'published', label: '已发布', value: 1 },
  { key: 'draft', label: '未发布', value: 0 },
  { key: 'disabled', label: '已停用', value: 2 },
];

const statsRows = ref<WorkflowModelVO[]>([]);
const stats = reactive({ total: 0, published: 0, draft: 0, disabled: 0 });

const categories = computed(() =>
  Array.from(new Set(statsRows.value.map((item) => item.category).filter((item): item is string => !!item)))
);

type StatusMeta = { key: string; label: string; cls: string };

function statusMeta(model: WorkflowModelVO): StatusMeta {
  if (model.status === 2) return { key: 'disabled', label: '已停用', cls: 'status-tag--danger' };
  if (model.status === 1) return { key: 'published', label: '已发布', cls: 'status-tag--teal' };
  if (model.hasDraft) return { key: 'draft', label: '草稿待发布', cls: 'status-tag--amber' };
  return { key: 'unpublished', label: '未发布', cls: 'status-tag--slate' };
}

function tileIcon(model: WorkflowModelVO): string {
  const key = statusMeta(model).key;
  if (key === 'disabled') return 'pause';
  if (key === 'draft' || key === 'unpublished') return 'edit';
  return 'workflow';
}

function tileTone(model: WorkflowModelVO): string {
  const key = statusMeta(model).key;
  if (key === 'disabled') return 'tile--danger';
  if (key === 'draft' || key === 'unpublished') return 'tile--amber';
  return '';
}

const createDialogRef = ref<InstanceType<typeof ModelCreateDialog>>();
const versionsDialogRef = ref<InstanceType<typeof DefinitionListDialog>>();

const deleteVisible = ref(false);
const deleteTarget = ref<WorkflowModelVO | null>(null);
const deleteSubmitting = ref(false);

async function loadStats(): Promise<void> {
  const result = await pageModels({ pageNum: 1, pageSize: 200 });
  statsRows.value = result.rows;
  stats.total = result.total;
  stats.published = result.rows.filter((item) => item.status === 1).length;
  stats.draft = result.rows.filter((item) => item.status === 0).length;
  stats.disabled = result.rows.filter((item) => item.status === 2).length;
}

onMounted(() => {
  void Promise.all([search(), loadStats()]);
});

async function refreshAll(): Promise<void> {
  await Promise.all([search(), loadStats()]);
  ElMessage.success('模型列表已刷新');
}

function applyFilters(): void {
  Object.assign(query.value, {
    name: undefined,
    modelKey: undefined,
    category: undefined,
    status: undefined,
    pageNum: 1,
  });

  const keyword = filters.keyword.trim();
  if (keyword) {
    if (/^[a-z][A-Za-z0-9_]*$/i.test(keyword)) {
      query.value.modelKey = keyword;
    } else {
      query.value.name = keyword;
    }
  }
  if (filters.category) {
    query.value.category = filters.category;
  }
  const status = STATUS_FILTERS.find((item) => item.key === filters.statusKey);
  if (status) {
    query.value.status = status.value;
  }
  void search();
}

function resetFilters(): void {
  filters.keyword = '';
  filters.category = '';
  filters.statusKey = '';
  applyFilters();
}

function onPageChange(): void {
  void search();
}

function openDesigner(model: WorkflowModelVO): void {
  void router.push(`/modeler/${model.modelId}/edit`);
}

async function onSuspend(model: WorkflowModelVO): Promise<void> {
  await suspendModel(model.modelId);
  ElMessage.warning('已停用，新发起将被阻止');
  await refreshAll();
}

async function onActivate(model: WorkflowModelVO): Promise<void> {
  await activateModel(model.modelId);
  ElMessage.success('已启用，可重新发起');
  await refreshAll();
}

function commandDelete(model: WorkflowModelVO): void {
  deleteTarget.value = model;
  deleteVisible.value = true;
}

async function handleRowCommand(command: string, model: WorkflowModelVO): Promise<void> {
  if (command === 'disable') {
    await onSuspend(model);
    return;
  }
  if (command === 'enable') {
    await onActivate(model);
    return;
  }
  if (command === 'delete') {
    commandDelete(model);
  }
}

async function confirmDelete(): Promise<void> {
  if (!deleteTarget.value || deleteSubmitting.value) return;
  deleteSubmitting.value = true;
  try {
    await deleteModel(deleteTarget.value.modelId);
    ElMessage.warning('模型已删除');
    deleteVisible.value = false;
    await refreshAll();
  } finally {
    deleteSubmitting.value = false;
  }
}

function onCreated(modelId: number): void {
  void router.push(`/modeler/${modelId}/edit`);
}

/** el-table 插槽 row 为 DefaultRow，一次性收窄 */
const asModel = (row: unknown): WorkflowModelVO => row as WorkflowModelVO;
</script>

<template>
  <div class="page-head">
    <div>
      <div class="eyebrow">
        FLOW MODELING / MODEL LIBRARY
      </div>
      <h1>流程建模</h1>
      <p>设计流程草稿、管理发布版本与启停状态。模型发布后即成为可发起的最新定义。</p>
    </div>
    <div class="page-actions">
      <el-button @click="refreshAll">
        <AppIcon name="refresh" />刷新
      </el-button>
      <el-button
        type="primary"
        @click="createDialogRef?.open()"
      >
        <AppIcon name="plus" />新建模型
      </el-button>
    </div>
  </div>

  <div class="split-stats">
    <div class="mini-stat">
      <AppIcon name="workflow" /><b>{{ stats.total }}</b><span>模型总数</span>
    </div>
    <div class="mini-stat">
      <AppIcon name="check-circle" /><b>{{ stats.published }}</b><span>已发布</span>
    </div>
    <div class="mini-stat">
      <AppIcon name="edit" /><b>{{ stats.draft }}</b><span>未发布</span>
    </div>
    <div class="mini-stat">
      <AppIcon name="pause" /><b>{{ stats.disabled }}</b><span>已停用</span>
    </div>
  </div>

  <section class="panel">
    <div class="toolbar">
      <el-input
        v-model="filters.keyword"
        class="flow-search"
        placeholder="搜索模型名称 / 标识"
        aria-label="搜索模型名称或标识"
        clearable
        @keyup.enter="applyFilters"
      >
        <template #prefix><AppIcon name="search" /></template>
      </el-input>
      <el-select
        v-model="filters.category"
        class="flow-select"
        placeholder="全部分类"
        aria-label="分类筛选"
        clearable
      >
        <el-option
          v-for="item in categories"
          :key="item"
          :label="item"
          :value="item"
        />
      </el-select>
      <el-select
        v-model="filters.statusKey"
        class="flow-select"
        placeholder="全部状态"
        aria-label="状态筛选"
        clearable
      >
        <el-option
          v-for="item in STATUS_FILTERS"
          :key="item.key"
          :label="item.label"
          :value="item.key"
        />
      </el-select>
      <el-button
        size="small"
        @click="applyFilters"
      >
        <AppIcon name="search" />查询
      </el-button>
      <el-button
        size="small"
        link
        class="ghost-btn"
        @click="resetFilters"
      >
        重置
      </el-button>
      <span class="grow"></span>
      <span class="toolbar-note"><AppIcon name="filter" />共 {{ total }} 个模型 · 当前页 {{ rows.length }} 个</span>
    </div>

    <div class="table-wrap">
      <el-table
        v-loading="loading"
        :data="rows"
        class="flow-table model-table"
        row-key="modelId"
      >
        <el-table-column
          label="模型"
          min-width="220"
        >
          <template #default="{ row }">
            <div class="cell-main">
              <span
                class="tile"
                :class="tileTone(asModel(row))"
              >
                <AppIcon :name="tileIcon(asModel(row))" />
              </span>
              <div class="cell-copy">
                <div class="cell-title">
                  {{ asModel(row).name }}
                </div>
                <div class="cell-sub">
                  {{ asModel(row).modelKey }}
                </div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column
          label="分类"
          min-width="90"
        >
          <template #default="{ row }">
            <el-tag
              class="flow-tag"
              disable-transitions
            >
              {{ asModel(row).category || '未分类' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="状态"
          min-width="120"
        >
          <template #default="{ row }">
            <el-tag
              class="status-tag"
              :class="statusMeta(asModel(row)).cls"
              disable-transitions
            >
              {{ statusMeta(asModel(row)).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="当前版本"
          min-width="120"
        >
          <template #default="{ row }">
            <span class="mono strong">{{ asModel(row).deployedVersion ? `v${asModel(row).deployedVersion}` : '—' }}</span>
            <el-tag
              v-if="statusMeta(asModel(row)).key === 'published'"
              class="flow-tag version-current"
              type="success"
              disable-transitions
            >
              当前
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="备注"
          min-width="220"
        >
          <template #default="{ row }">
            <span class="remark-cell">{{ asModel(row).remark || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          min-width="190"
          align="right"
        >
          <template #default="{ row }">
            <div class="row-actions">
              <el-button
                link
                size="small"
                @click="openDesigner(asModel(row))"
              >
                <AppIcon name="edit" />编辑
              </el-button>
              <el-button
                link
                size="small"
                class="ghost-btn"
                @click="versionsDialogRef?.open(asModel(row))"
              >
                <AppIcon name="history" />版本
              </el-button>
              <el-dropdown
                trigger="click"
                @command="(command: string) => handleRowCommand(command, asModel(row))"
              >
                <button
                  class="icon-btn"
                  type="button"
                  aria-label="更多操作"
                >
                  <AppIcon name="more" />
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item
                      v-if="statusMeta(asModel(row)).key === 'published'"
                      command="disable"
                    >
                      <span class="menu-inline"><AppIcon name="pause" />停用流程</span>
                    </el-dropdown-item>
                    <el-dropdown-item
                      v-if="statusMeta(asModel(row)).key === 'disabled'"
                      command="enable"
                    >
                      <span class="menu-inline"><AppIcon name="play" />启用流程</span>
                    </el-dropdown-item>
                    <el-dropdown-item
                      :divided="statusMeta(asModel(row)).key !== 'draft' && statusMeta(asModel(row)).key !== 'unpublished'"
                      command="delete"
                      class="menu-danger"
                    >
                      <span class="menu-inline"><AppIcon name="trash" />删除模型</span>
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty
            class="flow-empty"
            description="没有匹配的流程模型"
          >
            <template #image>
              <span class="empty-icon"><AppIcon name="inbox" /></span>
            </template>
            <p class="empty-hint">
              试试调整关键词、分类或状态筛选。
            </p>
          </el-empty>
        </template>
      </el-table>
    </div>

    <div class="pager flow-pager-bar">
      <span class="pager-note">共 {{ total }} 条 · 每页 {{ pageSize }} 条</span>
      <el-pagination
        v-model:current-page="pageNum"
        class="flow-pager"
        layout="prev, pager, next"
        :total="total"
        :page-size="pageSize"
        :pager-count="5"
        @current-change="onPageChange"
      />
    </div>
  </section>

  <ModelCreateDialog
    ref="createDialogRef"
    @success="onCreated"
  />
  <DefinitionListDialog ref="versionsDialogRef" />

  <el-dialog
    v-model="deleteVisible"
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
            style="color: var(--danger)"
          >
            DESTRUCTIVE ACTION
          </div>
          <h2 class="dialog-title">
            删除流程模型
          </h2>
          <p class="dialog-desc">
            此操作不可撤销。
          </p>
        </div>
        <button
          class="icon-btn dialog-close"
          type="button"
          aria-label="关闭"
          @click="deleteVisible = false"
        >
          <AppIcon name="x" />
        </button>
      </div>
    </template>

    <el-alert
      v-if="deleteTarget"
      class="flow-alert flow-alert--error"
      type="error"
      :closable="false"
      show-icon
    >
      确定删除模型「{{ deleteTarget.name }}（{{ deleteTarget.modelKey }}）」吗？存在在途实例时后端将拒绝删除，已发布版本与历史实例不会被级联清除。
    </el-alert>

    <template #footer>
      <div class="dialog-foot">
        <el-button @click="deleteVisible = false">
          取消
        </el-button>
        <el-button
          type="danger"
          :loading="deleteSubmitting"
          @click="confirmDelete"
        >
          确认删除
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
.toolbar .flow-search {
  width: 236px;
}

.toolbar .flow-select {
  width: 148px;
}

.model-table .cell-title {
  font-size: 11.5px;
}

.model-table .cell-sub {
  font-size: 9px;
}

.remark-cell {
  color: var(--text-3);
  font-size: 11px;
  white-space: normal;
}

.tile.tile--danger {
  border-color: var(--danger-line);
  background: var(--danger-soft);
  color: var(--danger-bright);
}

.version-current {
  margin-left: 6px;
}

.row-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 2px;
}

.row-actions :deep(.el-button + .el-button),
.row-actions :deep(.el-dropdown) {
  margin-left: 0;
}

.menu-inline {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.menu-inline .ic {
  width: 14px;
  height: 14px;
}

.menu-danger {
  color: var(--danger-bright);
}

@media (max-width: 860px) {
  .toolbar .flow-search,
  .toolbar .flow-select {
    width: 100%;
  }
}
</style>
