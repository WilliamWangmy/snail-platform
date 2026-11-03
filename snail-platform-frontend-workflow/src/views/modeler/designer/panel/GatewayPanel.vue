<script setup lang="ts">
/**
 * 网关面板（排他/并行）：只开放基础信息，标识只读（运行时按 id 定位），名称可编辑。
 * 条件表达式在连线的 SequenceFlowPanel 上配置，网关本身无可配项。
 */
import { computed } from 'vue';

import type { FlowElementLike, ModelingLike } from '../flowableExtension';

const props = defineProps<{
  element: FlowElementLike;
  modeling: ModelingLike;
  revision: number;
}>();

const elementId = computed(() => String(props.element.businessObject.id ?? ''));
const elementName = computed({
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
      <label class="field-label">网关标识</label>
      <el-input
        :model-value="elementId"
        aria-label="网关标识"
        disabled
      />
    </div>
    <div class="field">
      <label class="field-label">网关名称</label>
      <el-input
        v-model="elementName"
        aria-label="网关名称"
        maxlength="64"
        placeholder="如：请假天数判断"
      />
    </div>
    <p class="field-hint">
      走向由网关出线的条件决定，选中连线后在「流转类型 / 高级模式」里配置。
    </p>
  </section>
</template>
