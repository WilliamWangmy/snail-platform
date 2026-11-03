<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import type { FormInstance, FormRules } from 'element-plus';
import { ElMessage } from 'element-plus';

import { createModel } from '@/api/workflow/model';
import { getTemplateXml, listTemplateOptions, type WorkflowTemplateOptionVO } from '@/api/workflow/template';
import AppIcon from '@/components/AppIcon.vue';

const emit = defineEmits<{ success: [modelId: number] }>();

const visible = ref(false);
const submitting = ref(false);
const formRef = ref<FormInstance>();

const form = reactive({
  modelKey: '',
  name: '',
  category: '',
  remark: '',
  source: 'blank' as 'blank' | 'template',
  templateId: undefined as number | undefined,
});

const rules: FormRules = {
  modelKey: [
    { required: true, message: '请输入模型标识', trigger: 'blur' },
    { pattern: /^[a-z][A-Za-z0-9_]{0,63}$/, message: '小写字母开头，仅字母/数字/下划线，最长 64 位', trigger: 'blur' },
  ],
  name: [{ required: true, message: '请输入模型名称', trigger: 'blur' }],
  templateId: [{ required: true, message: '请选择模板', trigger: 'change' }],
};

const templates = ref<WorkflowTemplateOptionVO[]>([]);
const templateXml = ref('');
const previewLoading = ref(false);

async function open(): Promise<void> {
  visible.value = true;
  Object.assign(form, { modelKey: '', name: '', category: '', remark: '', source: 'blank', templateId: undefined });
  templateXml.value = '';
  try {
    templates.value = await listTemplateOptions();
  } catch {
    templates.value = [];
  }
}

watch(
  () => form.templateId,
  async (id) => {
    templateXml.value = '';
    if (id) {
      previewLoading.value = true;
      try {
        templateXml.value = (await getTemplateXml(id)).xml;
      } finally {
        previewLoading.value = false;
      }
    }
  },
);

const submitPayload = computed(() => ({
  modelKey: form.modelKey,
  name: form.name,
  category: form.category || undefined,
  remark: form.remark || undefined,
  ...(form.source === 'template' ? { templateId: form.templateId } : {}),
}));

async function onSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) return;

  submitting.value = true;
  try {
    const modelId = await createModel(submitPayload.value);
    ElMessage.success('模型已创建，正在打开设计器');
    visible.value = false;
    emit('success', modelId);
  } finally {
    submitting.value = false;
  }
}

defineExpose({ open });
</script>

<template>
  <el-dialog
    v-model="visible"
    class="flow-dialog"
    width="720px"
    align-center
    :show-close="false"
  >
    <template #header>
      <div class="dialog-head">
        <div class="dialog-head-copy">
          <div class="dialog-kicker">
            NEW MODEL
          </div>
          <h2 class="dialog-title">
            新建流程模型
          </h2>
          <p class="dialog-desc">
            模型标识是流程在引擎中的唯一键，创建后不可修改。
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
      class="flow-alert flow-alert--warning"
      type="warning"
      :closable="false"
      show-icon
    >
      流程标识需以小写字母开头，仅允许字母、数字与下划线，最长 64 位；发布时后端会再次核对标识一致性。
    </el-alert>

    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-position="top"
      style="margin-top: 16px"
      @submit.prevent
    >
      <div class="form-grid">
        <el-form-item
          prop="modelKey"
          label="模型标识"
          required
        >
          <el-input
            v-model="form.modelKey"
            placeholder="如 expense_approval"
            maxlength="64"
          />
          <span class="field-hint">创建后不可修改</span>
        </el-form-item>
        <el-form-item
          prop="name"
          label="模型名称"
          required
        >
          <el-input
            v-model="form.name"
            placeholder="请输入模型名称"
            maxlength="64"
          />
        </el-form-item>
        <el-form-item label="分类">
          <el-input
            v-model="form.category"
            placeholder="选填，如 采购 / 财务"
            maxlength="64"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="form.remark"
            placeholder="选填，一句话说明用途"
            maxlength="200"
          />
        </el-form-item>
      </div>

      <div
        class="field"
        style="margin-top: 16px"
      >
        <span class="field-label">初始内容</span>
        <el-radio-group
          v-model="form.source"
          class="source-cards"
        >
          <el-radio
            value="blank"
            class="source-card"
            border
          >
            <span class="source-copy">
              <b>空白模型</b>
              <small>预置开始、审批、结束骨架，适合从零编排。</small>
            </span>
          </el-radio>
          <el-radio
            value="template"
            class="source-card"
            border
          >
            <span class="source-copy">
              <b>从模板落地</b>
              <small>复用平台模板并立即进入设计器调整。</small>
            </span>
          </el-radio>
        </el-radio-group>

        <div
          v-if="form.source === 'template'"
          class="template-stage"
        >
          <el-form-item
            label="选择模板"
            prop="templateId"
            style="margin-top: 12px"
          >
            <el-select
              v-model="form.templateId"
              style="width: 100%"
              placeholder="请选择模板"
              filterable
            >
              <el-option
                v-for="item in templates"
                :key="item.templateId"
                :label="item.name"
                :value="item.templateId"
              >
                <span>{{ item.name }}</span>
                <span class="template-option-key">{{ item.templateKey }}</span>
              </el-option>
            </el-select>
          </el-form-item>
          <div
            v-loading="previewLoading"
            class="template-preview"
          >
            <div class="template-preview-head">
              <span>TEMPLATE XML PREVIEW</span>
              <el-tag
                class="flow-tag"
                disable-transitions
              >
                READ ONLY
              </el-tag>
            </div>
            <pre><code>{{ templateXml || '选择模板后展示原文' }}</code></pre>
          </div>
        </div>
      </div>
    </el-form>

    <template #footer>
      <div class="dialog-foot">
        <span class="foot-note">创建后将直接进入 BPMN 设计器</span>
        <el-button @click="visible = false">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="submitting"
          @click="onSubmit"
        >
          创建并进入设计器<AppIcon name="arrow-right" />
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
.template-option-key {
  float: right;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-3);
}

.source-cards {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-top: 3px;
}

.source-cards :deep(.el-radio.source-card) {
  display: flex;
  height: auto;
  align-items: flex-start;
  margin: 0;
  padding: 13px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.02);
  white-space: normal;
}

.source-cards :deep(.el-radio.source-card.is-checked) {
  border-color: var(--teal-line);
  background: rgba(30, 201, 165, 0.065);
}

.source-cards :deep(.el-radio.source-card .el-radio__input) {
  margin-top: 2px;
}

.source-cards :deep(.el-radio.source-card .el-radio__label) {
  padding-left: 10px;
  color: var(--text-2);
  font-size: 11.5px;
}

.source-copy b {
  display: block;
  color: var(--text-1);
  font-size: 11.5px;
  font-weight: 500;
}

.source-copy small {
  display: block;
  margin-top: 3px;
  color: var(--text-3);
  font-size: 9.5px;
  line-height: 1.45;
}

.field-hint {
  display: block;
  margin-top: 6px;
}

@media (max-width: 560px) {
  .source-cards {
    grid-template-columns: 1fr;
  }
}
</style>
