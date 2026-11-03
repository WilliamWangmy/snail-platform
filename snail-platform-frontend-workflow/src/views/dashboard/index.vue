<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';

import { pageStartedInstances, type WorkflowInstanceVO } from '@/api/workflow/instance';
import { pageModels, type WorkflowModelVO } from '@/api/workflow/model';
import {
  pageDoneTasks,
  pageTodoTasks,
  type WorkflowDoneVO,
  type WorkflowTodoVO,
} from '@/api/workflow/task';
import AppIcon from '@/components/AppIcon.vue';
import { useAuthStore } from '@/stores/auth';

interface DayBucket {
  key: string;
  label: string;
  started: number;
  ended: number;
  todo: number;
  running: number;
  durationTotal: number;
  durationCount: number;
}

interface ActivityItem {
  key: string;
  time?: string;
  verb: string;
  target: string;
  ref: string;
}

interface StatusSegment {
  key: string;
  label: string;
  color: string;
  count: number;
}

const DAY_COUNT = 30;
const SAMPLE_SIZE = 200;
const CHART_LEFT = 40;
const CHART_RIGHT = 735;
const CHART_TOP = 36;
const CHART_BOTTOM = 204;

const router = useRouter();
const auth = useAuthStore();

const loading = ref(true);
const syncedAt = ref('');
const trendRange = ref<'7' | '14' | '30'>('14');

const started = ref<WorkflowInstanceVO[]>([]);
const startedTotal = ref(0);
const models = ref<WorkflowModelVO[]>([]);
const todos = ref<WorkflowTodoVO[]>([]);
const todoTotal = ref(0);
const done = ref<WorkflowDoneVO[]>([]);
const doneTotal = ref(0);

const rangeOptions = [
  { label: '7 天', value: '7' },
  { label: '14 天', value: '14' },
  { label: '30 天', value: '30' },
];

function pad2(value: number): string {
  return String(value).padStart(2, '0');
}

function dateKey(value: Date): string {
  return `${value.getFullYear()}-${pad2(value.getMonth() + 1)}-${pad2(value.getDate())}`;
}

function parseTime(value?: string): number {
  if (!value) return Number.NaN;
  const parsed = new Date(value.replace(' ', 'T')).getTime();
  return Number.isNaN(parsed) ? Number.NaN : parsed;
}

function relativeTime(value?: string): string {
  const time = parseTime(value);
  if (Number.isNaN(time)) return '—';
  const diff = Date.now() - time;
  if (diff < 60_000) return '刚刚';
  const minutes = Math.floor(diff / 60_000);
  if (minutes < 60) return `${minutes} 分钟前`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} 小时前`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days} 天前`;
  const date = new Date(time);
  return `${pad2(date.getMonth() + 1)}-${pad2(date.getDate())} ${pad2(date.getHours())}:${pad2(date.getMinutes())}`;
}

function instanceLabel(row: { instanceName?: string; definitionKey?: string }): string {
  return row.instanceName || row.definitionKey || '未命名流程';
}

/** 近 30 天逐日桶：发起 / 结束 / 新增待办 / 结束耗时，供趋势图与迷你走势共用 */
const days = computed<DayBucket[]>(() => {
  const buckets: DayBucket[] = [];
  const today = new Date();
  for (let offset = DAY_COUNT - 1; offset >= 0; offset -= 1) {
    const date = new Date(today.getFullYear(), today.getMonth(), today.getDate() - offset);
    buckets.push({
      key: dateKey(date),
      label: `${pad2(date.getMonth() + 1)}.${pad2(date.getDate())}`,
      started: 0,
      ended: 0,
      todo: 0,
      running: 0,
      durationTotal: 0,
      durationCount: 0,
    });
  }
  const index = new Map(buckets.map((bucket, i) => [bucket.key, i]));
  const bucketOf = (value?: string): DayBucket | null => {
    const time = parseTime(value);
    if (Number.isNaN(time)) return null;
    const position = index.get(dateKey(new Date(time)));
    return position == null ? null : buckets[position];
  };

  for (const item of started.value) {
    const startBucket = bucketOf(item.startTime);
    if (startBucket) {
      startBucket.started += 1;
      if (item.status === 'RUNNING') startBucket.running += 1;
    }
    const endBucket = bucketOf(item.endTime);
    if (endBucket) {
      endBucket.ended += 1;
      const from = parseTime(item.startTime);
      const to = parseTime(item.endTime);
      if (!Number.isNaN(from) && to >= from) {
        endBucket.durationTotal += to - from;
        endBucket.durationCount += 1;
      }
    }
  }

  for (const item of todos.value) {
    const bucket = bucketOf(item.createTime);
    if (bucket) bucket.todo += 1;
  }

  return buckets;
});

