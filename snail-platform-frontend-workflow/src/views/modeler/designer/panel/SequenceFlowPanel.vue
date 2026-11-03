<script setup lang="ts">
/**
 * 连线面板：常规编辑 + 流转类型（正常提交/驳回，仅网关出线显示）+ 高级模式手写条件。
 * 类型选择即自动写入条件表达式；驳回线以 name="驳回" 持久化（画布渲染红色虚线）。
 */
import { computed, ref } from 'vue';

import { FLOW_CONDITIONS, FLOW_TYPE_LABELS, detectFlowType, type FlowType } from '../flowType';
import { readConditionBody, writeConditionBody, type ModelingLike } from '../flowableExtension';

const props = defineProps<{
  element: { businessObject: Record<string, unknown>; id?: string; source?: { type?: string } };
  services: {
    modeling: ModelingLike;
    bpmnFactory: { create: (type: string, props?: Record<string, unknown>) => Record<string, unknown> };
  };
  revision: number;
}>();

/** 来源为网关时才显示条件区 */
const fromGateway = computed(() => {
  void props.revision;
  return Boolean(props.element.source?.type?.includes('Gateway'));
});

const name = computed({
  get: () => {
    void props.revision;
    return String(props.element.businessObject.name ?? '');
  },
  set: (v: string) => props.services.modeling.updateProperties(props.element, { name: v }),
});

const conditionBody = computed(() => {
  void props.revision;
  return readConditionBody(props.element);
});

const flowType = computed<FlowType>(() =>
  detectFlowType(name.value, conditionBody.value),
);

function switchFlowType(type: FlowType): void {
  writeConditionBody(props.services.modeling, props.services.bpmnFactory, props.element, FLOW_CONDITIONS[type]);
  if (type === 'reject') {
    props.services.modeling.updateProperties(props.element, { name: '驳回' });
  } else if (name.value === '驳回') {
    props.services.modeling.updateProperties(props.element, { name: '' });
  }
}

/* 高级模式：手写表达式，实时校验（整体 ${...} + 标量字符白名单） */
const advanced = ref(false);
const EXPRESSION_OPERATORS = "+-*/%<>=!&|~?:'\"";

const conditionInput = ref('');
const conditionError = ref('');

function openAdvanced(): void {
  conditionInput.value = conditionBody.value;
  conditionError.value = '';
  advanced.value = true;
}

function onConditionInput(value: string): void {
  conditionInput.value = value;
  const body = value.trim();
  if (!body) {
    conditionError.value = '';
    return;
  }
  if (!body.startsWith('${') || !body.endsWith('}')) {
    conditionError.value = '条件表达式必须形如 ${...}';
    return;
  }
  const inner = body.slice(2, -1);
  const ok = [...inner].every(
    (c) => c === '_' || c === ' ' || /[A-Za-z0-9]/.test(c) || EXPRESSION_OPERATORS.includes(c),
  );
  conditionError.value = ok ? '' : '表达式只允许标量变量与运算符（禁止 . ( ) [ ] , # @）';
}

function applyAdvanced(): void {
  if (conditionError.value) return;
  const body = conditionInput.value.trim();
  writeConditionBody(
    props.services.modeling,
    props.services.bpmnFactory,
    props.element,
    body || undefined,
  );
  advanced.value = false;
}
</script>

<template>
  <div class="flow-panel">
    <section class="inspector-section">
      <h3 class="inspector-section-title">
        基础信息
      </h3>
      <div class="field">
        <label class="field-label">连线标识</label>
        <el-input
          :model-value="element.id"
          aria-label="连线标识"
          disabled
        />
      </div>
      <div class="field">
        <label class="field-label">连线名称</label>
        <el-input
          :model-value="name"
          aria-label="连线名称"
          maxlength="64"
          placeholder="如：驳回"
          @update:model-value="(v: string) => (name = v)"
        />
      </div>
    </section>

    <section
      v-if="fromGateway"
      class="inspector-section"
    >
      <h3 class="inspector-section-title">
        流转类型
      </h3>
      <el-radio-group
        class="flow-choices"
        :model-value="flowType"
        @update:model-value="(v: string | number | boolean | undefined) => switchFlowType(v as FlowType)"
      >
        <el-radio-button
          v-for="(label, key) in FLOW_TYPE_LABELS"
          :key="key"
          :value="key"
        >
          {{ label }}
        </el-radio-button>
      </el-radio-group>
      <p class="field-hint section-hint">
        正常提交写入 ${approved}；驳回写入 ${!approved} 并渲染红色虚线。语义与任务办理动作绑定：通过=true、驳回=false。
      </p>

      <div
        v-if="!advanced"
        class="adv-row"
      >
        <el-button
          size="small"
          text
          type="primary"
          @click="openAdvanced"
        >
          高级模式：手写表达式
        </el-button>
        <span
          v-if="conditionBody"
          class="cond-preview"
        >{{ conditionBody }}</span>
      </div>
      <template v-else>
        <el-input
          v-model="conditionInput"
          class="field-gap"
          type="textarea"
          :rows="2"
          aria-label="条件表达式"
          placeholder="${approved} 或 ${amount > 100}"
          @input="onConditionInput"
        />
        <div
          v-if="conditionError"
          class="cond-error"
        >
          {{ conditionError }}
        </div>
        <div class="adv-row">
          <el-button
            size="small"
            type="primary"
            :disabled="Boolean(conditionError)"
            @click="applyAdvanced"
          >
            应用
          </el-button>
          <el-button
            size="small"
            @click="advanced = false"
          >
            取消
          </el-button>
        </div>
      </template>
    </section>
  </div>
</template>

<style scoped lang="scss">
.field-gap {
  margin-top: 9px;
}

.section-hint {
  margin-top: 8px;
}

.adv-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 9px;
}

.cond-preview {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--text-3);
  word-break: break-all;
}

.cond-error {
  margin-top: 4px;
  font-size: 10.5px;
  color: var(--danger-bright);
}
</style>
