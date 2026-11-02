<script setup lang="ts">
/**
 * 用户任务面板：审批模式两维设置（单人绑定 / 多人执行方式+通过条件）、
 * 通知方式、抄送人、办理按钮设置。切换保留/清空规则见设计文档 5.3 定稿项。
 * 本组件以 :key="element.id" 挂载，选中切换即重挂载，状态从 businessObject 一次性回显。
 */
import { computed, ref } from 'vue';
import { ElMessageBox } from 'element-plus';

import {
  ACTION_BUTTONS,
  DEFAULT_ACTION_BUTTONS,
  DEFAULT_COLLECTION,
  ELEMENT_VARIABLE,
  EXEC_MODE_LABELS,
  NOTIFY_TYPES,
  buildCompletionCondition,
  type ApproveMode,
  type ExecMode,
  type PassCondition,
} from '../flowType';
import {
  getMultiInstance,
  isSequentialMultiInstance,
  readFlowableAttr,
  writeCompletionCondition,
  writeFlowableAttrs,
  type FlowElementLike,
} from '../flowableExtension';
import { useRoleRemoteSearch, useUserRemoteSearch } from './useRemoteSearch';

interface BpmnServices {
  modeling: {
    updateProperties: (el: unknown, props: Record<string, unknown>) => void;
    updateModdleProperties: (el: unknown, moddleEl: unknown, props: Record<string, unknown>) => void;
  };
  bpmnFactory: { create: (type: string, props?: Record<string, unknown>) => Record<string, unknown> };
}

const props = defineProps<{
  element: FlowElementLike;
  services: BpmnServices;
}>();

/* ------------------------------ 状态派生（自 businessObject 回显） ------------------------------ */

const multiInstance = computed(() => getMultiInstance(props.element));
const approveMode = computed<ApproveMode>(() => (multiInstance.value ? 'multi' : 'single'));
const execMode = computed<ExecMode>(() => {
  if (!multiInstance.value) return 'competitive';
  return isSequentialMultiInstance(multiInstance.value) ? 'serial' : 'parallel';
});

const isCompetitive = computed(() => approveMode.value === 'multi' && execMode.value === 'competitive');
const isMultiInstance = computed(() => approveMode.value === 'multi' && execMode.value !== 'competitive');

/* ------------------------------------ 单人审批人绑定 ------------------------------------ */

const assignee = computed({
  get: () => readFlowableAttr(props.element, 'assignee'),
  set: (v: string) => writeFlowableAttrs(props.services.modeling, props.element, { assignee: v }),
});

const candidateGroups = computed({
  get: () => readFlowableAttr(props.element, 'candidateGroups').split(',').filter(Boolean),
  set: (v: string[]) =>
    writeFlowableAttrs(props.services.modeling, props.element, { candidateGroups: v.join(',') }),
});

const bindingMode = computed(() =>
  assignee.value ? 'assignee' : candidateGroups.value.length > 0 ? 'groups' : 'none',
);

function switchBinding(mode: 'assignee' | 'groups' | 'none'): void {
  writeFlowableAttrs(props.services.modeling, props.element, {
    assignee: mode === 'assignee' ? assignee.value : '',
    candidateGroups: mode === 'groups' ? candidateGroups.value.join(',') : '',
  });
}

/* ------------------------------------ 执行方式切换 ------------------------------------ */

async function confirmLoss(message: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, '切换确认', {
      type: 'warning',
      confirmButtonText: '切换',
      cancelButtonText: '取消',
    });
    return true;
  } catch {
    return false;
  }
}

function hasMultiConfig(): boolean {
  return Boolean(multiInstance.value || readFlowableAttr(props.element, 'candidateUsers'));
}

function hasSingleConfig(): boolean {
  return Boolean(readFlowableAttr(props.element, 'assignee') || readFlowableAttr(props.element, 'candidateGroups'));
}

async function switchApproveMode(mode: ApproveMode): Promise<void> {
  if (mode === approveMode.value) return;

  if (mode === 'multi') {
    if (hasSingleConfig() && !(await confirmLoss('切换为多人审批将清除已配置的审批人绑定，是否继续？'))) return;
    writeFlowableAttrs(props.services.modeling, props.element, {
      assignee: '',
      candidateGroups: '',
      candidateUsers: '',
      collection: '',
      elementVariable: '',
    });
    enterMultiInstance();
    return;
  }

  // 多人 → 单人：清理 multiInstance 与竞争专属属性，审批人需重新绑定（黄条提示）
  if (hasMultiConfig() && !(await confirmLoss('切换为单人审批将清除多人审批配置，是否继续？'))) return;
  writeFlowableAttrs(props.services.modeling, props.element, {
    assignee: '',
    candidateUsers: '',
    collection: '',
    elementVariable: '',
  });
  props.services.modeling.updateProperties(props.element, { loopCharacteristics: undefined });
}