const trend = computed(() => days.value.slice(DAY_COUNT - Number(trendRange.value)));

const trendTotals = computed(() => {
  const startedSum = trend.value.reduce((sum, day) => sum + day.started, 0);
  const endedSum = trend.value.reduce((sum, day) => sum + day.ended, 0);
  const rate = startedSum ? Math.round((endedSum / startedSum) * 100) : 0;
  return { started: startedSum, ended: endedSum, rate };
});

const chart = computed(() => {
  const points = trend.value;
  const lastIndex = Math.max(1, points.length - 1);
  const maxValue = Math.max(1, ...points.map((point) => Math.max(point.started, point.ended)));
  const step = Math.max(1, Math.ceil(maxValue / 3));
  const axisMax = step * 3;
  const xOf = (index: number): number => CHART_LEFT + ((CHART_RIGHT - CHART_LEFT) * index) / lastIndex;
  const yOf = (value: number): number =>
    CHART_BOTTOM - ((CHART_BOTTOM - CHART_TOP) * value) / axisMax;
  const lineOf = (key: 'started' | 'ended'): string =>
    points
      .map((point, i) => `${i === 0 ? 'M' : 'L'}${xOf(i).toFixed(1)} ${yOf(point[key]).toFixed(1)}`)
      .join(' ');

  const ticks = [3, 2, 1, 0].map((factor) => ({
    value: step * factor,
    y: yOf(step * factor),
  }));

  const labelEvery = Math.max(1, Math.ceil(lastIndex / 6));
  const xLabels = points
    .map((point, i) => ({
      label: point.label,
      x: xOf(i),
      anchor: i === 0 ? 'start' : i === lastIndex ? 'end' : 'middle',
    }))
    .filter((_, i) => i % labelEvery === 0 || i === lastIndex);

  const peakIndex = points.reduce(
    (best, point, i) => (point.started > points[best].started ? i : best),
    0
  );
  const peakPoint = points[peakIndex];
  const peakX = xOf(peakIndex);
  const peak =
    peakPoint.started > 0
      ? {
          x: peakX,
          y: yOf(peakPoint.started),
          endedY: yOf(peakPoint.ended),
          label: peakPoint.label,
          started: peakPoint.started,
          ended: peakPoint.ended,
          tooltipLeft: Math.min(Math.max(peakX + 14, 10), CHART_RIGHT - 158),
        }
      : null;

  return {
    startedPath: lineOf('started'),
    endedPath: lineOf('ended'),
    areaPath: `${lineOf('started')} L${CHART_RIGHT} ${CHART_BOTTOM} L${CHART_LEFT} ${CHART_BOTTOM}Z`,
    ticks,
    xLabels,
    peak,
  };
});

function sparkPath(values: number[]): { line: string; area: string } | null {
  if (!values.length || values.every((value) => value === 0)) return null;
  const max = Math.max(...values);
  const min = Math.min(...values);
  const span = max - min || 1;
  const points = values.map((value, i) => {
    const x = 1 + (84 * i) / Math.max(1, values.length - 1);
    const y = 27 - ((value - min) / span) * 22;
    return `${i === 0 ? 'M' : 'L'}${x.toFixed(1)} ${y.toFixed(1)}`;
  });
  const line = points.join(' ');
  return { line, area: `${line} L85 30 L1 30Z` };
}

