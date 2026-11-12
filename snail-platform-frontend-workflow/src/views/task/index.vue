<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';

import { pageUsers } from '@/api/system/user';
import {
  getInstanceTrace,
  type WorkflowTraceStep,
  type WorkflowTraceVO,
} from '@/api/workflow/instance';
import {
  approveTask,
  claimTask,
  pageDoneTasks,
  pageTodoTasks,
  rejectTask,
  transferTask,
  type WorkflowDoneVO,
  type WorkflowTodoVO,
} from '@/api/workflow/task';
import AppIcon from '@/components/AppIcon.vue';
import { useAuthStore } from '@/stores/auth';

type TabKey = 'todo' | 'done';
type Tone = '' | 'warn' | 'danger';

interface TaskView {
  taskId: string;
  taskDefinitionKey: string;
  taskName: string;
  instanceId: string;
  definitionKey?: string;
  businessKey?: string;
  instanceName?: string;
  initiatorId?: number;
  initiatorName?: string;
  createTime?: string;
  dueDate?: string;
  claimed?: boolean;
  handledAt?: string;
}

interface UserOption {
  label: string;
  value: number;
}

const ACTION_LABELS: Record<string, string> = {
  start: '发起流程',
  claim: '签收任务',
  approve: '审批通过',
  reject: '驳回',
  transfer: '转办',
  withdraw: '撤销流程',
};

const auth = useAuthStore();
/** 按钮级权限:与 sys_menu 种子码一致(办理/已办独立权限),未授权即隐藏对应入口 */
const canHandle = computed(() => auth.has('system:workflow-task:handle'));
const canViewDone = computed(() => auth.has('system:workflow-task:done'));
const PAGE_SIZE = 10;
const FETCH_SIZE = 200;

const activeTab = ref<TabKey>('todo');
const loading = ref(false);

const todoRows = ref<WorkflowTodoVO[]>([]);
const doneRows = ref<WorkflowDoneVO[]>([]);
const todoTotal = ref(0);
const doneTotal = ref(0);

const todoQuery = ref('');
const todoDefinition = ref('');
const todoPage = ref(1);
const doneQuery = ref('');
const doneDefinition = ref('');
const donePage = ref(1);

const doneActions = ref<Record<string, string>>({});
const doneTraceLoading = ref<Record<string, boolean>>({});
const doneTraceCache = new Map<string, WorkflowTraceVO | null>();

function toView(row: WorkflowTodoVO | WorkflowDoneVO): TaskView {
  return { ...row };
}

function matchesFilter(row: TaskView, keyword: string, definitionKey: string): boolean {
  if (definitionKey && row.definitionKey !== definitionKey) return false;
  const normalized = keyword.trim().toLowerCase();
  if (!normalized) return true;
  const haystack = [
    row.taskName,
    row.taskId,
    row.instanceId,
    row.instanceName,
    row.businessKey,
    row.definitionKey,
    row.initiatorName,
  ]
    .filter(Boolean)
    .join(' ')
    .toLowerCase();
  return haystack.includes(normalized);
}

const filteredTodo = computed(() =>
  todoRows.value.filter((row) => matchesFilter(row, todoQuery.value, todoDefinition.value))
);
const filteredDone = computed(() =>
  doneRows.value.filter((row) => matchesFilter(row, doneQuery.value, doneDefinition.value))
);

const pagedTodo = computed(() => {
  const start = (todoPage.value - 1) * PAGE_SIZE;
  return filteredTodo.value.slice(start, start + PAGE_SIZE);
});
const pagedDone = computed(() => {
  const start = (donePage.value - 1) * PAGE_SIZE;
  return filteredDone.value.slice(start, start + PAGE_SIZE);
});

const definitionOptions = computed(() => {
  const map = new Map<string, string>();
  const add = (key?: string, name?: string): void => {
    if (!key) return;
    if (!map.has(key)) map.set(key, name || key);
  };
  for (const row of todoRows.value) add(row.definitionKey, row.instanceName);
  for (const row of doneRows.value) add(row.definitionKey, row.instanceName);
  return Array.from(map.entries(), ([key, label]) => ({
    key,
    label: label === key ? key : `${label} · ${key}`,
  }));
});

const dueSoonCount = computed(
  () => todoRows.value.filter((row) => remainingMeta(row.dueDate).tone === 'warn').length
);
const unclaimedCount = computed(() => todoRows.value.filter((row) => !row.claimed).length);

watch([todoQuery, todoDefinition], () => {
  todoPage.value = 1;
});
watch([doneQuery, doneDefinition], () => {
  donePage.value = 1;
});
watch(filteredTodo, (rows) => {
  const pages = Math.max(1, Math.ceil(rows.length / PAGE_SIZE));
  if (todoPage.value > pages) todoPage.value = pages;
});
watch(filteredDone, (rows) => {
  const pages = Math.max(1, Math.ceil(rows.length / PAGE_SIZE));
  if (donePage.value > pages) donePage.value = pages;
});
watch(
  [pagedDone, activeTab],
  ([rows, tab]) => {
    if (tab === 'done') void enrichDoneVisible(rows);
  },
  { immediate: true }
);

function formatTime(value?: string): string {
  if (!value) return '—';
  return value.slice(0, 19).replace('T', ' ');
}

