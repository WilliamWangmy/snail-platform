<script setup lang="ts">
/**
 * 新建模型对话框：字段镜像 WorkflowModelCreateRequest，来源支持空白 / 模板落地（模板可先预览 XML）。
 */
import { computed, reactive, ref, watch } from 'vue';
import type { FormInstance, FormRules } from 'element-plus';
import { ElMessage } from 'element-plus';

import { createModel, type WorkflowModelVO } from '@/api/workflow/model';
import { getTemplateXml, listTemplateOptions, type WorkflowTemplateOptionVO } from '@/api/workflow/template';

const emit = defineEmits<{ success: [model: WorkflowModelVO] }>();

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

/* 模板数据 */
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
  await formRef.value?.validate().catch(() => Promise.reject());
  submitting.value = true;
  try {
    const model = await createModel(submitPayload.value);
    ElMessage.success('模型已创建');
    visible.value = false;
    emit('success', model);
  } finally {
    submitting.value = false;
  }
}

defineExpose({ open });
</script>

<template>
  <el-dialog
    v-model="visible"
    title="新建流程模型"
    width="560px"
    destroy-on-close
  >
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="88px"
    >
      <el-form-item
        label="模型标识"
        prop="modelKey"
      >
        <el-input
          v-model="form.modelKey"
          placeholder="小写字母开头，如 expense_approval"
          maxlength="64"
        />
      </el-form-item>
      <el-form-item
        label="模型名称"
        prop="name"
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
          placeholder="选填"
          maxlength="64"
        />
      </el-form-item>
      <el-form-item label="备注">
        <el-input
          v-model="form.remark"
          type="textarea"
          :rows="2"
          placeholder="选填"
          maxlength="200"
        />
      </el-form-item>
      <el-form-item label="初始内容">
        <el-radio-group v-model="form.source">
          <el-radio value="blank">
            空白模型
          </el-radio>
          <el-radio value="template">
            从模板落地
          </el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item
        v-if="form.source === 'template'"
        label="选择模板"
        prop="templateId"
      >
        <el-select
          v-model="form.templateId"
          class="block"
          placeholder="请选择模板"
          filterable
        >
          <el-option
            v-for="t in templates"
            :key="t.templateId"
            :label="t.name"
            :value="t.templateId"
          >
            <span>{{ t.name }}</span>
            <span class="tpl-key">{{ t.templateKey }}</span>
          </el-option>
        </el-select>
      </el-form-item>
    </el-form>

    <div
      v-if="form.source === 'template'"
      v-loading="previewLoading"
      class="xml-preview"
    >
      <div class="xml-preview-title">
        模板 XML 预览
      </div>
      <pre class="xml-preview-body">{{ templateXml || '选择模板后展示原文' }}</pre>
    </div>

    <template #footer>
      <el-button @click="visible = false">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="submitting"
        @click="onSubmit"
      >
        创建并进入设计器
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
.block {
  width: 100%;
}

.tpl-key {
  float: right;
  font-family: monospace;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.xml-preview {
  margin: 0 0 8px 88px;
}

.xml-preview-title {
  margin-bottom: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.xml-preview-body {
  max-height: 220px;
  margin: 0;
  padding: 8px;
  overflow: auto;
  font-family: monospace;
  font-size: 12px;
  line-height: 1.5;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  background: var(--el-fill-color-light);
}
</style>
