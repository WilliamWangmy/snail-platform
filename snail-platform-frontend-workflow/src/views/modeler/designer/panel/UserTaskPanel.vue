<script setup lang="ts">
/**
 * 用户任务面板：审批模式两维设置（单人绑定 / 多人执行方式+通过条件）、
 * 通知方式、抄送人、办理按钮设置。切换保留/清空规则见设计文档 5.3 定稿项。
 * 本组件以 :key="element.id" 挂载，选中切换即重挂载，状态从 businessObject 一次性回显。
 */
import { computed, ref, watch } from 'vue';
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
  revision: number;
}>();

const taskName = computed({
  get: () => {
    void props.revision;
    return String(props.element.businessObject.name ?? '');
  },
  set: (value: string) => props.services.modeling.updateProperties(props.element, { name: value }),
});

/* 节点标识：失焦/回车提交；非法格式不落盘并回显错误（提交后面板按新 id 重挂载） */
const ID_PATTERN = /^[A-Za-z_][A-Za-z0-9_.-]*$/;

const elementId = ref(String(props.element.businessObject.id ?? ''));
const elementIdError = ref('');

watch(() => props.revision, () => {
  elementId.value = String(props.element.businessObject.id ?? '');
});

function commitElementId(): void {
  const current = String(props.element.businessObject.id ?? '');
  const next = elementId.value.trim();
  elementId.value = next;
  elementIdError.value = '';
  if (!next || next === current) return;
  if (!ID_PATTERN.test(next)) {
    elementIdError.value = '需以字母或下划线开头，仅可含字母、数字、点、下划线、连字符';
    elementId.value = current;
    return;
  }
  props.services.modeling.updateProperties(props.element, { id: next });
}

const APPROVE_MODE_OPTIONS = [
  { label: '单人审批', value: 'single' },
  { label: '多人审批', value: 'multi' },
];

/* ------------------------------ 状态派生（自 businessObject 回显） ------------------------------ */

// bpmn 业务对象非响应式：getter 读取 revision 建立依赖，画布内/撤销的改动后面板才会重算
const multiInstance = computed(() => {
  void props.revision;
  return getMultiInstance(props.element);
});
const approveMode = computed<ApproveMode>(() => (multiInstance.value ? 'multi' : 'single'));
const execMode = computed<ExecMode>(() => {
  if (!multiInstance.value) return 'competitive';
  return isSequentialMultiInstance(multiInstance.value) ? 'serial' : 'parallel';
});

const isCompetitive = computed(() => approveMode.value === 'multi' && execMode.value === 'competitive');
const isMultiInstance = computed(() => approveMode.value === 'multi' && execMode.value !== 'competitive');

/* ------------------------------------ 单人审批人绑定 ------------------------------------ */

const assignee = computed({
  get: () => {
    void props.revision;
    return readFlowableAttr(props.element, 'assignee');
  },
  set: (v: string) => writeFlowableAttrs(props.services.modeling, props.element, { assignee: v }),
});

const candidateGroups = computed({
  get: () => {
    void props.revision;
    return readFlowableAttr(props.element, 'candidateGroups').split(',').filter(Boolean);
  },
  set: (v: string[]) =>
    writeFlowableAttrs(props.services.modeling, props.element, { candidateGroups: v.join(',') }),
});

// 二选一：点选即锁定界面态（两项都为空时派生值是 none，否则点选无反馈）；有值一侧切换时清掉另一侧
const bindingChoice = ref<'assignee' | 'groups' | null>(null);
const bindingMode = computed<'assignee' | 'groups' | 'none'>(() => {
  if (bindingChoice.value) return bindingChoice.value;
  return assignee.value ? 'assignee' : candidateGroups.value.length > 0 ? 'groups' : 'none';
});

function switchBinding(mode: 'assignee' | 'groups'): void {
  bindingChoice.value = mode;
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
  get: () => {
    void props.revision;
    return String(multiInstance.value?.collection ?? DEFAULT_COLLECTION);
  },
  set: (v: string) => {
    if (multiInstance.value) {
      props.services.modeling.updateModdleProperties(props.element, multiInstance.value, { collection: v });
    }
  },
});

const RATIO_PATTERN = /^\$\{100 \* nrOfCompletedInstances >= \d+ \* nrOfInstances\}$/;
const VOTES_PATTERN = /^\$\{nrOfCompletedInstances >= (\d+)\}$/;