async function switchExecMode(mode: ExecMode): Promise<void> {
  if (mode === execMode.value) return;
  const mi = multiInstance.value;

  if (mode === 'competitive') {
    if (mi && !(await confirmLoss('切换为竞争（抢签）将移除会签配置，是否继续？'))) return;
    props.services.modeling.updateProperties(props.element, { loopCharacteristics: undefined });
    writeFlowableAttrs(props.services.modeling, props.element, {
      assignee: '',
      collection: '',
      elementVariable: '',
      candidateUsers: '',
    });
    return;
  }

  if (isCompetitive.value) {
    if (!(await confirmLoss(`切换为${EXEC_MODE_LABELS[mode]}将清除抢签人员配置，是否继续？`))) return;
    writeFlowableAttrs(props.services.modeling, props.element, {
      candidateUsers: '',
      collection: DEFAULT_COLLECTION,
      elementVariable: ELEMENT_VARIABLE,
      assignee: `\${${ELEMENT_VARIABLE}}`,
    });
    enterMultiInstance();
    setSequential(mode === 'serial');
    return;
  }

  // 并行 ↔ 串行：仅改 isSequential，collection/completionCondition 直接复用（静默执行）
  if (mi) {
    setSequential(mode === 'serial');
  }
}

function enterMultiInstance(): void {
  const mi = props.services.bpmnFactory.create('bpmn:MultiInstanceLoopCharacteristics', {
    isSequential: true,
    collection: DEFAULT_COLLECTION,
    elementVariable: ELEMENT_VARIABLE,
  });
  props.services.modeling.updateProperties(props.element, { loopCharacteristics: mi });
}

function setSequential(value: boolean): void {
  const mi = multiInstance.value;
  if (mi) {
    props.services.modeling.updateModdleProperties(props.element, mi, { isSequential: value });
  }
}

/* ------------------------------------ 通过条件 ------------------------------------ */

const collection = computed({
  get: () => String(multiInstance.value?.collection ?? DEFAULT_COLLECTION),
  set: (v: string) => {
    if (multiInstance.value) {
      props.services.modeling.updateModdleProperties(props.element, multiInstance.value, { collection: v });
    }
  },
});

const RATIO_PATTERN = /^\$\{100 \* nrOfCompletedInstances >= \d+ \* nrOfInstances\}$/;
const VOTES_PATTERN = /^\$\{nrOfCompletedInstances >= (\d+)\}$/;

const passCondition = computed<PassCondition>(() => {
  const cond = multiInstance.value?.completionCondition as { body?: string } | undefined;
  const body = String(cond?.body ?? '').trim();
  if (!body) return 'all';
  if (body === '${nrOfCompletedInstances >= 1}') return 'any';
  if (RATIO_PATTERN.test(body)) return 'ratio';
  if (body === '${approved == false}') return 'veto';
  return VOTES_PATTERN.test(body) ? 'votes' : 'all';
});

const conditionValue = ref(60);

function onPassCondition(kind: PassCondition): void {
  if (!multiInstance.value) return;
  const expr = buildCompletionCondition(kind, conditionValue.value);
  writeCompletionCondition(props.services.modeling, props.services.bpmnFactory, props.element, multiInstance.value, expr);
}

function onConditionValueChange(): void {
  const current = passCondition.value;
  if (current === 'ratio' || current === 'votes') {
    onPassCondition(current);
  }
}

/* ------------------------------------ 通知 / 抄送 / 按钮 ------------------------------------ */

const notifyType = computed({
  get: () => readFlowableAttr(props.element, 'notifyType'),
  set: (v: string) => writeFlowableAttrs(props.services.modeling, props.element, { notifyType: v || '' }),
});

const ccUsers = computed({
  get: () => readFlowableAttr(props.element, 'ccUsers').split(',').filter(Boolean),
  set: (v: string[]) => writeFlowableAttrs(props.services.modeling, props.element, { ccUsers: v.join(',') }),
});

const ccGroups = computed({
  get: () => readFlowableAttr(props.element, 'ccGroups').split(',').filter(Boolean),
  set: (v: string[]) => writeFlowableAttrs(props.services.modeling, props.element, { ccGroups: v.join(',') }),
});

const selectedButtons = computed({
  get: () => {
    const raw = readFlowableAttr(props.element, 'actionButtons');
    const codes = raw ? raw.split(',').filter(Boolean) : [DEFAULT_ACTION_BUTTONS];
    return [...new Set([...codes, 'approve'])];
  },
  set: (v: string[]) => {
    const codes = [...new Set([...v, 'approve'])];
    writeFlowableAttrs(props.services.modeling, props.element, { actionButtons: codes.join(',') });
  },
});

