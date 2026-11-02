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
}>();

/** 来源为网关时才显示条件区 */
const fromGateway = computed(() => Boolean(props.element.source?.type?.includes('Gateway')));

const name = computed({
  get: () => String(props.element.businessObject.name ?? ''),
  set: (v: string) => props.services.modeling.updateProperties(props.element, { name: v }),
});

const conditionBody = computed(() => readConditionBody(props.element));

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
  <div class="panel">
    <h4 class="panel-title">
      连线
    </h4>
    <el-form
      label-position="top"
      size="default"
    >
      <el-form-item label="连线标识">
        <el-input
          :model-value="element.id"
          disabled
        />
      </el-form-item>
      <el-form-item label="连线名称">
        <el-input
          :model-value="name"
          maxlength="64"
          placeholder="如：驳回"
          @update:model-value="(v: string) => (name = v)"
        />
      </el-form-item>

      <!-- 流转类型（仅网关出线） -->
      <div
        v-if="fromGateway"
        class="panel-section"
      >
        <p class="section-title">
          流转类型
        </p>
        <el-radio-group
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
        <div class="form-tip">
          正常提交写入 ${approved}；驳回写入 ${!approved} 并渲染红色虚线。语义与任务办理动作绑定：通过=true、驳回=false
        </div>

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
            type="textarea"
            :rows="2"
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
      </div>
    </el-form>
  </div>
</template>

<style scoped lang="scss">
@use './panel-base' as *;

.adv-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.cond-preview {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-family: monospace;
}

.cond-error {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-color-danger);
}
</style>
