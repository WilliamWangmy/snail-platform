<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';

import {
  getDefinitionXml,
  listModelDefinitions,
  type WorkflowDefinitionVO,
  type WorkflowModelVO,
} from '@/api/workflow/model';
import AppIcon from '@/components/AppIcon.vue';

import BpmnCanvas from '../designer/BpmnCanvas.vue';

const router = useRouter();

const visible = ref(false);
const loading = ref(false);
const model = ref<WorkflowModelVO | null>(null);
const definitions = ref<WorkflowDefinitionVO[]>([]);

const previewVisible = ref(false);
const previewVersion = ref<number | null>(null);
const previewXml = ref('');
const previewLoading = ref(false);

const currentVersion = computed(() => model.value?.deployedVersion ?? null);

async function open(target: WorkflowModelVO): Promise<void> {
  visible.value = true;
  model.value = target;
  definitions.value = [];
  loading.value = true;
  try {
    definitions.value = await listModelDefinitions(target.modelId);
  } finally {
    loading.value = false;
  }
}

async function openPreview(item: WorkflowDefinitionVO): Promise<void> {
  previewVisible.value = true;
  previewVersion.value = item.version;
  previewXml.value = '';
  previewLoading.value = true;
  try {
    previewXml.value = (await getDefinitionXml(item.definitionId)).xml;
  } catch {
    ElMessage.error('版本 XML 读取失败');
    previewVisible.value = false;
  } finally {
    previewLoading.value = false;
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
  if (!model.value) return;
  visible.value = false;
  void router.push(`/modeler/${model.value.modelId}/edit`);
}

defineExpose({ open });
</script>

<template>
  <el-dialog
    v-model="visible"
    class="flow-dialog"
    width="780px"
    align-center
    :show-close="false"
  >
    <template #header>
      <div class="dialog-head">
        <div class="dialog-head-copy">
          <div class="dialog-kicker">VERSION HISTORY</div>
          <h2 class="dialog-title">版本列表 · {{ model?.name ?? '' }}</h2>
          <p class="dialog-desc">
            流程标识 {{ model?.modelKey ?? '' }} · 当前发布指针 {{ model?.deployedVersion ? `v${model.deployedVersion}` : '—' }}
          </p>
        </div>
        <button
          class="icon-btn dialog-close"
          type="button"
          aria-label="关闭"
          @click="visible = false"
        >
          <AppIcon name="x" />
        </button>
      </div>
    </template>

    <el-alert
      class="flow-alert"
      type="info"
      :closable="false"
      show-icon
    >
      已发布版本为只读快照。预览会打开该版本部署时的 BPMN 原文，不受当前草稿修改影响。
    </el-alert>

    <div
      v-loading="loading"
      class="version-list"
      style="margin-top: 14px"
    >
      <div
        v-for="item in definitions"
        :key="item.definitionId"
        class="version-row"
      >
        <span class="version-no">{{ item.version }}</span>
        <div class="version-main">
          <b>
            {{ item.definitionName || model?.name }}
            <el-tag
              v-if="item.deployed"
              class="flow-tag version-current"
              type="success"
              disable-transitions
            >
              当前
            </el-tag>
            <el-tag
              v-else-if="item.suspended"
              class="flow-tag version-current"
              type="warning"
              disable-transitions
            >
              已挂起
            </el-tag>
          </b>
          <span class="version-key">{{ item.definitionKey }} · {{ item.deploymentId || item.definitionId }}</span>
        </div>
        <span
          class="version-state"
          :class="{ 'is-off': item.suspended }"
        >{{ item.suspended ? '已挂起' : item.deployed ? '在用版本' : '历史版本' }}</span>
        <div class="version-action">
          <el-button
            link
            size="small"
            @click="openPreview(item)"
          >
            <AppIcon name="eye" />预览
          </el-button>
        </div>
      </div>
    </div>

    <el-empty
      v-if="!loading && !definitions.length"
      class="flow-empty"
      description="暂无已发布版本"
    >
      <template #image>
        <span class="empty-icon"><AppIcon name="layers" /></span>
      </template>
      <p class="empty-hint">
        发布模型后，这里会保留每个版本的只读快照。
      </p>
    </el-empty>

    <template #footer>
      <div class="dialog-foot">
        <span class="foot-note">共 {{ definitions.length }} 个已发布版本</span>
        <el-button @click="visible = false">
          关闭
        </el-button>
        <el-button
          type="primary"
          @click="openDesigner"
        >
          打开设计器<AppIcon name="arrow-right" />
        </el-button>
      </div>
    </template>
  </el-dialog>

  <el-dialog
    v-model="previewVisible"
    class="flow-dialog preview-dialog"
    width="900px"
    align-center
    :show-close="false"
    append-to-body
  >
    <template #header>
      <div class="dialog-head">
        <div class="dialog-head-copy">
          <div class="dialog-kicker">DEFINITION XML · READ ONLY</div>
          <h2 class="dialog-title">流程图预览 · v{{ previewVersion ?? '—' }}</h2>
          <p class="dialog-desc">
            已部署定义快照 · {{ model?.modelKey ?? '' }} · {{ model?.name ?? '' }}
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

    <div
      v-loading="previewLoading"
      class="diagram-preview preview-stage"
    >
      <div class="preview-toolbar">
        <el-tag
          class="flow-tag"
          disable-transitions
        >
          V{{ previewVersion ?? '—' }} · READ ONLY
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

    <template #footer>
      <div class="dialog-foot">
        <span class="foot-note">只读预览 · 关闭后可继续查看其他版本</span>
        <el-button @click="previewVisible = false">
          关闭
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
.version-current {
  margin-left: 6px;
}

.version-key {
  font-family: var(--font-mono);
  font-size: 9.5px;
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

.flow-empty {
  padding: 24px 0 8px;
}

.preview-dialog :deep(.el-dialog__body) {
  padding: 0;
}

.preview-dialog .diagram-preview {
  border: 0;
  border-radius: 0;
}

.preview-stage {
  height: 480px;
}
</style>