function formatSpan(ms: number): string {
  const minutes = Math.max(0, Math.floor(ms / 60000));
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ${minutes % 60}m`;
  return `${Math.floor(hours / 24)}d ${hours % 24}h`;
}

function remainingMeta(dueDate?: string): { text: string; tone: Tone } {
  if (!dueDate) return { text: '—', tone: '' };
  const due = new Date(dueDate).getTime();
  if (Number.isNaN(due)) return { text: '—', tone: '' };
  const diff = due - Date.now();
  if (diff < 0) return { text: `已逾期 ${formatSpan(-diff)}`, tone: 'danger' };
  if (diff <= 24 * 60 * 60 * 1000) return { text: `剩余 ${formatSpan(diff)}`, tone: 'warn' };
  return { text: `剩余 ${formatSpan(diff)}`, tone: '' };
}

function slaClass(dueDate?: string): string {
  const tone = remainingMeta(dueDate).tone;
  return tone ? `sla--${tone}` : '';
}

function durationBetween(start?: string, end?: string): string {
  if (!start || !end) return '—';
  const from = new Date(start).getTime();
  const to = new Date(end).getTime();
  if (Number.isNaN(from) || Number.isNaN(to)) return '—';
  return formatSpan(Math.max(0, to - from));
}

function instanceLabel(row: { instanceName?: string; definitionKey?: string }): string {
  return row.instanceName || row.definitionKey || '未命名流程';
}

function applicantName(row: { initiatorName?: string; initiatorId?: number }): string {
  if (row.initiatorName) return row.initiatorName;
  return row.initiatorId ? `用户 #${row.initiatorId}` : '—';
}

function todoTile(row: WorkflowTodoVO): { icon: string; tone: string } {
  const tone = remainingMeta(row.dueDate).tone;
  if (tone === 'danger') return { icon: 'flag', tone: 'tile--amber' };
  if (tone === 'warn') return { icon: 'clock', tone: 'tile--amber' };
  return { icon: 'task', tone: '' };
}

function resultMeta(action?: string): { label: string; cls: string } {
  if (action === 'approve') return { label: '通过', cls: 'status-tag--teal' };
  if (action === 'reject') return { label: '驳回', cls: 'status-tag--danger' };
  if (action === 'transfer') return { label: '转办', cls: 'status-tag--amber' };
  if (action === 'claim') return { label: '签收', cls: 'status-tag--slate' };
  if (action === 'unavailable') return { label: '—', cls: 'status-tag--slate' };
  return { label: '已办结', cls: 'status-tag--slate' };
}

function doneResult(row: WorkflowDoneVO): { label: string; cls: string } {
  const action = doneActions.value[row.taskId];
  if (!action) {
    return doneTraceLoading.value[row.taskId]
      ? { label: '读取中…', cls: 'status-tag--slate' }
      : { label: '—', cls: 'status-tag--slate' };
  }
  return resultMeta(action);
}

async function traceOf(instanceId: string): Promise<WorkflowTraceVO | null> {
  if (doneTraceCache.has(instanceId)) return doneTraceCache.get(instanceId) ?? null;
  try {
    const trace = await getInstanceTrace(instanceId);
    doneTraceCache.set(instanceId, trace);
    return trace;
  } catch {
    doneTraceCache.set(instanceId, null);
    return null;
  }
}

async function enrichDoneVisible(rows: WorkflowDoneVO[]): Promise<void> {
  const targets = rows.filter(
    (row) => !doneActions.value[row.taskId] && !doneTraceLoading.value[row.taskId]
  );
  if (!targets.length) return;
  await Promise.allSettled(
    targets.map(async (row) => {
      doneTraceLoading.value[row.taskId] = true;
      try {
        const trace = await traceOf(row.instanceId);
        if (!trace) {
          doneActions.value[row.taskId] = 'unavailable';
          return;
        }
        const steps = trace.steps;
        for (let index = steps.length - 1; index >= 0; index -= 1) {
          if (steps[index].taskId === row.taskId) {
            doneActions.value[row.taskId] = steps[index].action;
            return;
          }
        }
        doneActions.value[row.taskId] = 'unavailable';
      } finally {
        doneTraceLoading.value[row.taskId] = false;
      }
    })
  );
}

async function loadData(notify = false): Promise<void> {
  loading.value = true;
  try {
    const [todoResult, doneResultPage] = await Promise.allSettled([
      pageTodoTasks({ pageNum: 1, pageSize: FETCH_SIZE }),
      pageDoneTasks({ pageNum: 1, pageSize: FETCH_SIZE }),
    ]);
    if (todoResult.status === 'fulfilled') {
      todoRows.value = todoResult.value.rows;
      todoTotal.value = todoResult.value.total;
    }
    if (doneResultPage.status === 'fulfilled') {
      doneRows.value = doneResultPage.value.rows;
      doneTotal.value = doneResultPage.value.total;
      doneActions.value = {};
      doneTraceCache.clear();
    }
    if (notify && (todoResult.status === 'fulfilled' || doneResultPage.status === 'fulfilled')) {
      ElMessage.success('任务列表已刷新');
    }
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  void loadData();
});

async function refresh(): Promise<void> {
  await loadData(true);
}

function applyTodoFilters(): void {
  todoPage.value = 1;
}

function resetTodoFilters(): void {
  todoQuery.value = '';
  todoDefinition.value = '';
  todoPage.value = 1;
}

function applyDoneFilters(): void {
  donePage.value = 1;
}

function resetDoneFilters(): void {
  doneQuery.value = '';
  doneDefinition.value = '';
  donePage.value = 1;
}

const drawerVisible = ref(false);
const drawerTab = ref('review');
const activeKind = ref<TabKey>('todo');
const activeTask = ref<TaskView | null>(null);
const trace = ref<WorkflowTraceVO | null>(null);
const traceLoading = ref(false);
const traceFailed = ref(false);
const comment = ref('');
const submitting = ref(false);
let traceSeq = 0;

