<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';

import {
  getInstance,
  getInstanceTrace,
  pageStartedInstances,
  startInstance,
  withdrawInstance,
  type WorkflowInstanceVO,
  type WorkflowStatus,
  type WorkflowTraceVO,
} from '@/api/workflow/instance';
import { pageModels, type WorkflowModelVO } from '@/api/workflow/model';
import AppIcon from '@/components/AppIcon.vue';

type InstanceFilter = 'all' | 'running' | 'approved' | 'rejected' | 'withdrawn';

type StatusMeta = { label: string; cls: string; tone: string; icon: string };

const STATUS_META: Record<WorkflowStatus, StatusMeta> = {
  RUNNING: { label: '运行中', cls: 'status-tag--teal', tone: '', icon: 'activity' },
  APPROVED: { label: '已通过', cls: 'status-tag--blue', tone: '', icon: 'check-circle' },
  REJECTED: { label: '已驳回', cls: 'status-tag--danger', tone: 'tile--danger', icon: 'x-circle' },
  WITHDRAWN: { label: '已撤销', cls: 'status-tag--amber', tone: 'tile--amber', icon: 'x-circle' },
  NONE: { label: '未启动', cls: 'status-tag--slate', tone: 'tile--amber', icon: 'pause' },
};

const ACTION_LABELS: Record<string, string> = {
  start: '发起流程',
  claim: '签收任务',
  approve: '审批通过',
  reject: '驳回',
  transfer: '转办',
  withdraw: '撤销流程',
};

const instances = ref<WorkflowInstanceVO[]>([]);
const totalCount = ref(0);
const models = ref<WorkflowModelVO[]>([]);
const loading = ref(false);

const query = ref('');
const activeFilter = ref<InstanceFilter>('all');
const definitionKey = ref('');
const pageNum = ref(1);
const pageSize = 10;

const traceSummary = ref<Record<string, { currentTask: string; assignee: string; stepCount: number }>>({});
const traceLoading = ref<Record<string, boolean>>({});
let enrichToken = 0;

const counts = computed(() => {
  const result = { all: instances.value.length, running: 0, approved: 0, rejected: 0, withdrawn: 0 };
  for (const item of instances.value) {
    if (item.status === 'RUNNING') result.running += 1;
    else if (item.status === 'APPROVED') result.approved += 1;
    else if (item.status === 'REJECTED') result.rejected += 1;
    else if (item.status === 'WITHDRAWN') result.withdrawn += 1;
  }
  return result;
});

const filterOptions = computed<Array<{ key: InstanceFilter; label: string; count: number }>>(() => [
  { key: 'all', label: '全部', count: counts.value.all },
  { key: 'running', label: '运行中', count: counts.value.running },
  { key: 'approved', label: '已通过', count: counts.value.approved },
  { key: 'rejected', label: '已驳回', count: counts.value.rejected },
  { key: 'withdrawn', label: '已撤销', count: counts.value.withdrawn },
]);

const modelNames = computed<Record<string, string>>(() =>
  Object.fromEntries(models.value.map((item) => [item.modelKey, item.name]))
);

const definitionOptions = computed(() => {
  const keys = Array.from(new Set(instances.value.map((item) => item.definitionKey)));
  return keys.map((key) => ({
    key,
    label: modelNames.value[key] ? `${modelNames.value[key]} · ${key}` : key,
  }));
});

const filtered = computed(() => {
  const keyword = query.value.trim().toLowerCase();
  return instances.value.filter((item) => {
    if (activeFilter.value !== 'all') {
      const statusMap: Record<Exclude<InstanceFilter, 'all'>, WorkflowStatus> = {
        running: 'RUNNING',
        approved: 'APPROVED',
        rejected: 'REJECTED',
        withdrawn: 'WITHDRAWN',
      };
      if (item.status !== statusMap[activeFilter.value]) return false;
    }
    if (definitionKey.value && item.definitionKey !== definitionKey.value) return false;
    if (keyword) {
      const haystack = `${item.instanceId} ${item.businessKey ?? ''} ${item.title ?? ''} ${item.definitionKey}`.toLowerCase();
      if (!haystack.includes(keyword)) return false;
    }
    return true;
  });
});

