<script setup lang="ts">
/**
 * 流程建模：模型列表页。查询区 + 分页表格 + 行操作（编辑/版本/停用启用/删除）+ 新建（空白/模板）。
 */
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';

import {
  activateModel,
  deleteModel,
  pageModels,
  suspendModel,
  type WorkflowModelVO,
} from '@/api/workflow/model';
import { useTable } from '@/composables/useTable';

import DefinitionListDialog from './components/DefinitionListDialog.vue';
import ModelCreateDialog from './components/ModelCreateDialog.vue';

const router = useRouter();

type ModelQuery = {
  modelKey?: string;
  name?: string;
  category?: string;
  [key: string]: unknown;
};

const { rows, total, loading, query, search } = useTable<WorkflowModelVO, ModelQuery>(pageModels, {});

const createDialogRef = ref<InstanceType<typeof ModelCreateDialog>>();
const versionsDialogRef = ref<InstanceType<typeof DefinitionListDialog>>();

onMounted(() => {
  void search();
});

function onSearch(): void {
  query.value.pageNum = 1;
  void search();
}

function openDesigner(model: WorkflowModelVO): void {
  void router.push(`/modeler/${model.modelId}/edit`);
}

async function onSuspend(model: WorkflowModelVO): Promise<void> {
  await suspendModel(model.modelId);
  ElMessage.success('已停用');
  void search();
}

async function onActivate(model: WorkflowModelVO): Promise<void> {
  await activateModel(model.modelId);
  ElMessage.success('已启用');
  void search();
}

async function onDelete(model: WorkflowModelVO): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除模型「${model.name}（${model.modelKey}）」吗？有在途实例时将被拒绝。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  await deleteModel(model.modelId);
  ElMessage.success('已删除');
  void search();
}

function onCreated(): void {
  void search();
}

/** el-table 插槽 row 为 DefaultRow，一次性收窄 */
const asModel = (row: unknown): WorkflowModelVO => row as WorkflowModelVO;
</script>

<template>
  <div class="page-shell">
    <div class="page-header">
      <h2 class="page-title">
        流程建模
      </h2>
      <el-button
        type="primary"
        @click="createDialogRef?.open()"
      >
        新建模型
      </el-button>
    </div>

    <div class="query-bar">
      <el-input
        v-model="query.modelKey"
        class="query-item"
        placeholder="模型标识"
        clearable
        @keyup.enter="onSearch"
      />
      <el-input
        v-model="query.name"
        class="query-item"
        placeholder="模型名称"
        clearable
        @keyup.enter="onSearch"
      />
      <el-input
        v-model="query.category"
        class="query-item"
        placeholder="分类"
        clearable
        @keyup.enter="onSearch"
      />
      <el-button
        type="primary"
        @click="onSearch"
      >
        查询
      </el-button>
    </div>

    <el-table
      v-loading="loading"
      :data="rows"
      class="table"
      size="default"
    >
      <el-table-column
        prop="modelKey"
        label="模型标识"
        min-width="150"
        show-overflow-tooltip
      >
        <template #default="{ row }">
          <span class="mono">{{ row.modelKey }}</span>
        </template>
      </el-table-column>
      <el-table-column
        prop="name"
        label="模型名称"
        min-width="140"
        show-overflow-tooltip
      />
      <el-table-column
        prop="category"
        label="分类"
        width="120"
        show-overflow-tooltip
      />
      <el-table-column
        label="状态"
        width="150"
      >
        <template #default="{ row }">
          <el-tag
            v-if="row.deployedVersion"
            size="small"
            type="success"
          >
            已发布 v{{ row.deployedVersion }}
          </el-tag>
          <el-tag
            v-else-if="row.hasDraft"
            size="small"
            type="warning"
            effect="plain"
          >
            草稿待发布
          </el-tag>
          <el-tag
            v-else
            size="small"
            type="info"
            effect="plain"
          >
            未发布
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        prop="remark"
        label="备注"
        min-width="140"
        show-overflow-tooltip
      />
      <el-table-column
        label="操作"
        width="260"
        fixed="right"
      >
        <template #default="{ row }">
          <el-button
            text
            type="primary"
            size="small"
            @click="openDesigner(asModel(row))"
          >
            编辑
          </el-button>
          <el-button
            text
            type="primary"
            size="small"
            @click="versionsDialogRef?.open(row.modelKey, row.modelId, row.deployedVersion)"
          >
            版本
          </el-button>
          <el-button
            v-if="row.deployedVersion"
            text
            type="warning"
            size="small"
            @click="onSuspend(asModel(row))"
          >
            停用
          </el-button>
          <el-button
            v-else
            text
            type="success"
            size="small"
            @click="onActivate(asModel(row))"
          >
            启用
          </el-button>
          <el-button
            text
            type="danger"
            size="small"
            @click="onDelete(asModel(row))"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="query.pageNum"
      v-model:page-size="query.pageSize"
      class="pager"
      background
      layout="total, prev, pager, next, sizes"
      :total="total"
      :page-sizes="[10, 20, 50]"
      @current-change="search"
      @size-change="onSearch"
    />

    <ModelCreateDialog
      ref="createDialogRef"
      @success="onCreated"
    />
    <DefinitionListDialog ref="versionsDialogRef" />
  </div>
</template>

<style scoped lang="scss">
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.query-bar {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
}

.query-item {
  width: 200px;
}

.table {
  width: 100%;
}

.mono {
  font-family: monospace;
  font-size: 12px;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