const spark = computed(() => {
  const recent = days.value.slice(DAY_COUNT - 14);
  return {
    running: sparkPath(recent.map((day) => day.running)),
    started: sparkPath(recent.map((day) => day.started)),
    todo: sparkPath(recent.map((day) => day.todo)),
    duration: sparkPath(
      recent.map((day) =>
        day.durationCount ? Math.round(day.durationTotal / day.durationCount / 3_600_000) : 0
      )
    ),
  };
});

const runningCount = computed(
  () => started.value.filter((item) => item.status === 'RUNNING').length
);

const todayStart = computed(() => {
  const now = new Date();
  return new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
});

const todayCount = computed(
  () =>
    started.value.filter((item) => {
      const time = parseTime(item.startTime);
      return !Number.isNaN(time) && time >= todayStart.value;
    }).length
);

const yesterdayCount = computed(() => {
  const from = todayStart.value - 86_400_000;
  return started.value.filter((item) => {
    const time = parseTime(item.startTime);
    return !Number.isNaN(time) && time >= from && time < todayStart.value;
  }).length;
});

const monthStartedCount = computed(() =>
  days.value.reduce((sum, day) => sum + day.started, 0)
);

const dueSoonCount = computed(() => {
  const now = Date.now();
  return todos.value.filter((item) => {
    const due = parseTime(item.dueDate);
    if (Number.isNaN(due)) return false;
    const diff = due - now;
    return diff >= 0 && diff <= 86_400_000;
  }).length;
});

const unclaimedCount = computed(() => todos.value.filter((item) => !item.claimed).length);

const completedDurations = computed(() =>
  started.value
    .map((item) => ({ from: parseTime(item.startTime), to: parseTime(item.endTime) }))
    .filter(
      (item) =>
        !Number.isNaN(item.from) && !Number.isNaN(item.to) && item.to >= item.from
    )
    .map((item) => item.to - item.from)
);

const averageDuration = computed(() => {
  const list = completedDurations.value;
  if (!list.length) return null;
  return list.reduce((sum, value) => sum + value, 0) / list.length;
});

const averageDurationText = computed<{ value: string; unit: string }>(() => {
  const duration = averageDuration.value;
  if (duration == null) return { value: '—', unit: '' };
  const minutes = Math.max(0, Math.round(duration / 60_000));
  if (minutes < 60) return { value: String(minutes), unit: '分钟' };
  const hours = minutes / 60;
  if (hours < 24) return { value: hours.toFixed(1), unit: '小时' };
  return { value: (hours / 24).toFixed(1), unit: '天' };
});

const publishedModelCount = computed(() => models.value.filter((item) => item.status === 1).length);

const modelRanks = computed(() => {
  let published = 0;
  let draft = 0;
  let unpublished = 0;
  let disabled = 0;
  for (const model of models.value) {
    if (model.status === 1) published += 1;
    else if (model.status === 2) disabled += 1;
    else if (model.hasDraft) draft += 1;
    else unpublished += 1;
  }
  const total = models.value.length || 1;
  return [
    { no: '01', name: '已发布', value: published, percent: Math.round((published / total) * 100) },
    { no: '02', name: '草稿待发布', value: draft, percent: Math.round((draft / total) * 100) },
    { no: '03', name: '未发布', value: unpublished, percent: Math.round((unpublished / total) * 100) },
    { no: '04', name: '已停用', value: disabled, percent: Math.round((disabled / total) * 100) },
  ];
});