const paged = computed(() => {
  const start = (pageNum.value - 1) * pageSize.value;
  return filtered.value.slice(start, start + pageSize.value);
});

watch([query, activeFilter, definitionKey], () => {
  pageNum.value = 1;
});

watch(
  paged,
  (rows) => {
    void enrichVisible(rows);
  },
  { immediate: true }
);

function formatTime(value?: string): string {
  if (!value) return '—';
  return value.slice(0, 19).replace('T', ' ');
}

function durationOf(item: WorkflowInstanceVO): string {
  if (!item.startTime) return '—';
  const start = new Date(item.startTime).getTime();
  const end = item.endTime ? new Date(item.endTime).getTime() : Date.now();
  if (Number.isNaN(start) || Number.isNaN(end)) return '—';
  const minutes = Math.max(0, Math.floor((end - start) / 60000));
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ${minutes % 60}m`;
  return `${Math.floor(hours / 24)}d ${hours % 24}h`;
}

function statusMeta(status: WorkflowStatus): StatusMeta {
  return STATUS_META[status];
}

function instanceName(item: WorkflowInstanceVO): string {
  return item.title || modelNames.value[item.definitionKey] || item.definitionKey;
}

function nodeLabel(item: WorkflowInstanceVO): string {
  if (item.status !== 'RUNNING') return statusMeta(item.status).label;
  const summary = traceSummary.value[item.instanceId];
  if (summary) return summary.currentTask;
  return traceLoading.value[item.instanceId] ? '读取中…' : '—';
}

function nodeSub(item: WorkflowInstanceVO): string {
  if (item.status !== 'RUNNING') return item.endTime ? `结束于 ${formatTime(item.endTime)}` : '已结束';
  const summary = traceSummary.value[item.instanceId];
  if (!summary) return traceLoading.value[item.instanceId] ? '正在读取轨迹' : '—';
  return summary.assignee ? `${summary.assignee} · ${summary.stepCount} 步已办` : `${summary.stepCount} 步已办`;
}

async function enrichVisible(rows: WorkflowInstanceVO[]): Promise<void> {
  const token = ++enrichToken;
  const targets = rows.filter((item) => item.status === 'RUNNING' && !traceSummary.value[item.instanceId]);
  if (!targets.length) return;

  await Promise.allSettled(
    targets.map(async (item) => {
      traceLoading.value[item.instanceId] = true;
      try {
        const trace = await getInstanceTrace(item.instanceId);
        if (token !== enrichToken) return;
        traceSummary.value[item.instanceId] = {
          currentTask: trace.waiting.length
            ? trace.waiting.map((task) => task.taskName || '未命名节点').join(' / ')
            : '引擎处理中',
          assignee: trace.waiting.map((task) => task.assigneeName || '待认领').join(' / '),
          stepCount: trace.steps.length,
        };
      } catch {
        if (token === enrichToken) {
          traceSummary.value[item.instanceId] = { currentTask: '轨迹读取失败', assignee: '', stepCount: 0 };
        }
      } finally {
        if (token === enrichToken) {
          traceLoading.value[item.instanceId] = false;
        }
      }
    })
  );
}

async function loadData(notify = false): Promise<void> {
  loading.value = true;
  try {
    const [page, modelPage] = await Promise.all([
      pageStartedInstances({ pageNum: 1, pageSize: 200 }),
      pageModels({ pageNum: 1, pageSize: 200 }).catch(() => null),
    ]);
    instances.value = page.rows;
    totalCount.value = page.total;
    if (modelPage) models.value = modelPage.rows;
    traceSummary.value = {};
    traceLoading.value = {};
    if (notify) ElMessage.success('实例列表已刷新');
  } catch {
    // 请求层已提示
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  void loadData();
});

function applyFilters(): void {
  pageNum.value = 1;
}

function resetFilters(): void {
  query.value = '';
  definitionKey.value = '';
  activeFilter.value = 'all';
  pageNum.value = 1;
}

const drawerVisible = ref(false);
const drawerTab = ref('overview');
const drawerLoading = ref(false);
const activeInstance = ref<WorkflowInstanceVO | null>(null);
const activeTrace = ref<WorkflowTraceVO | null>(null);
let activeTraceSeq = 0;

const activeSteps = computed(() => activeTrace.value?.steps ?? []);
const waitingTasks = computed(() => activeTrace.value?.waiting ?? []);
const recentSteps = computed(() => activeSteps.value.slice(-4).reverse());
const canWithdraw = computed(() => activeInstance.value?.status === 'RUNNING');

const drawerFootnote = computed(() => {
  if (!activeInstance.value) return '';
  if (activeInstance.value.status === 'RUNNING') return '在途实例可撤销，撤销会终止所有待办任务。';
  if (activeInstance.value.status === 'APPROVED') return '实例已通过结束，当前为只读视图。';
  if (activeInstance.value.status === 'REJECTED') return '实例已驳回结束，当前为只读视图。';
  if (activeInstance.value.status === 'WITHDRAWN') return '实例已由发起人撤销，当前为只读视图。';
  return '实例尚未进入流程。';
});

async function openInstance(item: WorkflowInstanceVO): Promise<void> {
  activeInstance.value = item;
  activeTrace.value = null;
  drawerTab.value = 'overview';
  drawerVisible.value = true;
  await loadActiveTrace();
}

async function loadActiveTrace(): Promise<void> {
  const target = activeInstance.value;
  if (!target) return;
  const seq = ++activeTraceSeq;
  drawerLoading.value = true;
  try {
    const trace = await getInstanceTrace(target.instanceId);
    if (seq !== activeTraceSeq) return;
    activeTrace.value = trace;
    activeInstance.value = trace.instance;
  } catch {
    if (seq === activeTraceSeq) ElMessage.error('实例轨迹读取失败');
  } finally {
    if (seq === activeTraceSeq) drawerLoading.value = false;
  }
}

async function openStartedInstance(instanceId: string): Promise<void> {
  try {
    const instance = await getInstance(instanceId);
    await openInstance(instance);
  } catch {
    ElMessage.warning('实例已发起，详情加载失败，请刷新列表查看');
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

function operatorText(step: { operatorName?: string; operatorId?: number }): string {
  if (step.operatorName) return step.operatorName;
  return step.operatorId ? `用户 #${step.operatorId}` : '系统';
}

