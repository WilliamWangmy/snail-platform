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
}>();

const processId = computed(() => props.modelKey || String(props.element.businessObject.id ?? ''));
const processName = computed({
  get: () => String(props.element.businessObject.name ?? ''),
  set: (value: string) => props.modeling.updateProperties(props.element, { name: value }),
});
</script>

<template>
  <div class="panel">
    <h4 class="panel-title">
      流程
    </h4>
    <el-form
      label-position="top"
      size="default"
    >
      <el-form-item label="流程标识">
        <el-input
          :model-value="processId"
          disabled
        />
        <div class="form-tip">
          与模型标识一致，发布时后端核对，不可修改
        </div>
      </el-form-item>
      <el-form-item label="流程名称">
        <el-input
          v-model="processName"
          placeholder="请输入流程名称"
          maxlength="64"
        />
      </el-form-item>
    </el-form>
  </div>
</template>

<style scoped lang="scss">
@use './panel-base' as *;
</style>