const statusSegments = computed<StatusSegment[]>(() => {
  const counts = { APPROVED: 0, RUNNING: 0, REJECTED: 0, WITHDRAWN: 0, NONE: 0 };
  for (const item of started.value) {
    counts[item.status] += 1;
  }
  return [
    { key: 'APPROVED', label: '已完成', color: 'var(--teal)', count: counts.APPROVED },
    { key: 'RUNNING', label: '运行中', color: 'var(--blue)', count: counts.RUNNING },
    { key: 'REJECTED', label: '已驳回', color: 'var(--danger)', count: counts.REJECTED },
    { key: 'WITHDRAWN', label: '已撤销', color: '#4b666a', count: counts.WITHDRAWN },
    { key: 'NONE', label: '未启动', color: '#38545a', count: counts.NONE },
  ];
});

const visibleSegments = computed(() => statusSegments.value.filter((item) => item.count > 0));

const donutStyle = computed(() => {
  const total = started.value.length;
  if (!total) return { background: 'conic-gradient(#38545a 0 100%)' };
  let offset = 0;
  const stops = visibleSegments.value.map((segment) => {
    const from = (offset / total) * 100;
    offset += segment.count;
    const to = (offset / total) * 100;
    return `${segment.color} ${from.toFixed(1)}% ${to.toFixed(1)}%`;
  });
  return { background: `conic-gradient(${stops.join(', ')})` };
});

const todoPreview = computed(() => todos.value.slice(0, 5));

function todoPriority(row: WorkflowTodoVO): '' | 'mid' | 'high' {
  const due = parseTime(row.dueDate);
  if (Number.isNaN(due)) return '';
  const diff = due - Date.now();
  if (diff < 0) return 'high';
  if (diff <= 86_400_000) return 'mid';
  return '';
}

function todoTitle(row: WorkflowTodoVO): string {
  return `${row.taskName} · ${instanceLabel(row)}`;
}

function todoSub(row: WorkflowTodoVO): string {
  return row.businessKey || row.instanceName || row.definitionKey || row.instanceId;
}

const displayName = computed(() => auth.displayName || '我');
const avatarText = computed(() => displayName.value.slice(0, 1).toUpperCase());

const activities = computed<ActivityItem[]>(() => {
  const items: ActivityItem[] = [];
  for (const item of started.value) {
    if (!item.startTime) continue;
    items.push({
      key: `start-${item.instanceId}`,
      time: item.startTime,
      verb: '发起了',
      target: instanceLabel(item),
      ref: item.businessKey || item.instanceId,
    });
  }
  for (const item of done.value) {
    if (!item.handledAt) continue;
    items.push({
      key: `done-${item.taskId}`,
      time: item.handledAt,
      verb: '办理了',
      target: item.taskName,
      ref: item.instanceName || item.businessKey || item.instanceId,
    });
  }
  return items
    .sort((a, b) => (parseTime(b.time) || 0) - (parseTime(a.time) || 0))
    .slice(0, 5);
});

async function loadData(): Promise<void> {
  loading.value = true;
  const [startedResult, todoResult, doneResult, modelResult] = await Promise.allSettled([
    pageStartedInstances({ pageNum: 1, pageSize: SAMPLE_SIZE }),
    pageTodoTasks({ pageNum: 1, pageSize: SAMPLE_SIZE }),
    pageDoneTasks({ pageNum: 1, pageSize: SAMPLE_SIZE }),
    pageModels({ pageNum: 1, pageSize: SAMPLE_SIZE }),
  ]);

  if (startedResult.status === 'fulfilled') {
    started.value = startedResult.value.rows;
    startedTotal.value = startedResult.value.total;
  }
  if (todoResult.status === 'fulfilled') {
    todos.value = todoResult.value.rows;
    todoTotal.value = todoResult.value.total;
  }
  if (doneResult.status === 'fulfilled') {
    done.value = doneResult.value.rows;
    doneTotal.value = doneResult.value.total;
  }
  if (modelResult.status === 'fulfilled') {
    models.value = modelResult.value.rows;
  }

  syncedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false });
  loading.value = false;
}

onMounted(() => {
  void loadData();
});
</script>