const withdrawVisible = ref(false);
const withdrawTarget = ref<WorkflowInstanceVO | null>(null);
const withdrawReason = ref('');
const withdrawSubmitting = ref(false);

function openWithdraw(item: WorkflowInstanceVO | null): void {
  if (!item || item.status !== 'RUNNING') return;
  withdrawTarget.value = item;
  withdrawReason.value = '';
  withdrawVisible.value = true;
}

async function confirmWithdraw(): Promise<void> {
  const target = withdrawTarget.value;
  if (!target || withdrawSubmitting.value) return;
  const reason = withdrawReason.value.trim();
  if (!reason) {
    ElMessage.warning('请填写撤销原因');
    return;
  }
  withdrawSubmitting.value = true;
  try {
    await withdrawInstance(target.instanceId, reason);
    withdrawVisible.value = false;
    drawerVisible.value = false;
    ElMessage.warning('实例已撤销，相关待办已终止');
    await loadData();
  } finally {
    withdrawSubmitting.value = false;
  }
}

const startVisible = ref(false);
const startSubmitting = ref(false);
const startForm = reactive({
  definitionKey: '',
  businessTitle: '',
  businessType: '',
  businessKey: '',
});
const startVariables = ref<Array<{ name: string; value: string }>>([]);

const publishedModels = computed(() =>
  models.value.filter((item) => item.status === 1 && item.deployedVersion != null)
);