const traceSteps = computed(() => trace.value?.steps ?? []);
const waitingTasks = computed(() => trace.value?.waiting ?? []);
const doneStep = computed<WorkflowTraceStep | null>(() => {
  const current = activeTask.value;
  if (!current || activeKind.value !== 'done') return null;
  const steps = trace.value?.steps ?? [];
  for (let index = steps.length - 1; index >= 0; index -= 1) {
    if (steps[index].taskId === current.taskId) return steps[index];
  }
  return null;
});
const drawerStatus = computed(() => {
  if (activeKind.value === 'done') return { label: '已办结', cls: 'status-tag--blue' };
  return activeTask.value?.claimed
    ? { label: '已签收', cls: 'status-tag--teal' }
    : { label: '未签收', cls: 'status-tag--amber' };
});
const drawerFootnote = computed(() =>
  activeKind.value === 'done'
    ? '已办任务为只读视图，可在流程轨迹中查看完整记录。'
    : '通过、驳回均需填写审批意见；转办后任务离开我的待办。'
);

async function openTask(row: WorkflowTodoVO | WorkflowDoneVO, kind: TabKey): Promise<void> {
  activeKind.value = kind;
  activeTask.value = toView(row);
  comment.value = '';
  drawerTab.value = 'review';
  trace.value = null;
  traceFailed.value = false;
  drawerVisible.value = true;
  await loadActiveTrace();
}

async function loadActiveTrace(): Promise<void> {
  const current = activeTask.value;
  if (!current) return;
  const seq = ++traceSeq;
  traceLoading.value = true;
  traceFailed.value = false;
  try {
    const result = await getInstanceTrace(current.instanceId);
    if (seq !== traceSeq) return;
    trace.value = result;
  } catch {
    if (seq !== traceSeq) return;
    traceFailed.value = true;
  } finally {
    if (seq === traceSeq) traceLoading.value = false;
  }
}

function actionLabel(action: string): string {
  return ACTION_LABELS[action] ?? action;
}

function actionTagType(action: string): 'success' | 'warning' | 'info' | 'danger' {
  if (action === 'reject' || action === 'withdraw') return 'danger';
  if (action === 'approve') return 'success';
  return 'info';
}

function operatorText(step: WorkflowTraceStep): string {
  if (step.operatorName) return step.operatorName;
  return step.operatorId ? `用户 #${step.operatorId}` : '系统';
}

function isActiveStep(step: WorkflowTraceStep): boolean {
  return !!activeTask.value && step.taskId === activeTask.value.taskId;
}

async function handleDecision(action: 'approve' | 'reject'): Promise<void> {
  const current = activeTask.value;
  if (!current || activeKind.value !== 'todo' || submitting.value) return;
  const text = comment.value.trim();
  if (!text) {
    ElMessage.warning('请填写审批意见');
    return;
  }
  submitting.value = true;
  try {
    if (action === 'approve') {
      await approveTask(current.taskId, text);
    } else {
      await rejectTask(current.taskId, text);
    }
    ElMessage.success(action === 'approve' ? '任务已通过' : '任务已驳回');
    drawerVisible.value = false;
    await loadData();
  } finally {
    submitting.value = false;
  }
}

const claimVisible = ref(false);
const claimTarget = ref<TaskView | null>(null);
const claimComment = ref('');
const claimSubmitting = ref(false);

function openClaim(row: TaskView): void {
  claimTarget.value = { ...row };
  claimComment.value = '';
  claimVisible.value = true;
}

function claimActive(): void {
  if (activeTask.value) openClaim(activeTask.value);
}

async function confirmClaim(): Promise<void> {
  const target = claimTarget.value;
  if (!target || claimSubmitting.value) return;
  const text = claimComment.value.trim();
  if (!text) {
    ElMessage.warning('请填写签收意见');
    return;
  }
  claimSubmitting.value = true;
  try {
    await claimTask(target.taskId, text);
    claimVisible.value = false;
    ElMessage.success('任务已签收');
    await loadData();
    if (
      drawerVisible.value &&
      activeKind.value === 'todo' &&
      activeTask.value?.taskId === target.taskId
    ) {
      const updated = todoRows.value.find((row) => row.taskId === target.taskId);
      if (updated) {
        activeTask.value = toView(updated);
        await loadActiveTrace();
      }
    }
  } finally {
    claimSubmitting.value = false;
  }
}

const transferVisible = ref(false);
const transferTarget = ref<TaskView | null>(null);
const transferUserId = ref<number | null>(null);
const transferManualId = ref<number | undefined>(undefined);
const transferNote = ref('');
const transferSubmitting = ref(false);
const transferUserOptions = ref<UserOption[]>([]);
const transferUserLoading = ref(false);
const transferDirectoryError = ref(false);
let transferSearchSeq = 0;

function openTransfer(row: TaskView): void {
  transferTarget.value = { ...row };
  transferUserId.value = null;
  transferManualId.value = undefined;
  transferNote.value = '';
  transferUserOptions.value = [];
  transferDirectoryError.value = false;
  transferVisible.value = true;
  void searchTransferUsers('');
}

function transferActive(): void {
  if (activeTask.value) openTransfer(activeTask.value);
}

async function searchTransferUsers(keyword: string): Promise<void> {
  const seq = ++transferSearchSeq;
  transferUserLoading.value = true;
  try {
    const page = await pageUsers({
      pageNum: 1,
      pageSize: 50,
      nickname: keyword || undefined,
      status: 0,
    });
    if (seq !== transferSearchSeq) return;
    transferUserOptions.value = page.rows
      .filter((user) => user.userId !== auth.loginUser?.userId)
      .map((user) => ({
        label: user.nickname ? `${user.nickname}(${user.username})` : user.username,
        value: user.userId,
      }));
    transferDirectoryError.value = false;
  } catch {
    if (seq !== transferSearchSeq) return;
    transferDirectoryError.value = true;
  } finally {
    if (seq === transferSearchSeq) transferUserLoading.value = false;
  }
}

function handleTodoCommand(command: string, row: WorkflowTodoVO): void {
  if (command === 'transfer') openTransfer(row);
}

