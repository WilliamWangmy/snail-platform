<template>
  <div class="page">
    <!-- 实例信息 + 健康 + 告警计数(§6.6 顶部区) -->
    <el-card shadow="never" class="block">
      <div class="head-row">
        <div class="instance">
          <h2>{{ summary?.instance.name || '—' }}</h2>
          <el-tag size="small" type="info" effect="plain">v{{ summary?.instance.version || '—' }}</el-tag>
          <el-tag v-for="p in summary?.instance.profile ?? []" :key="p" size="small" effect="plain">{{ p }}</el-tag>
        </div>
        <div class="meta">
          <span>启动时间:{{ formatTime(summary?.instance.startTime) }}</span>
          <span>已运行:{{ formatUptime(summary?.instance.uptimeSeconds) }}</span>
          <el-tag :type="summary?.degraded ? 'warning' : 'success'" size="small">
            指标源 {{ summary?.metricsSource || '—' }}{{ summary?.degraded ? ' · 降级' : '' }}
          </el-tag>
        </div>
        <div class="alerts">
          <el-badge :value="summary?.activeAlertCount.critical ?? 0" :hidden="!summary?.activeAlertCount.critical" type="danger">
            <el-button size="small" @click="router.push('/alert')">严重</el-button>
          </el-badge>
          <el-badge :value="summary?.activeAlertCount.warn ?? 0" :hidden="!summary?.activeAlertCount.warn" type="warning">
            <el-button size="small" @click="router.push('/alert')">警告</el-button>
          </el-badge>
          <el-badge :value="summary?.activeAlertCount.info ?? 0" :hidden="!summary?.activeAlertCount.info" type="info">
            <el-button size="small" @click="router.push('/alert')">提示</el-button>
          </el-badge>
        </div>
      </div>
      <div class="health-chips">
        <span v-for="(item, name) in summary?.health ?? {}" :key="name" class="chip" :class="`chip-${item.status}`">
          {{ name }} · {{ item.status.toUpperCase() }}
          <template v-if="item.latencyMs !== null"> · {{ item.latencyMs }}ms</template>
        </span>
        <span v-if="!summary" class="chip chip-skip">健康信息加载中…</span>
      </div>
    </el-card>

    <!-- 8 指标卡 -->
    <div class="card-grid">
      <el-card v-for="card in metricCards" :key="card.key" shadow="never" class="stat-card">
        <p class="stat-label">{{ card.label }}</p>
        <p class="stat-value">
          {{ formatMetric(summary?.metrics?.[card.key]) }}<span class="stat-unit">{{ card.unit }}</span>
        </p>
      </el-card>
    </div>

    <!-- 实时曲线 + 登录趋势 -->
    <el-card shadow="never" class="block">
      <template #header>
        <div class="chart-head">
          <span>实时曲线(最近 {{ realtimeMinutes }} 分钟,环形缓冲)</span>
          <el-select v-model="realtimeSelected" multiple collapse-tags collapse-tags-tooltip size="small" style="width: 420px" placeholder="选择指标">
            <el-option v-for="m in metricOptions" :key="m.value" :label="m.label" :value="m.value" />
          </el-select>
          <el-button size="small" :loading="realtimeLoading" @click="loadRealtime">刷新</el-button>
        </div>
      </template>
      <div ref="realtimeEl" class="chart tall" />
    </el-card>

    <el-card shadow="never" class="block">
      <template #header>
        <div class="chart-head">
          <span>登录趋势(近 7 天,失败含拦截)</span>
          <span class="inline-stat">当前在线:<b>{{ trends?.onlineNow ?? '—' }}</b> 人</span>
        </div>
      </template>
      <div ref="trendEl" class="chart mid" />
    </el-card>

    <!-- 历史曲线 -->
    <el-card shadow="never" class="block">
      <template #header>
        <div class="chart-head">
          <span>历史曲线(本地聚合,跨度 ≤ 7 天)</span>
          <div class="chart-tools">
            <el-select v-model="historyMetric" size="small" style="width: 240px">
              <el-option v-for="m in metricOptions" :key="m.value" :label="m.label" :value="m.value" />
            </el-select>
            <el-date-picker
              v-model="historyRange"
              type="datetimerange"
              size="small"
              value-format="YYYY-MM-DDTHH:mm:ss"
              :clearable="false"
              style="width: 340px"
            />
            <el-select v-model="historyStep" size="small" style="width: 120px">
              <el-option label="1 分钟" :value="60" />
              <el-option label="5 分钟" :value="300" />
              <el-option label="1 小时" :value="3600" />
            </el-select>
            <el-button size="small" :loading="historyLoading" @click="loadHistory">查询</el-button>
          </div>
        </div>
      </template>
      <div ref="historyEl" class="chart mid" />
    </el-card>

    <!-- 数据库面板(2 卡 + 慢 SQL 抽屉) -->
    <el-card shadow="never" class="block">
      <template #header>
        <div class="chart-head">
          <span>数据库</span>
          <el-button size="small" :loading="dbLoading" @click="loadDb">刷新</el-button>
          <el-button size="small" type="primary" plain @click="openSlowDrawer">慢 SQL 详情</el-button>
        </div>
      </template>
      <div class="db-grid">
        <div class="db-card">
          <h4>连接池(Hikari)</h4>
          <el-descriptions :column="2" size="small" border>
            <el-descriptions-item label="状态">{{ db?.pool.available ? '可用' : '不可用' }}</el-descriptions-item>
            <el-descriptions-item label="使用率">{{ percent(db?.pool.usagePercent) }}</el-descriptions-item>
            <el-descriptions-item label="活跃">{{ db?.pool.active ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="空闲">{{ db?.pool.idle ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="总数">{{ db?.pool.total ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="等待">{{ db?.pool.pending ?? '—' }}</el-descriptions-item>
          </el-descriptions>
          <el-descriptions v-if="db?.sql.enabled" :column="2" size="small" border class="mt">
            <el-descriptions-item label="执行速率">{{ fixed(db?.sql.execPerSec, 2) }} 次/s</el-descriptions-item>
            <el-descriptions-item label="平均 RT">{{ fixed(db?.sql.avgRtMs, 1) }} ms</el-descriptions-item>
            <el-descriptions-item label="慢 SQL 阈值">{{ db?.sql.slowThresholdMs ?? '—' }} ms</el-descriptions-item>
            <el-descriptions-item label="近 5 分钟慢 SQL">{{ db?.sql.slowCount5m ?? '—' }} 次</el-descriptions-item>
          </el-descriptions>
        </div>
        <div class="db-card">
          <h4>MySQL 服务端</h4>
          <el-descriptions :column="2" size="small" border>
            <el-descriptions-item label="状态">{{ db?.server.available ? '可用' : '不可用' }}</el-descriptions-item>
            <el-descriptions-item label="版本">{{ db?.server.version || '—' }}</el-descriptions-item>
            <el-descriptions-item label="最大连接数">{{ db?.server.maxConnections ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="当前连接">{{ db?.server.threadsConnected ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="运行线程">{{ db?.server.threadsRunning ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="行锁等待">{{ db?.server.rowLockWaitsPerMin ?? '—' }} 次/min</el-descriptions-item>
          </el-descriptions>
          <p v-if="db?.server.lastError" class="db-error">{{ db.server.lastError }}</p>
        </div>
      </div>
    </el-card>

    <!-- 慢 SQL 抽屉:Top 摘要 + 最近事件 -->
    <el-drawer v-model="slowDrawer" title="慢 SQL 详情(内存近窗)" size="640px">
      <template v-if="slow">
        <p class="drawer-sub">阈值 {{ slow.thresholdMs }} ms · 统计起点 {{ formatTime(slow.since) }}</p>
        <h5>Top 摘要</h5>
        <el-table :data="slow.top" size="small" border>
          <el-table-column label="SQL" min-width="220">
            <template #default="{ row }">
              <span class="sql-text" :title="row.sql">{{ row.sql }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="slowCount" label="次数" width="70" />
          <el-table-column label="最大/平均" width="110">
            <template #default="{ row }">{{ row.maxMs }} / {{ fixed(row.avgMs, 1) }} ms</template>
          </el-table-column>
          <el-table-column label="最近" width="160">
            <template #default="{ row }">{{ formatTime(row.lastTime) }}</template>
          </el-table-column>
        </el-table>
        <h5 class="mt">最近事件</h5>
        <el-table :data="slow.recent" size="small" border>
          <el-table-column label="时间" width="160">
            <template #default="{ row }">{{ formatTime(row.time) }}</template>
          </el-table-column>
          <el-table-column prop="costMs" label="耗时" width="80">
            <template #default="{ row }">{{ row.costMs }} ms</template>
          </el-table-column>
          <el-table-column label="SQL" min-width="220">
            <template #default="{ row }">
              <span class="sql-text" :title="row.sql">{{ row.sql }}</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <el-empty v-else description="暂无数据" />
    </el-drawer>
  </div>
</template>

<script lang="ts" setup>
import { onMounted, onBeforeUnmount, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { fetchOverviewSummary, fetchOverviewTrends, type OverviewSummary, type TrendsVO } from '@/api/monitor/overview';
import { fetchRealtime, fetchHistory, type RealtimeVO } from '@/api/monitor/metric';
import { fetchDbSummary, fetchDbSlow, type DbSummary, type SlowSqlDigest, type SlowSqlEvent } from '@/api/monitor/db';
import { useChart, echarts } from '@/composables/useChart';

const router = useRouter();

/** 25 指标键标签表(与后端 MetricKey 冻结清单一致,DD-08 键不落库不改名)。 */
const METRIC_LABELS: Record<string, { label: string; unit: string }> = {
  'system.cpu.usage': { label: '系统 CPU 使用率', unit: '%' },
  'process.cpu.usage': { label: '进程 CPU 使用率', unit: '%' },
  'jvm.heap.used.percent': { label: 'JVM 堆使用率', unit: '%' },
  'jvm.heap.used.mb': { label: 'JVM 堆已用', unit: 'MB' },
  'jvm.nonheap.used.mb': { label: 'JVM 非堆已用', unit: 'MB' },
  'jvm.threads.live': { label: 'JVM 线程数', unit: '个' },
  'jvm.gc.pause.ms': { label: 'GC 停顿(每分钟)', unit: 'ms/min' },
  'disk.usage.percent': { label: '磁盘使用率', unit: '%' },
  'http.server.qps': { label: 'HTTP QPS', unit: '次/s' },
  'http.server.rt.avg.ms': { label: 'HTTP 平均 RT', unit: 'ms' },
  'http.server.error.rate': { label: 'HTTP 错误率', unit: '%' },
  'db.pool.active': { label: '连接池活跃', unit: '个' },
  'db.pool.idle': { label: '连接池空闲', unit: '个' },
  'db.pool.pending': { label: '连接池等待', unit: '个' },
  'db.pool.usage.percent': { label: '连接池使用率', unit: '%' },
  'db.sql.qps': { label: 'SQL 执行速率', unit: '次/s' },
  'db.sql.rt.avg.ms': { label: 'SQL 平均 RT', unit: 'ms' },
  'db.sql.slow.5m': { label: '慢 SQL(5 分钟)', unit: '次' },
  'db.mysql.threads.connected': { label: 'MySQL 连接数', unit: '个' },
  'db.mysql.threads.running': { label: 'MySQL 运行线程', unit: '个' },
  'db.mysql.row.lock.waits': { label: '行锁等待(每分钟)', unit: '次/min' },
  'db.up': { label: '数据库状态', unit: '0/1' },
  'valkey.up': { label: 'Valkey 状态', unit: '0/1' },
  'online.users': { label: '在线用户', unit: '人' },
  'login.fail.5m': { label: '登录失败(5 分钟)', unit: '次' },
};

const metricOptions = Object.entries(METRIC_LABELS).map(([value, m]) => ({ value, label: m.label }));

/** 总览 8 卡(设计 §6.6 冻结清单)。 */
const CARD_KEYS = [
  'system.cpu.usage',
  'jvm.heap.used.percent',
  'disk.usage.percent',
  'http.server.qps',
  'http.server.rt.avg.ms',
  'db.pool.usage.percent',
  'online.users',
  'login.fail.5m',
];
const metricCards = CARD_KEYS.map((key) => ({ key, label: METRIC_LABELS[key].label, unit: METRIC_LABELS[key].unit }));

const summary = ref<OverviewSummary | null>(null);
const trends = ref<TrendsVO | null>(null);
const db = ref<DbSummary | null>(null);
const dbLoading = ref(false);
const slowDrawer = ref(false);
const slow = ref<{ thresholdMs: number; since: string; top: SlowSqlDigest[]; recent: SlowSqlEvent[] } | null>(null);

const realtimeMinutes = 60;
const realtimeSelected = ref<string[]>(['system.cpu.usage', 'jvm.heap.used.percent', 'http.server.rt.avg.ms']);
const realtimeLoading = ref(false);
let realtimeVo: RealtimeVO | null = null;

const historyMetric = ref('system.cpu.usage');
const historyStep = ref(300);
const historyLoading = ref(false);
const historyRange = ref<[string, string]>(defaultHistoryRange());

function defaultHistoryRange(): [string, string] {
  const end = new Date();
  const begin = new Date(end.getTime() - 24 * 3600 * 1000);
  const fmt = (d: Date) => {
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:00`;
  };
  return [fmt(begin), fmt(end)];
}

function formatTime(value?: string | null): string {
  if (!value) return '—';
  return value.replace('T', ' ').slice(0, 19);
}

function formatUptime(seconds?: number): string {
  if (seconds === undefined || seconds === null) return '—';
  const d = Math.floor(seconds / 86400);
  const h = Math.floor((seconds % 86400) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  return d > 0 ? `${d} 天 ${h} 时 ${m} 分` : h > 0 ? `${h} 时 ${m} 分` : `${m} 分`;
}

function formatMetric(value?: number | null): string {
  if (value === undefined || value === null) return '—';
  return Math.round(value * 100) / 100 + '';
}

function percent(value?: number | null): string {
  return value === undefined || value === null ? '—' : `${Math.round(value)}%`;
}

function fixed(value: number | null | undefined, digits: number): string {
  return value === undefined || value === null ? '—' : value.toFixed(digits);
}

// ---- 图表 ----
const realtimeChart = useChart((chart, colors) => renderRealtime(chart, colors));
const trendChart = useChart((chart, colors) => renderTrend(chart, colors));
const historyChart = useChart((chart, colors) => renderHistory(chart, colors));
const realtimeEl = realtimeChart.el;
const trendEl = trendChart.el;
const historyEl = historyChart.el;

function baseOption(colors: string[]) {
  return {
    color: colors,
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0 },
    grid: { left: 48, right: 24, top: 24, bottom: 48 },
  };
}

function renderRealtime(chart: echarts.ECharts, colors: string[]): void {
  const selected = realtimeSelected.value.length ? realtimeSelected.value : ['system.cpu.usage'];
  const series = (realtimeVo?.series ?? []).filter((s) => selected.includes(s.metric));
  chart.setOption({
    ...baseOption(colors),
    xAxis: { type: 'time' },
    yAxis: { type: 'value', scale: true },
    series: series.map((s) => ({
      name: `${METRIC_LABELS[s.metric]?.label ?? s.metric}(${s.unit})`,
      type: 'line',
      showSymbol: false,
      data: s.points.map((p) => [p.t, Math.round(p.v * 100) / 100]),
    })),
  });
}

function renderTrend(chart: echarts.ECharts, colors: string[]): void {
  const days = trends.value?.loginTrend ?? [];
  chart.setOption({
    ...baseOption(colors),
    xAxis: { type: 'category', data: days.map((d) => d.date) },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      { name: '登录成功', type: 'bar', barMaxWidth: 28, data: days.map((d) => d.total) },
      { name: '登录失败', type: 'bar', barMaxWidth: 28, data: days.map((d) => d.fail) },
    ],
  });
}

function renderHistory(chart: echarts.ECharts, colors: string[]): void {
  if (!historyVo) return;
  chart.setOption({
    ...baseOption(colors),
    xAxis: { type: 'time' },
    yAxis: { type: 'value', scale: true },
    series: [
      {
        name: `${METRIC_LABELS[historyVo.metric]?.label ?? historyVo.metric}(${historyVo.unit})`,
        type: 'line',
        showSymbol: false,
        data: historyVo.points.map((p) => [p.t, Math.round(p.v * 100) / 100]),
      },
    ],
  });
}

let historyVo: { metric: string; unit: string; points: { t: number; v: number }[] } | null = null;

// ---- 数据加载 ----
async function loadSummary(): Promise<void> {
  summary.value = await fetchOverviewSummary();
  trends.value = await fetchOverviewTrends(7);
  trendChart.update();
}

async function loadRealtime(): Promise<void> {
  realtimeLoading.value = true;
  try {
    realtimeVo = await fetchRealtime(realtimeMinutes);
    realtimeChart.update();
  } finally {
    realtimeLoading.value = false;
  }
}

async function loadHistory(): Promise<void> {
  historyLoading.value = true;
  try {
    const [begin, end] = historyRange.value;
    historyVo = await fetchHistory(historyMetric.value, begin, end, historyStep.value);
    historyChart.update();
  } finally {
    historyLoading.value = false;
  }
}

async function loadDb(): Promise<void> {
  dbLoading.value = true;
  try {
    db.value = await fetchDbSummary();
  } finally {
    dbLoading.value = false;
  }
}

async function openSlowDrawer(): Promise<void> {
  slowDrawer.value = true;
  slow.value = await fetchDbSlow();
}

watch(realtimeSelected, () => realtimeChart.update());

let summaryTimer: number | undefined;

onMounted(() => {
  void loadSummary();
  void loadRealtime();
  void loadHistory();
  void loadDb();
  // 概要 60s 轮询;实时曲线 60s 轮询(采样间隔默认 60s)
  summaryTimer = window.setInterval(() => {
    void loadSummary();
    void loadRealtime();
  }, 60_000);
});

onBeforeUnmount(() => {
  if (summaryTimer) window.clearInterval(summaryTimer);
});
</script>

<style scoped>
.page {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.block :deep(.el-card__header) {
  padding: 10px 16px;
}

.head-row {
  display: flex;
  align-items: center;
  gap: 24px;
  flex-wrap: wrap;
}

.instance {
  display: flex;
  align-items: center;
  gap: 8px;
}

.instance h2 {
  margin: 0;
  font-size: 18px;
}

.meta {
  display: flex;
  align-items: center;
  gap: 16px;
  color: var(--text-secondary);
  font-size: 13px;
}

.alerts {
  margin-left: auto;
  display: flex;
  gap: 12px;
}

.health-chips {
  margin-top: 12px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.chip {
  padding: 3px 10px;
  border-radius: 10px;
  font-size: 12px;
  border: 1px solid transparent;
}

.chip-up {
  background: color-mix(in srgb, var(--success-color, #67c23a) 14%, transparent);
  color: var(--success-color, #67c23a);
  border-color: color-mix(in srgb, var(--success-color, #67c23a) 40%, transparent);
}

.chip-down {
  background: color-mix(in srgb, var(--danger-color, #f56c6c) 14%, transparent);
  color: var(--danger-color, #f56c6c);
  border-color: color-mix(in srgb, var(--danger-color, #f56c6c) 40%, transparent);
}

.chip-skip {
  background: color-mix(in srgb, var(--text-secondary, #909399) 12%, transparent);
  color: var(--text-secondary, #909399);
  border-color: color-mix(in srgb, var(--text-secondary, #909399) 35%, transparent);
}

.card-grid {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 12px;
}

@media (max-width: 1280px) {
  .card-grid {
    grid-template-columns: repeat(4, 1fr);
  }
}

.stat-card {
  text-align: center;
}

.stat-label {
  margin: 0;
  font-size: 13px;
  color: var(--text-secondary);
}

.stat-value {
  margin: 8px 0 0;
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary);
}

.stat-unit {
  margin-left: 4px;
  font-size: 12px;
  font-weight: 400;
  color: var(--text-secondary);
}

.chart-head {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.chart-tools {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.inline-stat {
  font-size: 13px;
  color: var(--text-secondary);
}

.chart {
  width: 100%;
}

.chart.tall {
  height: 320px;
}

.chart.mid {
  height: 280px;
}

.db-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

@media (max-width: 960px) {
  .db-grid {
    grid-template-columns: 1fr;
  }
}

.db-card h4 {
  margin: 0 0 10px;
  font-size: 14px;
}

.mt {
  margin-top: 12px;
}

.db-error {
  margin: 10px 0 0;
  color: var(--danger-color, #f56c6c);
  font-size: 12px;
}

.drawer-sub {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--text-secondary);
}

.sql-text {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
  font-family: var(--font-mono, monospace);
  font-size: 12px;
}
</style>