function openStart(): void {
  startForm.definitionKey = '';
  startForm.businessTitle = '';
  startForm.businessType = '';
  startForm.businessKey = '';
  startVariables.value = [];
  startVisible.value = true;
}

function addVariable(): void {
  startVariables.value.push({ name: '', value: '' });
}

function removeVariable(index: number): void {
  startVariables.value.splice(index, 1);
}

async function confirmStart(): Promise<void> {
  if (startSubmitting.value) return;
  if (!startForm.definitionKey) {
    ElMessage.warning('请选择流程定义');
    return;
  }
  const businessType = startForm.businessType.trim();
  const businessKey = startForm.businessKey.trim();
  if (!!businessType !== !!businessKey) {
    ElMessage.warning('业务类型与业务键需同时填写或同时留空');
    return;
  }

  startSubmitting.value = true;
  try {
    const variables: Record<string, string> = {};
    for (const item of startVariables.value) {
      const name = item.name.trim();
      if (name) variables[name] = item.value;
    }
    const result = await startInstance({
      definitionKey: startForm.definitionKey,
      businessTitle: startForm.businessTitle.trim() || undefined,
      businessType: businessType || undefined,
      businessKey: businessKey || undefined,
      variables: Object.keys(variables).length ? variables : undefined,
    });
    startVisible.value = false;
    ElMessage.success('流程已发起，实例运行中');
    await loadData();
    await openStartedInstance(result.instanceId);
  } finally {
    startSubmitting.value = false;
  }
}

/** el-table 插槽 row 为 DefaultRow，一次性收窄 */
const asInstance = (row: unknown): WorkflowInstanceVO => row as WorkflowInstanceVO;
</script>