async function confirmTransfer(): Promise<void> {
  const target = transferTarget.value;
  if (!target || transferSubmitting.value) return;
  const targetUserId = transferDirectoryError.value
    ? transferManualId.value
    : transferUserId.value;
  if (!targetUserId) {
    ElMessage.warning('请选择转办目标');
    return;
  }
  if (targetUserId === auth.loginUser?.userId) {
    ElMessage.warning('不能转办给自己');
    return;
  }
  transferSubmitting.value = true;
  try {
    const note = transferNote.value.trim();
    await transferTask(target.taskId, targetUserId, note || undefined);
    transferVisible.value = false;
    drawerVisible.value = false;
    ElMessage.success('任务已转办');
    await loadData();
  } finally {
    transferSubmitting.value = false;
  }
}
</script>

<template>
  <div class="page-head">
    <div>
      <div class="eyebrow">
        TASK CENTER / INBOX
      </div>
      <h1>任务中心</h1>
      <p>处理分配给我的审批任务：签收、通过、驳回或转办；已办任务保留完整处理记录。</p>
    </div>
    <div class="page-actions">
      <el-button @click="refresh">
        <AppIcon name="refresh" />刷新
      </el-button>
    </div>
  </div>

  <div class="split-stats">
    <div class="mini-stat">
      <AppIcon name="inbox" /><b>{{ todoTotal }}</b><span>待我处理</span>
    </div>
    <div class="mini-stat">
      <AppIcon name="check-circle" /><b>{{ doneTotal }}</b><span>已办任务</span>
    </div>
    <div class="mini-stat">
      <AppIcon name="clock" /><b>{{ dueSoonCount }}</b><span>即将超时</span>
    </div>
    <div class="mini-stat">
      <AppIcon name="lock" /><b>{{ unclaimedCount }}</b><span>未签收</span>
    </div>
  </div>

  <section class="panel">
    <div class="panel-head">
      <h2 class="panel-title">
        <AppIcon name="task" />任务队列
      </h2>
      <span class="toolbar-note"><AppIcon name="target" />待办与已办均按到达时间倒序</span>
    </div>

    <el-tabs
      v-model="activeTab"
      class="flow-tabs task-tabs"
    >
      <el-tab-pane name="todo">
        <template #label>
          待办 <span class="tab-count">{{ todoTotal }}</span>
        </template>

        <div class="toolbar">
          <el-input
            v-model="todoQuery"
            class="flow-search"
            placeholder="搜索任务 / 实例 / 申请人"
            aria-label="搜索待办任务"
            clearable
            @keyup.enter="applyTodoFilters"
          >
            <template #prefix><AppIcon name="search" /></template>
          </el-input>
          <el-select
            v-model="todoDefinition"
            class="flow-select"
            placeholder="全部流程"
            aria-label="按流程筛选待办"
            clearable
          >
            <el-option
              v-for="item in definitionOptions"
              :key="item.key"
              :label="item.label"
              :value="item.key"
            />
          </el-select>
          <el-button
            size="small"
            @click="applyTodoFilters"
          >
            <AppIcon name="search" />查询
          </el-button>
          <el-button
            size="small"
            link
            class="ghost-btn"
            @click="resetTodoFilters"
          >
            重置
          </el-button>
          <span class="grow"></span>
          <span class="toolbar-note">
            <AppIcon name="filter" />共 {{ todoTotal }} 条<template v-if="todoRows.length < todoTotal"> · 已加载 {{ todoRows.length }} 条</template> · 当前页 {{ pagedTodo.length }} 条
          </span>
        </div>

        <div class="table-wrap">
          <el-table
            v-loading="loading"
            :data="pagedTodo"
            class="flow-table task-table"
            row-key="taskId"
          >
            <el-table-column
              label="任务"
              min-width="210"
            >
              <template #default="scope">
                <div class="cell-main">
                  <span
                    class="tile"
                    :class="todoTile(scope.row).tone"
                  >
                    <AppIcon :name="todoTile(scope.row).icon" />
                  </span>
                  <div class="cell-copy">
                    <div class="cell-title">
                      {{ scope.row.taskName || scope.row.taskDefinitionKey }}
                    </div>
                    <div class="cell-sub">
                      {{ scope.row.taskId }}
                    </div>
                  </div>
                </div>
              </template>
            </el-table-column>

            <el-table-column
              label="流程实例"
              min-width="210"
            >
              <template #default="scope">
                <div class="instance-node">
                  <b>{{ instanceLabel(scope.row) }}</b>
                  <span>{{ scope.row.instanceId }}<template v-if="scope.row.businessKey"> · {{ scope.row.businessKey }}</template></span>
                </div>
              </template>
            </el-table-column>

            <el-table-column
              label="申请人"
              min-width="132"
            >
              <template #default="scope">
                <div class="cell-copy">
                  <div class="cell-title">
                    {{ applicantName(scope.row) }}
                  </div>
                  <div class="cell-sub">
                    {{ scope.row.definitionKey || '—' }}
                  </div>
                </div>
              </template>
            </el-table-column>

            <el-table-column
              label="到达时间"
              min-width="152"
            >
              <template #default="scope">
                <span class="mono">{{ formatTime(scope.row.createTime) }}</span>
              </template>
            </el-table-column>

            <el-table-column
              label="剩余时间"
              min-width="116"
            >
              <template #default="scope">
                <span
                  class="sla"
                  :class="slaClass(scope.row.dueDate)"
                >{{ remainingMeta(scope.row.dueDate).text }}</span>
              </template>
            </el-table-column>

            <el-table-column
              label="状态"
              min-width="96"
            >
              <template #default="scope">
                <el-tag
                  class="status-tag"
                  :class="scope.row.claimed ? 'status-tag--teal' : 'status-tag--amber'"
                  disable-transitions
                >
                  {{ scope.row.claimed ? '已签收' : '未签收' }}
                </el-tag>
              </template>
            </el-table-column>

            <el-table-column
              label="操作"
              min-width="212"
              align="right"
            >
              <template #default="scope">
                <div class="row-actions">
                  <el-button
                    v-if="canHandle && !scope.row.claimed"
                    link
                    size="small"
                    @click="openClaim(scope.row)"
                  >
                    <AppIcon name="check" />签收
                  </el-button>
                  <el-button
                    link
                    size="small"
                    @click="openTask(scope.row, 'todo')"
                  >
                    <AppIcon name="arrow-right" />处理
                  </el-button>
                  <el-dropdown
                    v-if="canHandle"
                    trigger="click"
                    @command="command => handleTodoCommand(String(command), scope.row)"
                  >
                    <button
                      class="icon-btn"
                      type="button"
                      aria-label="更多操作"
                    >
                      <AppIcon name="more" />
                    </button>
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item command="transfer">
                          <span class="menu-inline"><AppIcon name="users" />转办</span>
                        </el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </div>
              </template>
            </el-table-column>

            <template #empty>
              <el-empty
                class="flow-empty"
                description="待办已清空"
              >
                <template #image>
                  <span class="empty-icon"><AppIcon name="inbox" /></span>
                </template>
                <p class="empty-hint">
                  当前筛选条件下没有待处理任务。
                </p>
              </el-empty>
            </template>
          </el-table>
        </div>

        <div class="pager flow-pager-bar">
          <span class="pager-note">共 {{ filteredTodo.length }} 条 · 每页 {{ PAGE_SIZE }} 条</span>
          <el-pagination
            v-model:current-page="todoPage"
            class="flow-pager"
            layout="prev, pager, next"
            :total="filteredTodo.length"
            :page-size="PAGE_SIZE"
            :pager-count="5"
          />
        </div>
      </el-tab-pane>

      <el-tab-pane
        v-if="canViewDone"
        name="done"
      >
        <template #label>
          已办 <span class="tab-count">{{ doneTotal }}</span>
        </template>

        <div class="toolbar">
          <el-input
            v-model="doneQuery"
            class="flow-search"
            placeholder="搜索已办任务 / 实例"
            aria-label="搜索已办任务"
            clearable
            @keyup.enter="applyDoneFilters"
          >
            <template #prefix><AppIcon name="search" /></template>
          </el-input>
          <el-select
            v-model="doneDefinition"
            class="flow-select"
            placeholder="全部流程"
            aria-label="按流程筛选已办"
            clearable
          >
            <el-option
              v-for="item in definitionOptions"
              :key="item.key"
              :label="item.label"
              :value="item.key"
            />
          </el-select>
          <el-button
            size="small"
            @click="applyDoneFilters"
          >
            <AppIcon name="search" />查询
          </el-button>
          <el-button
            size="small"
            link
            class="ghost-btn"
            @click="resetDoneFilters"
          >
            重置
          </el-button>
          <span class="grow"></span>
          <span class="toolbar-note">
            <AppIcon name="filter" />共 {{ doneTotal }} 条<template v-if="doneRows.length < doneTotal"> · 已加载 {{ doneRows.length }} 条</template> · 当前页 {{ pagedDone.length }} 条
          </span>
        </div>

        <div class="table-wrap">
          <el-table
            v-loading="loading"
            :data="pagedDone"
            class="flow-table task-table"
            row-key="taskId"
          >
            <el-table-column
              label="任务"
              min-width="210"
            >
              <template #default="scope">
                <div class="cell-main">
                  <span class="tile"><AppIcon name="check" /></span>
                  <div class="cell-copy">
                    <div class="cell-title">
                      {{ scope.row.taskName || scope.row.taskDefinitionKey }}
                    </div>
                    <div class="cell-sub">
                      {{ scope.row.taskId }}
                    </div>
                  </div>
                </div>
              </template>
            </el-table-column>

            <el-table-column
              label="流程实例"
              min-width="210"
            >
              <template #default="scope">
                <div class="instance-node">
                  <b>{{ instanceLabel(scope.row) }}</b>
                  <span>{{ scope.row.instanceId }}<template v-if="scope.row.businessKey"> · {{ scope.row.businessKey }}</template></span>
                </div>
              </template>
            </el-table-column>

            <el-table-column
              label="申请人"
              min-width="132"
            >
              <template #default="scope">
                <div class="cell-copy">
                  <div class="cell-title">
                    {{ applicantName(scope.row) }}
                  </div>
                  <div class="cell-sub">
                    {{ scope.row.definitionKey || '—' }}
                  </div>
                </div>
              </template>
            </el-table-column>

            <el-table-column
              label="办理结果"
              min-width="100"
            >
              <template #default="scope">
                <el-tag
                  class="status-tag"
                  :class="doneResult(scope.row).cls"
                  disable-transitions
                >
                  {{ doneResult(scope.row).label }}
                </el-tag>
              </template>
            </el-table-column>

            <el-table-column
              label="办理时间"
              min-width="152"
            >
              <template #default="scope">
                <span class="mono">{{ formatTime(scope.row.handledAt) }}</span>
              </template>
            </el-table-column>

            <el-table-column
              label="耗时"
              min-width="86"
            >
              <template #default="scope">
                <span class="sla">{{ durationBetween(scope.row.createTime, scope.row.handledAt) }}</span>
              </template>
            </el-table-column>

            <el-table-column
              label="操作"
              min-width="120"
              align="right"
            >
              <template #default="scope">
                <div class="row-actions">
                  <el-button
                    link
                    size="small"
                    @click="openTask(scope.row, 'done')"
                  >
                    <AppIcon name="eye" />查看
                  </el-button>
                </div>
              </template>
            </el-table-column>

            <template #empty>
              <el-empty
                class="flow-empty"
                description="没有匹配的已办任务"
              >
                <template #image>
                  <span class="empty-icon"><AppIcon name="inbox" /></span>
                </template>
                <p class="empty-hint">
                  试试调整关键词或流程筛选。
                </p>
              </el-empty>
            </template>
          </el-table>
        </div>

        <div class="pager flow-pager-bar">
          <span class="pager-note">共 {{ filteredDone.length }} 条 · 每页 {{ PAGE_SIZE }} 条</span>
          <el-pagination
            v-model:current-page="donePage"
            class="flow-pager"
            layout="prev, pager, next"
            :total="filteredDone.length"
            :page-size="PAGE_SIZE"
            :pager-count="5"
          />
        </div>
      </el-tab-pane>
    </el-tabs>
  </section>

  <el-drawer
    v-model="drawerVisible"
    class="flow-drawer"
    size="min(640px, 96vw)"
    direction="rtl"
    :show-close="false"
    :with-header="true"
  >
    <template #header>
      <div class="drawer-head">
        <div class="drawer-head-copy">
          <div class="dialog-kicker">
            TASK REVIEW
          </div>
          <h2 class="drawer-title">
            {{ activeTask ? activeTask.taskName || activeTask.taskDefinitionKey : '' }}
          </h2>
          <p class="drawer-sub">
            {{ activeTask ? `${instanceLabel(activeTask)} · ${activeTask.instanceId}` : '' }}
          </p>
        </div>
        <el-tag
          v-if="activeTask"
          class="status-tag"
          :class="drawerStatus.cls"
          disable-transitions
        >
          {{ drawerStatus.label }}
        </el-tag>
        <button
          class="icon-btn"
          type="button"
          aria-label="关闭任务详情"
          @click="drawerVisible = false"
        >
          <AppIcon name="x" />
        </button>
      </div>
    </template>

    <template v-if="activeTask">
      <div class="task-summary dialog-summary">
        <span
          class="tile"
          :class="activeKind === 'todo' ? 'tile--amber' : ''"
        >
          <AppIcon :name="activeKind === 'todo' ? 'task' : 'check'" />
        </span>
        <div class="task-summary-copy">
          <b>{{ instanceLabel(activeTask) }} · {{ activeTask.taskName || activeTask.taskDefinitionKey }}</b>
          <span v-if="activeKind === 'todo'">到达 {{ formatTime(activeTask.createTime) }} · {{ remainingMeta(activeTask.dueDate).text }}</span>
          <span v-else>办结于 {{ formatTime(activeTask.handledAt) }} · 耗时 {{ durationBetween(activeTask.createTime, activeTask.handledAt) }}</span>
        </div>
        <el-tag
          class="status-tag"
          :class="drawerStatus.cls"
          disable-transitions
        >
          {{ drawerStatus.label }}
        </el-tag>
      </div>

      <el-tabs
        v-model="drawerTab"
        class="flow-tabs flow-tabs--drawer"
      >
        <el-tab-pane
          label="审批信息"
          name="review"
        >
          <div class="meta-grid">
            <div class="meta-cell">
              <span>申请人</span><b>{{ applicantName(activeTask) }}</b>
            </div>
            <div class="meta-cell">
              <span>流程定义</span><b>{{ activeTask.definitionKey || '—' }}</b>
            </div>
            <div class="meta-cell">
              <span>到达时间</span><b>{{ formatTime(activeTask.createTime) }}</b>
            </div>
            <div class="meta-cell">
              <span>{{ activeKind === 'done' ? '办理时间' : '剩余时间' }}</span>
              <b>{{ activeKind === 'done' ? formatTime(activeTask.handledAt) : remainingMeta(activeTask.dueDate).text }}</b>
            </div>
          </div>

          <div class="inspector-section">
            <h3 class="inspector-section-title">
              <span>任务上下文</span><span class="mono">READ ONLY</span>
            </h3>
            <div class="form-data">
              <div class="form-data-row">
                <dt>任务 ID</dt><dd>{{ activeTask.taskId }}</dd>
              </div>
              <div class="form-data-row">
                <dt>实例 ID</dt><dd>{{ activeTask.instanceId }}</dd>
              </div>
              <div class="form-data-row">
                <dt>业务键</dt><dd>{{ activeTask.businessKey || '—' }}</dd>
              </div>
              <div class="form-data-row">
                <dt>流程定义</dt><dd>{{ activeTask.definitionKey || '—' }}</dd>
              </div>
              <div class="form-data-row">
                <dt>发起人</dt><dd>{{ applicantName(activeTask) }}</dd>
              </div>
              <div class="form-data-row">
                <dt>{{ activeKind === 'done' ? '办理耗时' : '到期时间' }}</dt>
                <dd>{{ activeKind === 'done' ? durationBetween(activeTask.createTime, activeTask.handledAt) : formatTime(activeTask.dueDate) }}</dd>
              </div>
            </div>
          </div>

          <div
            v-if="activeKind === 'todo'"
            class="inspector-section"
          >
            <h3 class="inspector-section-title">
              <span>审批意见 <span class="req">*</span></span><span class="mono">REQUIRED</span>
            </h3>
            <el-input
              v-model="comment"
              type="textarea"
              :rows="4"
              maxlength="500"
              show-word-limit
              placeholder="填写通过或驳回意见…"
            />
            <span class="field-hint hint-block">通过、驳回均须填写意见；意见会写入流程轨迹。</span>
          </div>

          <div
            v-else
            class="inspector-section"
          >
            <h3 class="inspector-section-title">
              <span>办理结果</span><span class="mono">ARCHIVED</span>
            </h3>
            <div
              v-if="doneStep"
              class="task-summary archived-summary"
            >
              <span class="tile"><AppIcon name="check" /></span>
              <div class="task-summary-copy">
                <b>{{ resultMeta(doneStep.action).label }}</b>
                <span>
                  {{ formatTime(doneStep.time || activeTask.handledAt) }}<template v-if="doneStep.targetUserName"> · 转办给 {{ doneStep.targetUserName }}</template>
                </span>
              </div>
              <el-tag
                class="status-tag"
                :class="resultMeta(doneStep.action).cls"
                disable-transitions
              >
                {{ resultMeta(doneStep.action).label }}
              </el-tag>
            </div>
            <div
              v-else-if="traceLoading"
              class="drawer-empty"
            >
              正在读取办理记录…
            </div>
            <div
              v-else
              class="drawer-empty"
            >
              {{ traceFailed ? '办理记录读取失败，仅显示任务元数据' : '轨迹中未找到该任务的办理动作' }}
            </div>
            <div
              v-if="doneStep"
              class="tl-note comment-note"
            >
              {{ doneStep.comment || '未填写办理意见' }}
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane
          label="流程轨迹"
          name="trace"
        >
          <el-alert
            class="flow-alert"
            type="info"
            :closable="false"
            show-icon
          >
            轨迹由审批台账与引擎在途节点拼接；当前任务以「本任务」标记。
          </el-alert>

          <div class="inspector-section">
            <h3 class="inspector-section-title">
              <span>已发生动作</span><span class="mono">{{ traceSteps.length }} STEPS</span>
            </h3>
            <el-timeline
              v-if="traceSteps.length"
              class="flow-timeline"
            >
              <el-timeline-item
                v-for="(step, index) in traceSteps"
                :key="`${step.taskId || 'instance'}-${step.action}-${index}`"
                class="is-done"
              >
                <template #dot><span class="tl-dot" /></template>
                <div class="tl-title">
                  {{ actionLabel(step.action) }}
                  <span
                    v-if="step.taskName"
                    class="trace-task"
                  >{{ step.taskName }}</span>
                  <el-tag
                    v-if="isActiveStep(step)"
                    class="flow-tag"
                    type="warning"
                    disable-transitions
                  >
                    本任务
                  </el-tag>
                  <el-tag
                    class="flow-tag"
                    :type="actionTagType(step.action)"
                    disable-transitions
                  >
                    {{ operatorText(step) }}
                  </el-tag>
                </div>
                <div class="tl-meta">
                  {{ formatTime(step.time) }}<template v-if="step.targetUserName"> · 转办给 {{ step.targetUserName }}</template>
                </div>
                <div
                  v-if="step.comment"
                  class="tl-note"
                >
                  {{ step.comment }}
                </div>
              </el-timeline-item>
            </el-timeline>
            <div
              v-else
              class="drawer-empty"
            >
              {{ traceLoading ? '正在读取流程轨迹…' : traceFailed ? '流程轨迹读取失败' : '暂无审批动作' }}
            </div>
          </div>

          <div class="inspector-section">
            <h3 class="inspector-section-title">
              <span>在途节点</span><AppIcon name="clock" />
            </h3>
            <div
              v-if="waitingTasks.length"
              class="waiting-list"
            >
              <div
                v-for="task in waitingTasks"
                :key="task.taskId"
                class="task-summary"
              >
                <span class="tile"><AppIcon name="clock" /></span>
                <div class="task-summary-copy">
                  <b>{{ task.taskName || '未命名节点' }}</b>
                  <span>{{ task.assigneeName ? `处理人 ${task.assigneeName}` : '待候选人认领' }} · {{ task.taskId }}</span>
                </div>
                <el-tag
                  class="status-tag status-tag--amber"
                  disable-transitions
                >
                  待处理
                </el-tag>
              </div>
            </div>
            <div
              v-else
              class="drawer-empty"
            >
              {{ traceLoading ? '正在读取在途节点…' : '当前没有在途节点' }}
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </template>

    <template #footer>
      <div class="drawer-foot">
        <span class="foot-note">{{ drawerFootnote }}</span>
        <div
          v-if="activeKind === 'todo' && canHandle"
          class="task-drawer-actions"
        >
          <el-button
            v-if="!activeTask?.claimed"
            @click="claimActive"
          >
            <AppIcon name="check" />签收任务
          </el-button>
          <el-button
            :disabled="submitting"
            @click="transferActive"
          >
            <AppIcon name="users" />转办
          </el-button>
          <el-button
            :disabled="submitting"
            @click="handleDecision('reject')"
          >
            <AppIcon name="x-circle" />驳回
          </el-button>
          <el-button
            type="primary"
            :loading="submitting"
            @click="handleDecision('approve')"
          >
            <AppIcon name="check" />通过
          </el-button>
        </div>
        <el-button
          v-else
          @click="drawerVisible = false"
        >
          关闭
        </el-button>
      </div>
    </template>
  </el-drawer>

  <el-dialog
    v-model="claimVisible"
    class="flow-dialog"
    width="540px"
    align-center
    :show-close="false"
  >
    <template #header>
      <div class="dialog-head">
        <div class="dialog-head-copy">
          <div class="dialog-kicker">
            TASK CLAIM
          </div>
          <h2 class="dialog-title">
            签收任务
          </h2>
          <p class="dialog-desc">
            签收后任务进入我的处理队列，签收意见会写入流程轨迹。
          </p>
        </div>
        <button
          class="icon-btn dialog-close"
          type="button"
          aria-label="关闭"
          @click="claimVisible = false"
        >
          <AppIcon name="x" />
        </button>
      </div>
    </template>

    <div
      v-if="claimTarget"
      class="task-summary dialog-summary"
    >
      <span class="tile"><AppIcon name="task" /></span>
      <div class="task-summary-copy">
        <b>{{ claimTarget.taskName || claimTarget.taskDefinitionKey }}</b>
        <span>{{ instanceLabel(claimTarget) }} · {{ claimTarget.instanceId }}</span>
      </div>
      <el-tag
        class="flow-tag"
        disable-transitions
      >
        待签收
      </el-tag>
    </div>

    <el-form
      label-position="top"
      @submit.prevent
    >
      <el-form-item
        label="签收意见"
        required
      >
        <el-input
          v-model="claimComment"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
          placeholder="说明签收背景…"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <div class="dialog-foot">
        <span class="foot-note">签收意见必填。</span>
        <el-button @click="claimVisible = false">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="claimSubmitting"
          @click="confirmClaim"
        >
          <AppIcon name="check" />确认签收
        </el-button>
      </div>
    </template>
  </el-dialog>

  <el-dialog
    v-model="transferVisible"
    class="flow-dialog"
    width="580px"
    align-center
    :show-close="false"
  >
    <template #header>
      <div class="dialog-head">
        <div class="dialog-head-copy">
          <div class="dialog-kicker">
            TRANSFER TASK
          </div>
          <h2 class="dialog-title">
            转办任务
          </h2>
          <p class="dialog-desc">
            转办后任务离开我的待办，由目标处理人继续审批。
          </p>
        </div>
        <button
          class="icon-btn dialog-close"
          type="button"
          aria-label="关闭"
          @click="transferVisible = false"
        >
          <AppIcon name="x" />
        </button>
      </div>
    </template>

    <div
      v-if="transferTarget"
      class="task-summary dialog-summary"
    >
      <span class="tile"><AppIcon name="task" /></span>
      <div class="task-summary-copy">
        <b>{{ transferTarget.taskName || transferTarget.taskDefinitionKey }}</b>
        <span>{{ instanceLabel(transferTarget) }} · {{ transferTarget.instanceId }}</span>
      </div>
      <el-tag
        class="flow-tag"
        disable-transitions
      >
        待办
      </el-tag>
    </div>

    <el-form
      label-position="top"
      @submit.prevent
    >
      <div class="form-grid">
        <el-form-item label="当前处理人">
          <el-input
            :model-value="auth.displayName || '我'"
            readonly
          />
        </el-form-item>
        <el-form-item
          v-if="!transferDirectoryError"
          label="转办给"
          required
        >
          <el-select
            v-model="transferUserId"
            filterable
            remote
            clearable
            reserve-keyword
            :remote-method="searchTransferUsers"
            :loading="transferUserLoading"
            placeholder="输入昵称检索用户"
            style="width: 100%"
          >
            <el-option
              v-for="item in transferUserOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
          <span class="field-hint hint-block">按昵称模糊检索，已排除当前登录人。</span>
        </el-form-item>
        <el-form-item
          v-else
          label="目标用户 ID"
          required
        >
          <el-input-number
            v-model="transferManualId"
            :min="1"
            :controls="false"
            placeholder="填写目标用户 ID"
            style="width: 100%"
          />
          <span class="field-hint hint-block">用户目录不可用（可能缺少 system:user:list 权限），请直接填写目标用户 ID。</span>
        </el-form-item>
        <el-form-item
          class="span-2"
          label="转办说明"
        >
          <el-input
            v-model="transferNote"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="选填，向目标处理人说明背景…"
          />
          <span class="field-hint hint-block">转办记录会写入流程轨迹。</span>
        </el-form-item>
      </div>
    </el-form>

    <template #footer>
      <div class="dialog-foot">
        <span class="foot-note">转办后不可撤回，原处理人仍可查看任务轨迹。</span>
        <el-button @click="transferVisible = false">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="transferSubmitting"
          @click="confirmTransfer"
        >
          <AppIcon name="send" />确认转办
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.task-table .cell-title {
  font-size: 11.5px;
}