<template>
  <div
    v-loading="loading"
    class="dash"
  >
    <div class="page-head">
      <div>
        <div class="eyebrow">
          WORKSPACE / OVERVIEW
        </div>
        <h1>流程运行态势</h1>
        <p>统计范围：我的发起实例与我的待办 / 已办任务（各取最近 {{ SAMPLE_SIZE }} 条）。</p>
      </div>
      <div class="page-actions">
        <div class="dash-meta">
          <span class="live-dot">LIVE</span>
          <span>{{ syncedAt ? `最后同步 ${syncedAt}` : '同步中' }}</span>
        </div>
        <el-button @click="router.push('/instance')">
          <AppIcon name="play" />发起流程
        </el-button>
        <el-button
          type="primary"
          @click="router.push('/modeler')"
        >
          <AppIcon name="plus" />新建模型
        </el-button>
      </div>
    </div>

    <section
      class="stat-grid"
      aria-label="关键指标"
    >
      <article class="stat-card">
        <div class="stat-top">
          <span>运行中实例</span>
          <span class="stat-icon"><AppIcon name="activity" /></span>
        </div>
        <div class="stat-value">
          {{ runningCount }}<small>个</small>
        </div>
        <div class="stat-meta">
          <span class="stat-delta"><AppIcon name="activity" /> 在途</span>
          <span>我发起共 {{ startedTotal }} 个</span>
        </div>
        <svg
          v-if="spark.running"
          class="spark"
          viewBox="0 0 86 30"
          aria-hidden="true"
        >
          <path
            class="spark-line"
            :d="spark.running.line"
            fill="none"
            stroke-width="1.5"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
          <path
            class="spark-fill"
            :d="spark.running.area"
          />
        </svg>
      </article>

      <article class="stat-card stat-card--amber">
        <div class="stat-top">
          <span>待办任务</span>
          <span class="stat-icon"><AppIcon name="task" /></span>
        </div>
        <div class="stat-value">
          {{ todoTotal }}<small>条</small>
        </div>
        <div class="stat-meta">
          <span class="stat-delta down"><AppIcon name="clock" /> {{ dueSoonCount }} 条</span>
          <span>24 小时内到期 · {{ unclaimedCount }} 条未签收</span>
        </div>
        <svg
          v-if="spark.todo"
          class="spark spark--amber"
          viewBox="0 0 86 30"
          aria-hidden="true"
        >
          <path
            class="spark-line"
            :d="spark.todo.line"
            fill="none"
            stroke-width="1.5"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
          <path
            class="spark-fill"
            :d="spark.todo.area"
          />
        </svg>
      </article>

      <article class="stat-card stat-card--blue">
        <div class="stat-top">
          <span>今日发起</span>
          <span class="stat-icon"><AppIcon name="send" /></span>
        </div>
        <div class="stat-value">
          {{ todayCount }}<small>个</small>
        </div>
        <div class="stat-meta">
          <span class="stat-delta"><AppIcon name="trend-up" /> {{ yesterdayCount }}</span>
          <span>昨日发起 · 近 30 天 {{ monthStartedCount }} 个</span>
        </div>
        <svg
          v-if="spark.started"
          class="spark spark--blue"
          viewBox="0 0 86 30"
          aria-hidden="true"
        >
          <path
            class="spark-line"
            :d="spark.started.line"
            fill="none"
            stroke-width="1.5"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
          <path
            class="spark-fill"
            :d="spark.started.area"
          />
        </svg>
      </article>

      <article class="stat-card">
        <div class="stat-top">
          <span>平均流转时长</span>
          <span class="stat-icon"><AppIcon name="clock" /></span>
        </div>
        <div class="stat-value">
          {{ averageDurationText.value }}<small>{{ averageDurationText.unit }}</small>
        </div>
        <div class="stat-meta">
          <span class="stat-delta"><AppIcon name="check" /> {{ completedDurations.length }}</span>
          <span>个实例已结束</span>
        </div>
        <svg
          v-if="spark.duration"
          class="spark spark--violet"
          viewBox="0 0 86 30"
          aria-hidden="true"
        >
          <path
            class="spark-line"
            :d="spark.duration.line"
            fill="none"
            stroke-width="1.5"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
          <path
            class="spark-fill"
            :d="spark.duration.area"
          />
        </svg>
      </article>
    </section>

    <section class="grid-2">
      <article class="panel">
        <div class="panel-head">
          <div>
            <h2 class="panel-title">
              <AppIcon name="activity" />流程流转趋势
            </h2>
            <p class="panel-sub">
              我发起的实例 · 按发起日与结束日聚合
            </p>
          </div>
          <el-segmented
            v-model="trendRange"
            class="flow-segmented"
            :options="rangeOptions"
            aria-label="时间范围"
          />
        </div>
        <div class="panel-body">
          <div class="trend-head">
            <div>
              <div class="trend-total">
                <b>{{ trendTotals.started }}</b>
                <span>近 {{ trendRange }} 天发起</span>
              </div>
              <div class="trend-legend">
                <span class="legend-item"><span class="legend-dot"></span>发起</span>
                <span class="legend-item"><span class="legend-dot legend-dot--amber"></span>结束</span>
              </div>
            </div>
            <div class="trend-total">
              <b>{{ trendTotals.ended }}</b>
              <span>近 {{ trendRange }} 天结束 · 结束率 {{ trendTotals.rate }}%</span>
            </div>
          </div>

          <svg
            v-if="trendTotals.started || trendTotals.ended"
            class="trend-chart"
            viewBox="0 0 760 248"
            role="img"
            :aria-label="`近 ${trendRange} 天我发起与结束的实例趋势折线图`"
          >
            <defs>
              <linearGradient
                id="dashAreaGradient"
                x1="0"
                y1="0"
                x2="0"
                y2="1"
              >
                <stop
                  offset="0%"
                  stop-color="rgba(30,201,165,.18)"
                />
                <stop
                  offset="100%"
                  stop-color="rgba(30,201,165,0)"
                />
              </linearGradient>
            </defs>
            <g class="chart-grid">
              <line
                v-for="tick in chart.ticks"
                :key="tick.value"
                :x1="CHART_LEFT"
                :x2="CHART_RIGHT"
                :y1="tick.y"
                :y2="tick.y"
              />
            </g>
            <g class="chart-label">
              <text
                v-for="tick in chart.ticks"
                :key="`y-${tick.value}`"
                :x="CHART_LEFT - 10"
                :y="tick.y + 3"
                text-anchor="end"
              >{{ tick.value }}</text>
              <text
                v-for="label in chart.xLabels"
                :key="label.label"
                :x="label.x"
                y="230"
                :text-anchor="label.anchor"
              >{{ label.label }}</text>
            </g>
            <path
              class="chart-area"
              :d="chart.areaPath"
            />
            <path
              class="chart-line primary"
              :d="chart.startedPath"
            />
            <path
              class="chart-line secondary"
              :d="chart.endedPath"
            />
            <template v-if="chart.peak">
              <line
                :x1="chart.peak.x"
                :x2="chart.peak.x"
                y1="52"
                :y2="CHART_BOTTOM"
                stroke="rgba(232,173,85,.28)"
                stroke-width="1"
                stroke-dasharray="3 4"
              />
              <circle
                class="chart-marker"
                :cx="chart.peak.x"
                :cy="chart.peak.y"
                r="4"
              />
              <circle
                :cx="chart.peak.x"
                :cy="chart.peak.endedY"
                r="3"
                fill="#071820"
                stroke="var(--amber)"
                stroke-width="1.5"
              />
              <g>
                <rect
                  :x="chart.peak.tooltipLeft"
                  y="26"
                  width="152"
                  height="44"
                  rx="8"
                  fill="#0d2731"
                  stroke="rgba(151,199,200,.2)"
                />
                <text
                  :x="chart.peak.tooltipLeft + 12"
                  y="44"
                  fill="#7b9592"
                  font-size="8.5"
                  font-family="'JetBrains Mono', Consolas, monospace"
                >{{ chart.peak.label }} · 峰值</text>
                <text
                  :x="chart.peak.tooltipLeft + 12"
                  y="60"
                  fill="#e8f3f0"
                  font-size="10"
                  font-family="'PingFang SC', 'Microsoft YaHei', sans-serif"
                >发起 {{ chart.peak.started }} · 结束 {{ chart.peak.ended }}</text>
              </g>
            </template>
          </svg>
          <div
            v-else
            class="empty dash-empty"
          >
            <span class="empty-icon"><AppIcon name="activity" /></span>
            <p>近 {{ trendRange }} 天暂无发起或结束记录</p>
          </div>
        </div>
      </article>

      <article class="panel">
        <div class="panel-head">
          <div>
            <h2 class="panel-title">
              <AppIcon name="target" />实例状态分布
            </h2>
            <p class="panel-sub">
              我发起的流程 · 最近 {{ started.length }} 条
            </p>
          </div>
          <RouterLink
            class="panel-link"
            to="/instance"
          >
            查看全部<AppIcon name="arrow-up-right" />
          </RouterLink>
        </div>
        <div class="donut-wrap">
          <div
            class="donut"
            :style="donutStyle"
          >
            <div class="donut-center">
              <b>{{ started.length }}</b>
              <span>在册实例</span>
            </div>
          </div>
          <div class="donut-legend">
            <div
              v-for="segment in visibleSegments"
              :key="segment.key"
              class="donut-row"
            >
              <span
                class="legend-dot"
                :style="{ background: segment.color }"
              ></span><span>{{ segment.label }}</span><b>{{ segment.count }}</b>
            </div>
            <div
              v-if="!visibleSegments.length"
              class="donut-row"
            >
              <span class="legend-dot legend-dot--slate"></span><span>暂无实例</span><b>0</b>
            </div>
          </div>
        </div>
      </article>
    </section>

    <section class="grid-3">
      <article class="panel">
        <div class="panel-head">
          <div>
            <h2 class="panel-title">
              <AppIcon name="layers" />模型状态构成
            </h2>
            <p class="panel-sub">
              全部流程模型 · 已发布 {{ publishedModelCount }} 个
            </p>
          </div>
          <RouterLink
            class="panel-link"
            to="/modeler"
          >
            模型管理<AppIcon name="arrow-up-right" />
          </RouterLink>
        </div>
        <div class="panel-body">
          <div class="rank-list">
            <div
              v-for="rank in modelRanks"
              :key="rank.no"
              class="rank-row"
            >
              <span class="rank-no">{{ rank.no }}</span>
              <div class="rank-main">
                <div class="rank-name">
                  <span>{{ rank.name }}</span><span>{{ rank.value }} 个</span>
                </div>
                <el-progress
                  class="rank-progress"
                  :class="{ 'rank-progress--amber': rank.no === '02' || rank.no === '04' }"
                  :percentage="rank.percent"
                  :show-text="false"
                  :stroke-width="4"
                />
              </div>
            </div>
          </div>
        </div>
      </article>

      <article class="panel">
        <div class="panel-head">
          <div>
            <h2 class="panel-title">
              <AppIcon name="inbox" />待我处理
            </h2>
            <p class="panel-sub">
              按到达时间排序
            </p>
          </div>
          <RouterLink
            class="panel-link"
            to="/task"
          >
            任务中心<AppIcon name="arrow-up-right" />
          </RouterLink>
        </div>
        <div class="panel-body">
          <div
            v-if="todoPreview.length"
            class="todo-list"
          >
            <RouterLink
              v-for="item in todoPreview"
              :key="item.taskId"
              class="todo-item"
              to="/task"
            >
              <span
                class="pri"
                :class="todoPriority(item) ? `pri--${todoPriority(item)}` : ''"
              ></span>
              <div class="todo-copy">
                <div class="todo-title">
                  {{ todoTitle(item) }}
                </div>
                <div class="todo-sub">
                  {{ todoSub(item) }}
                </div>
              </div>
              <span class="todo-time">{{ relativeTime(item.createTime) }}</span>
            </RouterLink>
          </div>
          <div
            v-else
            class="empty dash-empty"
          >
            <span class="empty-icon"><AppIcon name="inbox" /></span>
            <p>暂无待办任务</p>
          </div>
        </div>
      </article>

      <article class="panel">
        <div class="panel-head">
          <div>
            <h2 class="panel-title">
              <AppIcon name="zap" />最近动态
            </h2>
            <p class="panel-sub">
              我的发起与办理记录
            </p>
          </div>
          <span class="live-dot">LIVE</span>
        </div>
        <div class="panel-body">
          <div
            v-if="activities.length"
            class="activity"
          >
            <div
              v-for="item in activities"
              :key="item.key"
              class="act-item"
            >
              <span class="act-avatar">{{ avatarText }}</span>
              <div class="act-copy">
                <div class="act-text">
                  <b>{{ displayName }}</b> {{ item.verb }} <em>{{ item.target }}</em>
                </div>
                <div class="act-time">
                  {{ relativeTime(item.time) }} · {{ item.ref }}
                </div>
              </div>
            </div>
          </div>
          <div
            v-else
            class="empty dash-empty"
          >
            <span class="empty-icon"><AppIcon name="zap" /></span>
            <p>暂无动态</p>
          </div>
        </div>
      </article>
    </section>

    <p class="dash-foot">
      数据来自 workflow 实例与任务分页接口 · 已办 {{ doneTotal }} 条 · 模型 {{ models.length }} 个
    </p>
  </div>
