<script setup lang="ts">
/**
 * 版本列表对话框：展示某模型的历史发布版本 + 已发布 XML 只读预览（内嵌 viewer）。
 */
import { ref } from 'vue';
import { ElMessage } from 'element-plus';

import {
  getDefinitionXml,
  listModelDefinitions,
  type WorkflowDefinitionVO,
} from '@/api/workflow/model';
import BpmnCanvas from '../designer/BpmnCanvas.vue';

const visible = ref(false);
const loading = ref(false);
const modelKey = ref('');
const definitions = ref<WorkflowDefinitionVO[]>([]);
const currentVersion = ref<number | null>(null);

/* 只读预览 */
const previewVisible = ref(false);
const previewVersion = ref<number | null>(null);
const previewXml = ref('');
const previewLoading = ref(false);

async function open(key: string, modelId: number, deployedVersion: number | undefined): Promise<void> {
  visible.value = true;
  modelKey.value = key;
  currentVersion.value = deployedVersion ?? null;
  definitions.value = [];
  loading.value = true;
  try {
    definitions.value = await listModelDefinitions(modelId);
  } finally {
    loading.value = false;
  }
}

/** el-table 插槽 row 为 DefaultRow，一次性收窄 */
const asDefinition = (row: unknown): WorkflowDefinitionVO => row as WorkflowDefinitionVO;

async function openPreview(rawRow: unknown): Promise<void> {
  const row = asDefinition(rawRow);
  previewVisible.value = true;
  previewVersion.value = row.version;
  previewXml.value = '';
  previewLoading.value = true;
  try {
    previewXml.value = (await getDefinitionXml(row.definitionId)).xml;
  } catch {
    ElMessage.error('版本 XML 读取失败');
    previewVisible.value = false;
  } finally {
    previewLoading.value = false;
  }
}

defineExpose({ open });
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="`版本列表 - ${modelKey}`"
    width="640px"
    destroy-on-close
  >
    <el-table
      v-loading="loading"
      :data="definitions"
      size="default"
      max-height="380"
    >
      <el-table-column
        prop="version"
        label="版本"
        width="80"
      >
        <template #default="{ row }">
          v{{ row.version }}
          <el-tag
            v-if="row.version === currentVersion"
            size="small"
            type="success"
            effect="plain"
          >
            当前
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        prop="definitionName"
        label="定义名称"
        min-width="140"
        show-overflow-tooltip
      />
      <el-table-column
        prop="definitionKey"
        label="定义标识"
        min-width="140"
        show-overflow-tooltip
      >
        <template #default="{ row }">
          <span class="mono">{{ row.definitionKey }}</span>
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="90"
        fixed="right"
      >
        <template #default="{ row }">
          <el-button
            text
            type="primary"
            size="small"
            @click="openPreview(row)"
          >
            预览
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="previewVisible"
      :title="`流程图预览 - v${previewVersion}`"
      width="860px"
      append-to-body
      destroy-on-close
    >
      <div
        v-loading="previewLoading"
        class="preview-stage"
      >
        <BpmnCanvas
          v-if="previewXml"
          :xml="previewXml"
          :readonly="true"
        />
      </div>
    </el-dialog>
  </el-dialog>
</template>

<style scoped lang="scss">
.mono {
  font-family: monospace;
  font-size: 12px;
}

.preview-stage {
  height: 560px;
}
</style>