.task-table .cell-sub {
  font-size: 9px;
}

.instance-node {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.instance-node b {
  color: var(--text-1);
  font-size: 11.5px;
  font-weight: 500;
}

.instance-node span {
  color: var(--text-3);
  font-size: 9.5px;
}

.sla {
  color: var(--text-2);
  font-family: var(--font-mono);
  font-size: 9.5px;
  white-space: nowrap;
}

.sla--warn {
  color: var(--amber);
}

.sla--danger {
  color: var(--danger);
}

.toolbar .flow-search {
  width: 236px;
}

.toolbar .flow-select {
  width: 156px;
}

.row-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 2px;
}

.row-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.row-actions :deep(.el-dropdown) {
  margin-left: 0;
}

.menu-inline {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.menu-inline .ic {
  width: 14px;
  height: 14px;
}

.dialog-summary {
  margin-bottom: 16px;
}

.archived-summary {
  margin-bottom: 10px;
}

.comment-note {
  margin-top: 0;
}

.req {
  color: var(--danger);
}

.hint-block {
  display: block;
  margin-top: 6px;
}

.task-drawer-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.task-drawer-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.dialog-foot {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.dialog-foot .foot-note {
  margin-right: auto;
}

.dialog-foot :deep(.el-button + .el-button) {
  margin-left: 0;
}

.waiting-list {
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.drawer-empty {
  padding: 12px 13px;
  border: 1px dashed var(--line);
  border-radius: 10px;
  color: var(--text-3);
  font-size: 10.5px;
}

.trace-task {
  color: var(--text-2);
  font-weight: 400;
}

@media (max-width: 860px) {
  .toolbar .flow-search,
  .toolbar .flow-select {
    width: 100%;
  }
}

@media (max-width: 560px) {
  .task-drawer-actions {
    width: 100%;
  }

  .task-drawer-actions :deep(.el-button) {
    flex: 1;
    justify-content: center;
  }

  .dialog-foot :deep(.el-button) {
    flex: 1;
    justify-content: center;
  }
}
</style>