</template>

<style scoped lang="scss">
.chart-grid line {
  stroke: var(--line-a09);
  stroke-width: 1;
}

.chart-label text {
  fill: #6f8b88;
  font-family: var(--font-mono);
  font-size: 8.5px;
}

.chart-area {
  fill: url(#dashAreaGradient);
}

.chart-line {
  fill: none;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.chart-line.primary {
  stroke: var(--teal);
}

.chart-line.secondary {
  stroke: var(--amber);
  stroke-width: 1.4;
  stroke-dasharray: 4 4;
  opacity: 0.76;
}

.chart-marker {
  fill: var(--bg-1);
  stroke: var(--teal);
  stroke-width: 2;
}

.panel-head .flow-segmented {
  flex: none;
}

.panel-link {
  text-decoration: none;
}

.todo-item {
  color: inherit;
  text-decoration: none;
}

.todo-item:hover .todo-title {
  color: var(--teal);
}

.rank-progress {
  margin-top: 6px;
}

.rank-progress :deep(.el-progress-bar__outer) {
  background: var(--wash-055);
  border-radius: 99px;
}

.rank-progress :deep(.el-progress-bar__inner) {
  background: linear-gradient(90deg, var(--teal-a50), var(--teal));
  border-radius: 99px;
}

.rank-progress--amber :deep(.el-progress-bar__inner) {
  background: linear-gradient(90deg, var(--amber-a45), var(--amber));
}

.spark .spark-line {
  stroke: var(--teal-a65);
}

.spark .spark-fill {
  fill: var(--teal-a07);
}

.spark--amber .spark-line {
  stroke: var(--amber-a65);
}

.spark--amber .spark-fill {
  fill: var(--amber-a07);
}

.spark--blue .spark-line {
  stroke: var(--blue-a6);
}

.spark--blue .spark-fill {
  fill: var(--blue-a06);
}

.spark--violet .spark-line {
  stroke: var(--violet-a55);
}

.spark--violet .spark-fill {
  fill: var(--violet-a06);
}

.dash-empty {
  padding: 30px 10px;
  font-size: 11px;
}

.dash-foot {
  margin: 16px 0 0;
  color: var(--text-3);
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.03em;
  text-align: right;
}

@media (max-width: 640px) {
  .panel-head {
    flex-wrap: wrap;
  }

  .panel-head .flow-segmented {
    flex: 1 0 100%;
    width: 100%;
  }
}
</style>