/* ------------------------------------ 远程搜索数据源（每实例独立） ------------------------------------ */

const assigneeSearch = useUserRemoteSearch(readFlowableAttr(props.element, 'assignee') ? [readFlowableAttr(props.element, 'assignee')] : []);
const groupSearch = useRoleRemoteSearch(readFlowableAttr(props.element, 'candidateGroups').split(',').filter(Boolean));
const candidateUserSearch = useUserRemoteSearch(readFlowableAttr(props.element, 'candidateUsers').split(',').filter(Boolean));
const ccUserSearch = useUserRemoteSearch(ccUsers.value);
const ccGroupSearch = useRoleRemoteSearch(ccGroups.value);

const candidateUsers = computed(() => readFlowableAttr(props.element, 'candidateUsers').split(',').filter(Boolean));
</script>

<template>
  <div class="panel">
    <h4 class="panel-title">
      用户任务
    </h4>
    <el-form
      label-position="top"
      size="default"
    >
      <el-form-item label="节点标识">
        <el-input
          :model-value="element.id"
          disabled
        />
      </el-form-item>
      <el-form-item label="节点名称">
        <el-input
          :model-value="String(element.businessObject.name ?? '')"
          maxlength="64"
          @update:model-value="(v: string) => services.modeling.updateProperties(element, { name: v })"
        />
      </el-form-item>

      <!-- 维度一：审批模式 -->
      <el-form-item label="审批模式">
        <el-radio-group
          :model-value="approveMode"
          @update:model-value="(v: string | number | boolean | undefined) => switchApproveMode(v as ApproveMode)"
        >
          <el-radio-button value="single">
            单人审批
          </el-radio-button>
          <el-radio-button value="multi">
            多人审批
          </el-radio-button>
        </el-radio-group>
        <div class="form-tip">
          单人：节点生成一条任务；多人：按执行方式生成多条任务
        </div>
      </el-form-item>

      <!-- 单人：审批人绑定 -->
      <div
        v-if="approveMode === 'single'"
        class="panel-section"
      >
        <p class="section-title">
          审批人绑定
        </p>
        <el-radio-group
          :model-value="bindingMode"
          @update:model-value="(v: string | number | boolean | undefined) => switchBinding(v as 'assignee' | 'groups' | 'none')"
        >
          <el-radio value="assignee">
            指定人员
          </el-radio>
          <el-radio value="groups">
            候选组（角色）
          </el-radio>
          <el-radio value="none">
            未绑定
          </el-radio>
        </el-radio-group>

        <el-select
          v-if="bindingMode === 'assignee'"
          class="block"
          :model-value="assignee"
          filterable
          remote
          clearable
          reserve-keyword
          placeholder="远程搜索用户"
          :remote-method="assigneeSearch.remoteMethod"
          :loading="false"
          @update:model-value="(v: string) => (assignee = v ?? '')"
        >
          <el-option
            v-for="o in assigneeSearch.options.value"
            :key="o.value"
            :label="o.label"
            :value="o.value"
          />
        </el-select>

        <el-select
          v-if="bindingMode === 'groups'"
          class="block"
          :model-value="candidateGroups"
          multiple
          filterable
          remote
          reserve-keyword
          placeholder="远程搜索角色"
          :remote-method="groupSearch.remoteMethod"
          @update:model-value="(v: string[]) => (candidateGroups = v)"
        >
          <el-option
            v-for="o in groupSearch.options.value"
            :key="o.value"
            :label="o.label"
            :value="o.value"
          />
        </el-select>

        <div
          v-if="bindingMode === 'none'"
          class="panel-warning"
        >
          该任务无审批人，发布不会被拦截，但流程无法流转
        </div>
      </div>

      <!-- 多人：执行方式 -->
      <div
        v-if="approveMode === 'multi'"
        class="panel-section"
      >
        <p class="section-title">
          执行方式
        </p>
        <el-radio-group
          :model-value="execMode"
          @update:model-value="(v: string | number | boolean | undefined) => switchExecMode(v as ExecMode)"
        >
          <el-radio-button
            v-for="(label, key) in EXEC_MODE_LABELS"
            :key="key"
            :value="key"
          >
            {{ label }}
          </el-radio-button>
        </el-radio-group>

        <template v-if="isCompetitive">
          <div class="form-tip block-tip">
            单条任务多人可见，谁先签收谁处理，一人完成即结束
          </div>
          <el-select
            class="block"
            :model-value="candidateUsers"
            multiple
            filterable
            remote
            reserve-keyword
            placeholder="选择抢签人员"
            :remote-method="candidateUserSearch.remoteMethod"
            @update:model-value="(v: string[]) => writeFlowableAttrs(services.modeling, element, { candidateUsers: v.join(',') })"
          >
            <el-option
              v-for="o in candidateUserSearch.options.value"
              :key="o.value"
              :label="o.label"
              :value="o.value"
            />
          </el-select>
        </template>

        <template v-if="isMultiInstance">
          <el-form-item
            label="人员集合变量"
            class="block-item"
          >
            <el-input
              :model-value="collection"
              maxlength="64"
              @update:model-value="(v: string) => (collection = v)"
            />
            <div class="form-tip">
              发起流程时需传入该变量（用户标识集合）
            </div>
          </el-form-item>
        </template>
      </div>

      <!-- 多人（并行/串行）：通过条件 -->
      <div
        v-if="isMultiInstance"
        class="panel-section"
      >
        <p class="section-title">
          通过条件
        </p>
        <el-radio-group
          :model-value="passCondition"
          @update:model-value="(v: string | number | boolean | undefined) => onPassCondition(v as PassCondition)"
        >
          <el-radio value="all">
            全员通过
          </el-radio>
          <el-radio value="any">
            一人通过即结束
          </el-radio>
          <el-radio value="ratio">
            达到比例
          </el-radio>
          <el-radio value="votes">
            达到票数
          </el-radio>
          <el-radio value="veto">
            一票否决
          </el-radio>
        </el-radio-group>

        <el-input-number
          v-if="passCondition === 'ratio'"
          class="block"
          :model-value="conditionValue"
          :min="1"
          :max="100"
          @change="onConditionValueChange"
        />
        <div
          v-if="passCondition === 'ratio'"
          class="form-tip"
        >
          达到 X% 即通过
        </div>

        <el-input-number
          v-if="passCondition === 'votes'"
          class="block"
          :model-value="conditionValue"
          :min="1"
          @change="onConditionValueChange"
        />
        <div
          v-if="passCondition === 'votes'"
          class="form-tip"
        >
          达到 N 票即通过
        </div>

        <div
          v-if="passCondition === 'veto'"
          class="form-tip"
        >
          任一实例办理为驳回即终止剩余实例，流程走驳回线
        </div>
      </div>

      <!-- 通知方式 -->
      <div class="panel-section">
        <p class="section-title">
          通知方式
        </p>
        <el-radio-group
          :model-value="notifyType"
          @update:model-value="(v: string | number | boolean | undefined) => (notifyType = String(v ?? ''))"
        >
          <el-radio
            v-for="n in NOTIFY_TYPES"
            :key="n.code"
            :value="n.code"
          >
            {{ n.label }}{{ n.pending ? '（待后端支持）' : '' }}
          </el-radio>
        </el-radio-group>
      </div>

      <!-- 抄送人 -->
      <div class="panel-section">
        <p class="section-title">
          抄送人
        </p>
        <el-select
          class="block"
          :model-value="ccUsers"
          multiple
          filterable
          remote
          reserve-keyword
          placeholder="指定人员（多选）"
          :remote-method="ccUserSearch.remoteMethod"
          @update:model-value="(v: string[]) => (ccUsers = v)"
        >
          <el-option
            v-for="o in ccUserSearch.options.value"
            :key="o.value"
            :label="o.label"
            :value="o.value"
          />
        </el-select>
        <el-select
          class="block"
          :model-value="ccGroups"
          multiple
          filterable
          remote
          reserve-keyword
          placeholder="候选组（角色，多选）"
          :remote-method="ccGroupSearch.remoteMethod"
          @update:model-value="(v: string[]) => (ccGroups = v)"
        >
          <el-option
            v-for="o in ccGroupSearch.options.value"
            :key="o.value"
            :label="o.label"
            :value="o.value"
          />
        </el-select>
      </div>

      <!-- 办理按钮设置 -->
      <div class="panel-section">
        <p class="section-title">
          办理按钮设置
        </p>
        <el-checkbox-group
          :model-value="selectedButtons"
          @update:model-value="(v: Array<string | number | boolean>) => (selectedButtons = v.map(String))"
        >
          <el-checkbox
            v-for="b in ACTION_BUTTONS"
            :key="b.code"
            :value="b.code"
            :disabled="b.fixed"
          >
            {{ b.label }}{{ b.pending ? '（待后端支持）' : '' }}
          </el-checkbox>
        </el-checkbox-group>
        <div class="form-tip">
          审批恒定不可取消；标注"待后端支持"的按钮任务中心暂不渲染
        </div>
      </div>
    </el-form>
  </div>
</template>

<style scoped lang="scss">
@use './panel-base' as *;

.block {
  width: 100%;
  margin-top: 6px;
}

.block-item {
  margin-top: 10px;
}

.block-tip {
  margin-top: 8px;
}
</style>