const passCondition = computed<PassCondition>(() => {
  void props.revision;
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

const notifyTypes = computed({
  get: () => {
    void props.revision;
    return readFlowableAttr(props.element, 'notifyType').split(',').filter(Boolean);
  },
  set: (v: string[]) => writeFlowableAttrs(props.services.modeling, props.element, { notifyType: v.join(',') }),
});

const ccUsers = computed({
  get: () => {
    void props.revision;
    return readFlowableAttr(props.element, 'ccUsers').split(',').filter(Boolean);
  },
  set: (v: string[]) => writeFlowableAttrs(props.services.modeling, props.element, { ccUsers: v.join(',') }),
});

const ccGroups = computed({
  get: () => {
    void props.revision;
    return readFlowableAttr(props.element, 'ccGroups').split(',').filter(Boolean);
  },
  set: (v: string[]) => writeFlowableAttrs(props.services.modeling, props.element, { ccGroups: v.join(',') }),
});

const selectedButtons = computed({
  get: () => {
    void props.revision;
    const raw = readFlowableAttr(props.element, 'actionButtons');
    const codes = raw ? raw.split(',') : DEFAULT_ACTION_BUTTONS.split(',');
    return [...new Set(codes.filter(Boolean).concat('approve'))];
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

const candidateUsers = computed(() => {
  void props.revision;
  return readFlowableAttr(props.element, 'candidateUsers').split(',').filter(Boolean);
});
</script>

<template>
  <div class="task-panel">
    <section class="inspector-section">
      <h3 class="inspector-section-title">
        基础信息
      </h3>
      <div class="field">
        <label class="field-label">节点标识</label>
        <el-input
          v-model="elementId"
          aria-label="节点标识"
          maxlength="64"
          placeholder="请输入节点标识"
          @change="commitElementId"
          @keyup.enter="commitElementId"
        />
        <span
          v-if="elementIdError"
          class="field-hint field-hint--error"
        >{{ elementIdError }}</span>
      </div>
      <div class="field">
        <label class="field-label">节点名称</label>
        <el-input
          v-model="taskName"
          aria-label="节点名称"
          maxlength="64"
          placeholder="请输入节点名称"
        />
      </div>
    </section>

    <section class="inspector-section">
      <h3 class="inspector-section-title">
        审批信息
      </h3>

      <div class="sub-block">
        <div class="sub-title">
          审批模式
        </div>
        <el-segmented
          class="flow-segmented flow-segmented--block"
          :options="APPROVE_MODE_OPTIONS"
          :model-value="approveMode"
          @change="(v: string | number | boolean) => switchApproveMode(v as ApproveMode)"
        />
        <p class="field-hint section-hint">
          单人：节点生成一条任务；多人：按执行方式生成多条任务。
        </p>
      </div>

      <div
        v-if="approveMode === 'single'"
        class="sub-block"
      >
        <div class="sub-title">
          审批人绑定
        </div>
        <el-radio-group
          class="flow-choices"
          :model-value="bindingMode"
          @update:model-value="(v: string | number | boolean | undefined) => switchBinding(v as 'assignee' | 'groups')"
        >
          <el-radio-button value="assignee">
            指定人员
          </el-radio-button>
          <el-radio-button value="groups">
            候选组（角色）
          </el-radio-button>
        </el-radio-group>

        <el-select
          v-if="bindingMode === 'assignee'"
          class="block field-gap"
          :model-value="assignee"
          filterable
          remote
          clearable
          reserve-keyword
          placeholder="远程搜索用户"
          aria-label="选择审批人"
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
          class="block field-gap"
          :model-value="candidateGroups"
          multiple
          filterable
          remote
          reserve-keyword
          placeholder="远程搜索角色"
          aria-label="选择候选组"
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

        <el-alert
          v-if="bindingMode === 'none'"
          class="flow-alert flow-alert--warning section-alert"
          type="warning"
          :closable="false"
          show-icon
          title="该任务无审批人"
          description="请在指定人员、候选组中二选一，否则流程无法流转。"
        />
      </div>

      <div
        v-if="approveMode === 'multi'"
        class="sub-block"
      >
        <div class="sub-title">
          执行方式
        </div>
        <el-radio-group
          class="flow-choices"
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
          <p class="field-hint section-hint">
            单条任务多人可见，谁先签收谁处理，一人完成即结束。
          </p>
          <el-select
            class="block field-gap"
            :model-value="candidateUsers"
            multiple
            filterable
            remote
            reserve-keyword
            placeholder="选择抢签人员"
            aria-label="选择抢签人员"
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

        <div
          v-if="isMultiInstance"
          class="field field-gap"
        >
          <label class="field-label">人员集合变量</label>
          <el-input
            :model-value="collection"
            aria-label="人员集合变量"
            maxlength="64"
            @update:model-value="(v: string) => (collection = v)"
          />
          <span class="field-hint">发起流程时需传入该变量（用户标识集合）</span>
        </div>
      </div>

      <div
        v-if="isMultiInstance"
        class="sub-block"
      >
        <div class="sub-title">
          通过条件
        </div>
        <el-radio-group
          class="flow-choices"
          :model-value="passCondition"
          @update:model-value="(v: string | number | boolean | undefined) => onPassCondition(v as PassCondition)"
        >
          <el-radio-button value="all">
            全员通过
          </el-radio-button>
          <el-radio-button value="any">
            一人通过即结束
          </el-radio-button>
          <el-radio-button value="ratio">
            达到比例
          </el-radio-button>
          <el-radio-button value="votes">
            达到票数
          </el-radio-button>
          <el-radio-button value="veto">
            一票否决
          </el-radio-button>
        </el-radio-group>

        <template v-if="passCondition === 'ratio'">
          <el-input-number
            class="block field-gap"
            :model-value="conditionValue"
            :min="1"
            :max="100"
            @change="onConditionValueChange"
          />
          <p class="field-hint">
            达到 X% 即通过
          </p>
        </template>

        <template v-if="passCondition === 'votes'">
          <el-input-number
            class="block field-gap"
            :model-value="conditionValue"
            :min="1"
            @change="onConditionValueChange"
          />
          <p class="field-hint">
            达到 N 票即通过
          </p>
        </template>

        <p
          v-if="passCondition === 'veto'"
          class="field-hint section-hint"
        >
          任一实例办理为驳回即终止剩余实例，流程走驳回线。
        </p>
      </div>
    </section>

    <section class="inspector-section">
      <h3 class="inspector-section-title">
        抄送人信息
      </h3>
      <div class="field">
        <label class="field-label">指定人员</label>
        <el-select
          class="block"
          :model-value="ccUsers"
          multiple
          filterable
          remote
          reserve-keyword
          placeholder="指定人员（多选）"
          aria-label="抄送人员"
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
      </div>
      <div class="field">
        <label class="field-label">候选组（角色）</label>
        <el-select
          class="block"
          :model-value="ccGroups"
          multiple
          filterable
          remote
          reserve-keyword
          placeholder="候选组（角色，多选）"
          aria-label="抄送候选组"
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
      <p class="field-hint">
        支持指定人员与候选组，两者可同时配置。
      </p>
    </section>

    <section class="inspector-section">
      <h3 class="inspector-section-title">
        流程配置
      </h3>

      <div class="sub-block">
        <div class="sub-title">
          通知方式
        </div>
        <el-checkbox-group
          class="flow-choices"
          :model-value="notifyTypes"
          @update:model-value="(v: Array<string | number | boolean>) => (notifyTypes = v.map(String))"
        >
          <el-checkbox-button
            v-for="n in NOTIFY_TYPES"
            :key="n.code"
            :value="n.code"
          >
            {{ n.label }}{{ n.pending ? '·待接入' : '' }}
          </el-checkbox-button>
        </el-checkbox-group>
        <p class="field-hint section-hint">
          可多选；都不选即不通知。标注「待接入」的方式暂不会实际发送通知。
        </p>
      </div>

      <div class="sub-block">
        <div class="sub-title">
          办理按钮
        </div>
        <el-checkbox-group
          class="flow-choices"
          :model-value="selectedButtons"
          @update:model-value="(v: Array<string | number | boolean>) => (selectedButtons = v.map(String))"
        >
          <el-checkbox-button
            v-for="b in ACTION_BUTTONS"
            :key="b.code"
            :value="b.code"
            :disabled="b.fixed"
          >
            {{ b.label }}{{ b.pending ? '·待接入' : '' }}
          </el-checkbox-button>
        </el-checkbox-group>
        <p class="field-hint section-hint">
          审批恒在；标注「待接入」的按钮任务中心暂不渲染。
        </p>
      </div>
    </section>
  </div>
</template>

<style scoped lang="scss">
.block {
  width: 100%;
}

/* 四大分组（基础信息/审批信息/抄送人信息/流程配置）标题加强：teal 竖条 + 提亮放大 */
.inspector-section {
  padding: 16px 0;
}

.inspector-section-title {
  justify-content: flex-start;
  margin: 0 0 13px;
  color: var(--text-1, var(--canvas-text));
  font-family: inherit;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.02em;
  text-transform: none;

  &::before {
    content: '';
    width: 3px;
    height: 14px;
    margin-right: 8px;
    border-radius: 2px;
    background: var(--teal, #35c3ae);
  }
}

.field-hint--error {
  margin-top: 5px;
  display: block;
  color: var(--danger);
}

.field-gap {
  margin-top: 9px;
}

.section-hint {
  margin-top: 8px;
}

.section-alert {
  margin-top: 9px;
}

.sub-block + .sub-block {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px dashed var(--line, var(--wash-08));
}

.sub-title {
  margin-bottom: 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-2, #9fb8b4);
  letter-spacing: 0.4px;
}
</style>