<template>
  <div class="page-head">
    <div>
      <div class="eyebrow">
        FLOW RUNTIME / INSTANCES
      </div>
      <h1>流程实例</h1>
      <p>追踪我发起的流程实例、节点轨迹与耗时；在途实例可主动撤销，已结束实例保持只读。</p>
    </div>
    <div class="page-actions">
      <el-button @click="loadData(true)">
        <AppIcon name="refresh" />刷新
      </el-button>
      <el-button
        type="primary"
        @click="openStart"
      >
        <AppIcon name="plus" />发起流程
      </el-button>
    </div>
  </div>

  <div class="split-stats">
    <div class="mini-stat">
      <AppIcon name="activity" /><b>{{ counts.all }}</b><span>我发起的实例</span>
    </div>
    <div class="mini-stat">
      <AppIcon name="zap" /><b>{{ counts.running }}</b><span>运行中</span>
    </div>
    <div class="mini-stat">
      <AppIcon name="check-circle" /><b>{{ counts.approved + counts.rejected }}</b><span>已结束</span>
    </div>
    <div class="mini-stat">
      <AppIcon name="x-circle" /><b>{{ counts.withdrawn }}</b><span>已撤销</span>
    </div>
  </div>

  <section class="panel">
    <div class="instance-filter-bar">
      <el-radio-group
        v-model="activeFilter"
        class="flow-choices instance-filters"
        aria-label="按实例状态筛选"
      >
        <el-radio-button
          v-for="item in filterOptions"
          :key="item.key"
          :value="item.key"
        >
          {{ item.label }}<span class="num">{{ item.count }}</span>
        </el-radio-button>
      </el-radio-group>
      <span class="toolbar-note"><AppIcon name="user" />仅显示我发起的实例</span>
    </div>

    <div class="toolbar">
      <el-input
        v-model="query"
        class="flow-search"
        placeholder="搜索实例 ID / 业务键 / 标题"
        aria-label="搜索实例、业务键或标题"
        clearable
        @keyup.enter="applyFilters"
      >
        <template #prefix><AppIcon name="search" /></template>
      </el-input>
      <el-select
        v-model="definitionKey"
        class="flow-select"
        placeholder="全部流程"
        aria-label="按流程定义筛选"
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
        @click="applyFilters"
      >
        <AppIcon name="search" />查询
      </el-button>
      <el-button
        size="small"
        link
        class="ghost-btn"
        @click="resetFilters"
      >
        重置
      </el-button>
      <span class="grow"></span>
      <span class="toolbar-note">
        <AppIcon name="filter" />共 {{ totalCount }} 条{{ totalCount > instances.length ? ` · 已加载 ${instances.length} 条` : '' }}
      </span>
    </div>

    <div class="table-wrap">
      <el-table
        v-loading="loading"
        :data="paged"
        class="flow-table instance-table"
        row-key="instanceId"
      >
        <el-table-column
          label="流程实例"
          min-width="210"
        >
          <template #default="{ row }">
            <div class="cell-main">
              <span
                class="tile"
                :class="statusMeta(asInstance(row).status).tone"
              >
                <AppIcon :name="statusMeta(asInstance(row).status).icon" />
              </span>
              <div class="cell-copy">
                <div class="cell-title">
                  {{ instanceName(asInstance(row)) }}
                </div>
                <div class="cell-sub">
                  {{ asInstance(row).instanceId }}
                </div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column
          label="业务键"
          min-width="132"
        >
          <template #default="{ row }">
            <span class="mono strong">{{ asInstance(row).businessKey || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="当前节点"
          min-width="160"
        >
          <template #default="{ row }">
            <div class="instance-node">
              <b>{{ nodeLabel(asInstance(row)) }}</b>
              <span>{{ nodeSub(asInstance(row)) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column
          label="状态"
          min-width="104"
        >
          <template #default="{ row }">
            <el-tag
              class="status-tag"
              :class="statusMeta(asInstance(row).status).cls"
              disable-transitions
            >
              {{ statusMeta(asInstance(row).status).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="发起时间"
          min-width="150"
        >
          <template #default="{ row }">
            <span class="mono">{{ formatTime(asInstance(row).startTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="历时"
          min-width="90"
        >
          <template #default="{ row }">
            <span class="instance-duration">{{ durationOf(asInstance(row)) }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          min-width="150"
          align="right"
        >
          <template #default="{ row }">
            <div class="row-actions">
              <el-button
                link
                size="small"
                @click="openInstance(asInstance(row))"
              >
                <AppIcon name="eye" />查看
              </el-button>
              <el-button
                v-if="asInstance(row).status === 'RUNNING'"
                link
                size="small"
                type="danger"
                @click="openWithdraw(asInstance(row))"
              >
                <AppIcon name="x-circle" />撤销
              </el-button>
            </div>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty
            class="flow-empty"
            description="没有匹配的流程实例"
          >
            <template #image>
              <span class="empty-icon"><AppIcon name="inbox" /></span>
            </template>
            <p class="empty-hint">
              试试更换关键词、流程定义或状态筛选。
            </p>
          </el-empty>
        </template>
      </el-table>
    </div>

    <div class="pager flow-pager-bar">
      <span class="pager-note">筛选后 {{ filtered.length }} 条 · 每页 {{ pageSize }} 条</span>
      <el-pagination
        v-model:current-page="pageNum"
        class="flow-pager"
        layout="prev, pager, next"
        :total="filtered.length"
        :page-size="pageSize"
        :pager-count="5"
      />
    </div>
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
            PROCESS INSTANCE
          </div>
          <h2 class="drawer-title">
            {{ activeInstance ? `${instanceName(activeInstance)} · ${activeInstance.instanceId}` : '' }}
          </h2>
          <p class="drawer-sub">
            <span class="mono">{{ activeInstance?.instanceId }}</span> · 发起于 {{ formatTime(activeInstance?.startTime) }}
          </p>
        </div>
        <button
          class="icon-btn"
          type="button"
          aria-label="关闭实例详情"
          @click="drawerVisible = false"
        >
          <AppIcon name="x" />
        </button>
      </div>
    </template>

    <div
      v-if="activeInstance"
      v-loading="drawerLoading"
    >
      <div class="task-summary" style="margin-bottom: 16px">
        <span
          class="tile"
          :class="statusMeta(activeInstance.status).tone"
        >
          <AppIcon :name="statusMeta(activeInstance.status).icon" />
        </span>
        <div class="task-summary-copy">
          <b>{{ activeInstance.definitionKey }}</b>
          <span>{{ activeInstance.title || activeInstance.businessKey || '手动发起' }}</span>
        </div>
        <el-tag
          class="status-tag"
          :class="statusMeta(activeInstance.status).cls"
          disable-transitions
        >
          {{ statusMeta(activeInstance.status).label }}
        </el-tag>
      </div>

      <el-tabs
        v-model="drawerTab"
        class="flow-tabs flow-tabs--drawer"
      >
        <el-tab-pane
          label="概览"
          name="overview"
        >
          <div class="meta-grid">
            <div class="meta-cell">
              <span>业务键</span><b>{{ activeInstance.businessKey || '—' }}</b>
            </div>
            <div class="meta-cell">
              <span>发起人</span><b>{{ activeInstance.initiatorId ? `用户 #${activeInstance.initiatorId}` : '我' }}</b>
            </div>
            <div class="meta-cell">
              <span>流程定义</span><b>{{ activeInstance.definitionKey }}</b>
            </div>
            <div class="meta-cell">
              <span>开始时间</span><b>{{ formatTime(activeInstance.startTime) }}</b>
            </div>
            <div class="meta-cell">
              <span>结束时间</span><b>{{ formatTime(activeInstance.endTime) }}</b>
            </div>
            <div class="meta-cell">
              <span>历时</span><b>{{ durationOf(activeInstance) }}</b>
            </div>
          </div>

          <div class="inspector-section">
            <h3 class="inspector-section-title">
              <span>当前处理人</span><AppIcon name="user" />
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
                  <span>{{ task.assigneeName ? `处理人 ${task.assigneeName}` : '待候选人认领' }}</span>
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
              {{ activeInstance.status === 'RUNNING' ? '引擎暂未生成待办节点' : '流程已结束，无在途处理人' }}
            </div>
          </div>

          <div class="inspector-section">
            <h3 class="inspector-section-title">
              <span>最近动态</span><AppIcon name="activity" />
            </h3>
            <el-timeline
              v-if="recentSteps.length"
              class="flow-timeline"
            >
              <el-timeline-item
                v-for="(step, index) in recentSteps"
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
                    class="flow-tag"
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
              暂无审批动作
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane
          label="节点轨迹"
          name="trace"
        >
          <el-alert
            class="flow-alert"
            type="info"
            :closable="false"
            show-icon
          >
            轨迹由审批台账中已发生的动作与引擎当前在途节点拼接：前者是办理时的快照，后者是此刻的现值。
          </el-alert>

          <div class="inspector-section">
            <h3 class="inspector-section-title">
              <span>已发生动作</span><span class="mono">{{ activeSteps.length }} STEPS</span>
            </h3>
            <el-timeline
              v-if="activeSteps.length"
              class="flow-timeline"
            >
              <el-timeline-item
                v-for="(step, index) in activeSteps"
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
              暂无审批动作
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
              {{ activeInstance.status === 'RUNNING' ? '引擎暂未生成待办节点' : '流程已结束，无在途节点' }}
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>

    <template #footer>
      <div class="drawer-foot">
        <span class="foot-note">{{ drawerFootnote }}</span>
        <div class="task-drawer-actions">
          <el-button
            v-if="canWithdraw"
            type="danger"
            plain
            @click="openWithdraw(activeInstance)"
          >
            <AppIcon name="x-circle" />撤销流程
          </el-button>
          <el-button @click="drawerVisible = false">
            关闭
          </el-button>
        </div>
      </div>
    </template>
  </el-drawer>

  <el-dialog
    v-model="startVisible"
    class="flow-dialog"
    width="640px"
    align-center
    :show-close="false"
  >
    <template #header>
      <div class="dialog-head">
        <div class="dialog-head-copy">
          <div class="dialog-kicker">
            START INSTANCE
          </div>
          <h2 class="dialog-title">
            发起流程实例
          </h2>
          <p class="dialog-desc">
            选择已发布定义并补充业务键与流程变量，实例将锁定当前定义版本。
          </p>
        </div>
        <button
          class="icon-btn dialog-close"
          type="button"
          aria-label="关闭"
          @click="startVisible = false"
        >
          <AppIcon name="x" />
        </button>
      </div>
    </template>

    <el-alert
      class="flow-alert flow-alert--warning"
      type="warning"
      :closable="false"
      show-icon
    >
      业务类型与业务键需同时填写或同时留空；多实例会签流程需在变量中提供 <span class="mono">assigneeList</span>，多个处理人以英文逗号分隔。
    </el-alert>

    <el-form
      label-position="top"
      style="margin-top: 16px"
      @submit.prevent
    >
      <div class="form-grid">
        <el-form-item
          class="span-2"
          label="流程定义"
          required
        >
          <el-select
            v-model="startForm.definitionKey"
            style="width: 100%"
            placeholder="选择已发布流程"
            :disabled="!publishedModels.length"
          >
            <el-option
              v-for="item in publishedModels"
              :key="item.modelId"
              :label="`${item.name} · ${item.modelKey} · v${item.deployedVersion}`"
              :value="item.modelKey"
            />
          </el-select>
          <span
            class="field-hint"
            style="display: block; margin-top: 6px"
          >仅列出状态为已发布的模型；停用流程不会出现在这里</span>
        </el-form-item>
        <el-form-item
          class="span-2"
          label="实例标题"
        >
          <el-input
            v-model="startForm.businessTitle"
            placeholder="如 采购服务器 · 11 月批次"
            maxlength="120"
          />
        </el-form-item>
        <el-form-item label="业务类型">
          <el-input
            v-model="startForm.businessType"
            placeholder="如 purchase_order"
            maxlength="64"
          />
        </el-form-item>
        <el-form-item label="业务键">
          <el-input
            v-model="startForm.businessKey"
            placeholder="如 PO-2026-1043"
            maxlength="64"
          />
        </el-form-item>
      </div>
    </el-form>

    <div
      v-if="!publishedModels.length"
      class="drawer-empty"
      style="margin-top: 12px"
    >
      当前没有可发起的已发布流程，请先在流程建模完成发布。
    </div>

    <div class="start-vars">
      <div class="start-var-head">
        <h3 class="inspector-section-title">
          <span>流程变量</span><span class="mono">{{ startVariables.length }} VALUES</span>
        </h3>
        <el-button
          size="small"
          link
          @click="addVariable"
        >
          <AppIcon name="plus" />添加变量
        </el-button>
      </div>
      <div class="variables">
        <div
          v-for="(variable, index) in startVariables"
          :key="index"
          class="var-row"
        >
          <el-input
            v-model="variable.name"
            placeholder="变量名"
            aria-label="变量名"
          />
          <el-input
            v-model="variable.value"
            placeholder="变量值"
            aria-label="变量值"
          />
          <el-button
            link
            class="var-remove"
            aria-label="删除变量"
            @click="removeVariable(index)"
          >
            <AppIcon name="x" />
          </el-button>
        </div>
      </div>
      <p
        v-if="!startVariables.length"
        class="field-hint"
      >
        变量值按字符串提交；同名变量以后出现的为准。
      </p>
    </div>

    <template #footer>
      <div class="dialog-foot">
        <span class="foot-note">发起后实例进入运行中，流程定义锁定为所选版本。</span>
        <el-button @click="startVisible = false">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="startSubmitting"
          :disabled="!publishedModels.length"
          @click="confirmStart"
        >
          <AppIcon name="send" />发起流程
        </el-button>
      </div>
    </template>
  </el-dialog>

  <el-dialog
    v-model="withdrawVisible"
    class="flow-dialog"
    width="540px"
    align-center
    :show-close="false"
  >
    <template #header>
      <div class="dialog-head">
        <div class="dialog-head-copy">
          <div
            class="dialog-kicker"
            style="color: var(--danger)"
          >
            DESTRUCTIVE ACTION
          </div>
          <h2 class="dialog-title">
            撤销流程实例
          </h2>
          <p class="dialog-desc">
            撤销后不可恢复，请确认实例状态并填写撤销原因。
          </p>
        </div>
        <button
          class="icon-btn dialog-close"
          type="button"
          aria-label="关闭"
          @click="withdrawVisible = false"
        >
          <AppIcon name="x" />
        </button>
      </div>
    </template>

    <el-alert
      v-if="withdrawTarget"
      class="flow-alert flow-alert--error"
      type="error"
      :closable="false"
      show-icon
    >
      确认撤销「<b>{{ instanceName(withdrawTarget) }}</b>」实例 <span class="mono">{{ withdrawTarget.instanceId }}</span> 吗？所有运行中的任务会被终止，相关处理人将收到通知。
    </el-alert>

    <el-form
      label-position="top"
      style="margin-top: 14px"
      @submit.prevent
    >
      <el-form-item
        label="撤销原因"
        required
      >
        <el-input
          v-model="withdrawReason"
          type="textarea"
          :rows="3"
          maxlength="200"
          show-word-limit
          placeholder="请说明撤销原因，将记录到审批台账"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <div class="dialog-foot">
        <span class="foot-note">已完成或已撤销的实例不可再次撤销。</span>
        <el-button @click="withdrawVisible = false">
          取消
        </el-button>
        <el-button
          type="danger"
          :loading="withdrawSubmitting"
          :disabled="!withdrawReason.trim()"
          @click="confirmWithdraw"
        >
          <AppIcon name="x-circle" />确认撤销
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
.instance-filter-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px 12px;
  padding: 13px 18px;
  border-bottom: 1px solid var(--line);
}

.instance-filter-bar .toolbar-note {
  margin-left: auto;
}

.instance-filters {
  flex: 1 1 auto;
  gap: 7px;
}

.instance-filters .num {
  margin-left: 1px;
  font-family: var(--font-mono);
  font-size: 9.5px;
  opacity: 0.8;
}

.instance-table .cell-title {
  font-size: 11.5px;
}

.instance-table .cell-sub {
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

.instance-duration {
  color: var(--text-2);
  font-family: var(--font-mono);
  font-size: 10px;
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

.tile.tile--danger {
  border-color: var(--danger-line);
  background: var(--danger-soft);
  color: var(--danger-bright);
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

.start-vars {
  margin-top: 18px;
  padding-top: 15px;
  border-top: 1px solid var(--line);
}

.start-var-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.start-var-head .inspector-section-title {
  margin: 0;
}

.var-row :deep(.el-button.var-remove) {
  width: 30px;
  height: 38px;
  padding: 0;
}

.var-row :deep(.el-button.var-remove .ic) {
  width: 14px;
  height: 14px;
}

.span-2 {
  grid-column: 1 / -1;
}

@media (max-width: 860px) {
  .instance-filter-bar .toolbar-note {
    display: none;
  }

  .toolbar .flow-search,
  .toolbar .flow-select {
    width: 100%;
  }
}

@media (max-width: 560px) {
  .instance-filter-bar {
    padding: 12px 14px;
  }

  .task-drawer-actions {
    width: 100%;
  }

  .task-drawer-actions :deep(.el-button) {
    flex: 1;
    justify-content: center;
  }
}
</style>
