<script setup lang="ts">
/**
 * 流程面板（点击画布空白 / process 根节点）：
 * 流程标识只读（= modelKey，发布时后端核对一致）、流程名称可编辑。
 */
import { computed } from 'vue';

import type { FlowElementLike, ModelingLike } from '../flowableExtension';

const props = defineProps<{
  element: FlowElementLike;
  modelKey: string;
  modeling: ModelingLike;
  revision: number;
}>();

const processId = computed(() => props.modelKey || String(props.element.businessObject.id ?? ''));
const processName = computed({
  get: () => {
    void props.revision;
    return String(props.element.businessObject.name ?? '');
  },
  set: (value: string) => props.modeling.updateProperties(props.element, { name: value }),
});
</script>

<template>
  <section class="inspector-section">
    <h3 class="inspector-section-title">
      基础信息
    </h3>
    <div class="field">
      <label class="field-label">流程标识</label>
      <el-input
        :model-value="processId"
        aria-label="流程标识"
        disabled
      />
      <span class="field-hint">与模型标识一致，发布时后端核对，不可修改</span>
    </div>
    <div class="field">
      <label class="field-label">流程名称</label>
      <el-input
        v-model="processName"
        aria-label="流程名称"
        maxlength="64"
        placeholder="请输入流程名称"
      />
    </div>
  </section>
</template>
