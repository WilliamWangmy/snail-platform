<script setup lang="ts">
/**
 * 开始/结束事件面板：只开放基础信息，标识只读（运行时按 id 定位），名称可编辑。
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
      <label class="field-label">节点标识</label>
      <el-input
        :model-value="elementId"
        aria-label="节点标识"
        disabled
      />
    </div>
    <div class="field">
      <label class="field-label">节点名称</label>
      <el-input
        v-model="elementName"
        aria-label="节点名称"
        maxlength="64"
        placeholder="请输入节点名称"
      />
    </div>
  </section>
</template>
