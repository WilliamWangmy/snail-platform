<template>
  <div class="overview-page">
    <!-- 健康 4 卡(照原型 health-row) -->
    <div class="health-row">
      <div
        v-for="h in healthCards"
        :key="h.name"
        class="card health-card"
      >
        <div
          class="health-ico"
          :style="{ background: h.ok ? 'rgba(var(--app-success-rgb), .12)' : 'rgba(var(--app-danger-rgb), .12)', color: h.ok ? 'var(--app-success)' : 'var(--app-danger)' }"
        >
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.8"
          ><path :d="h.icon" /></svg>
        </div>
        <div>
          <div class="health-name">{{ h.name }}</div>
          <div class="health-val">
            {{ h.val }}
            <span
              class="chip"
              :class="h.ok ? 'chip-success' : 'chip-danger'"
            >{{ h.ok ? 'UP' : 'DOWN' }}</span>
          </div>
        </div>
        <div class="health-lat">{{ h.lat }}</div>
      </div>
    </div>

    <!-- 指标源状态行:degraded 徽标(§4.8)+ 全局自动刷新开关 -->
    <div style="display: flex; align-items: center; gap: 10px; margin-bottom: 14px">
      <span
        class="chip"
        :class="summary?.degraded ? 'chip-warning' : 'chip-success'"
      >指标源 {{ summary?.metricsSource ?? '—' }}</span>
      <span
        v-if="summary?.degraded"
        class="chip chip-danger"
      >外部指标源降级 · 已回退内置采集</span>
      <span style="flex: 1" />
      <span class="muted">最后更新 {{ lastUpdate }}</span>
      <span style="font-size: 12px">自动刷新 <el-switch
        v-model="autoRefresh"
        size="small"
      /></span>
    </div>

    <!-- 指标 8 卡(§4.8:CPU/堆/线程/QPS/错误率/RT/在线/磁盘;照原型样式) -->
    <div class="metric-row">
      <div
        v-for="m in metricCards"
        :key="m.key"
        class="card metric-card"
      >
        <div class="metric-name">
          {{ m.name }}
          <span
            v-if="m.th"
            class="chip chip-muted"
          >阈值 {{ m.th }}{{ m.unit }}</span>
        </div>
        <div
          class="metric-val"
          :style="{ color: valueColor(m) }"
        >
          {{ formatVal(m) }}<small>{{ m.unit }}</small>
        </div>
        <div
          class="metric-delta"
          :class="deltaClass(m)"
        >
          {{ deltaText(m) }}
        </div>
        <Sparkline
          :vals="sparkOf(m.key)"
          :color="valueColor(m)"
        />
      </div>
    </div>

    <!-- 实时曲线(指标多选,§4.8 默认 cpu/heap/qps/rt)+ 历史曲线(指标+快捷档 1h/6h/24h/7d) -->
    <div class="chart-row">
      <div class="card">
        <div class="card-title">
          实时曲线
          <span class="sub">环形缓冲 · 近 60 分钟</span>
        </div>
        <el-select
          v-model="rtMetrics"
          multiple
          collapse-tags
          collapse-tags-tooltip
          size="small"
          style="width: 100%; margin-bottom: 10px"
          @change="loadRealtime"
        >
          <el-option
            v-for="o in CHART_OPTIONS"
            :key="o.value"
            :label="o.label"
            :value="o.value"
          />
        </el-select>
        <SvgLineChart
          :series="rtSeries"
          :labels="chartLabels"
          zero
        />
      </div>
      <div class="card">
        <div class="card-title">
          历史曲线
          <span class="sub">{{ historyLabel }}</span>
          <span class="right">
            <el-select
              v-model="historyMetric"
              size="small"
              style="width: 140px"
              @change="loadHistory"
            >
              <el-option
                v-for="o in CHART_OPTIONS"
                :key="o.value"
                :label="o.label"
                :value="o.value"
              />
            </el-select>
            <el-segmented
              v-model="historyRange"
              :options="HISTORY_OPTIONS"
              size="small"
              @change="loadHistory"
            />
          </span>
        </div>
        <div
          v-if="historyHint"
          class="muted"
          style="font-size: 11px; margin-bottom: 8px"
        >{{ historyHint }}</div>
        <SvgLineChart
          :series="historySeries"
          :labels="chartLabels"
          zero
        />
      </div>
    </div>

    <!-- 最近告警 + 登录趋势(照原型 bottom-row) -->
    <div class="bottom-row">
      <div class="card">
        <div class="card-title">
          最近告警
          <span class="right">
            <a
              class="link"
              @click="router.push('/alert')"
            >查看全部 →</a>
          </span>
        </div>
        <template v-if="recentAlerts.length">
          <div
            v-for="a in recentAlerts"
            :key="a.alertId"
            class="alert-mini"
          >
            <span
              class="chip"
              :class="levelChip(a.level)"
            >{{ a.level }}</span>
            <span>{{ a.ruleName }}</span>
            <span
              class="chip"
              :class="statusChip(a.status)"
            >{{ statusName(a.status) }}</span>
            <span class="t">{{ shortTime(a.firstTime) }}</span>
          </div>
        </template>
        <div
          v-else
          class="empty"
        >
          当前无开放告警 🎉
        </div>
      </div>
      <div class="card">
        <div class="card-title">
          登录趋势 · 近 7 天
          <span class="sub">sys_login_log 聚合</span>
          <span class="right">
            <span class="legend">
              <span><i style="background: var(--app-chart-1)" />成功</span>
              <span><i style="background: var(--app-danger)" />失败</span>
            </span>
          </span>
        </div>
        <SvgBarChart :groups="trendGroups" />
        <div class="overview-foot">
          <span>当前在线 <b style="color: var(--app-ink)">{{ trends?.onlineNow ?? '—' }}</b> 人</span>
          <span class="muted">sys_login_log 聚合 · 缺失日补 0</span>
        </div>
      </div>
    </div>

    <!-- 数据库面板(照原型 db-row:池卡 + 慢 SQL Top5);platform:monitor:db 门控(§4.8) -->
    <div
      v-if="canDb"
      class="db-row"
    >
      <div class="card">
        <div class="card-title">
          数据库连接池
          <span class="sub">HikariPool · HikariPoolMXBean</span>
          <span class="right">
            <span
              class="chip"
              :class="poolChipClass"
            >{{ poolChipText }}</span>
          </span>
        </div>
        <div style="display: flex; align-items: flex-end; gap: 8px">
          <div
            class="pool-val"
            :style="{ color: poolColor }"
          >
            {{ poolUsageText }}<small>% 使用率</small>
          </div>
          <span
            class="muted"
            style="font-size: 10.5px; margin-left: auto"
          >{{ poolSub }}</span>
        </div>
        <div class="pool-bar">
          <i :style="{ width: poolActivePct + '%', background: poolColor }" />
          <i
            :style="{ width: poolIdlePct + '%', background: 'var(--app-chart-2)', opacity: 0.45 }"
          />
        </div>
        <div class="db-stats">
          <div class="db-stat"><div class="k">活跃连接</div><div class="v">{{ db?.pool.active ?? '—' }}</div></div>
          <div class="db-stat"><div class="k">空闲连接</div><div class="v">{{ db?.pool.idle ?? '—' }}</div></div>
          <div class="db-stat"><div class="k">等待线程</div><div
            class="v"
            :style="db?.pool.pending ? 'color: var(--app-warning)' : ''"
          >{{ db?.pool.pending ?? '—' }}</div></div>
          <div class="db-stat"><div class="k">池上限</div><div class="v">{{ db?.pool.max ?? '—' }}<small> max</small></div></div>
        </div>

        <div
          class="card-title"
          style="margin: 14px 0 8px; font-size: 12px"
        >
          MySQL 服务端
          <span class="sub">SHOW GLOBAL STATUS · 只读</span>
          <span class="right">
            <span
              class="chip"
              :class="db?.server.available ? 'chip-success' : 'chip-warning'"
            >{{ db?.server.available ? '可达' : '降级' }}</span>
          </span>
        </div>
        <div
          v-if="db?.server.available"
          class="db-stats"
        >
          <div class="db-stat"><div class="k">已连接</div><div class="v">{{ db.server.threadsConnected ?? '—' }}<small> / {{ db.server.maxConnections ?? '—' }}</small></div></div>
          <div class="db-stat"><div class="k">运行中</div><div class="v">{{ db.server.threadsRunning ?? '—' }}<small> 个</small></div></div>
          <div class="db-stat"><div class="k">行锁等待</div><div class="v">{{ db.server.rowLockWaitsPerMin ?? '—' }}<small> 次/min</small></div></div>
          <div class="db-stat"><div class="k">版本</div><div class="v"><span style="font-size: 12.5px">MySQL {{ db.server.version || '—' }}</span></div></div>
        </div>
        <div
          v-else
          class="empty"
          style="grid-column: 1 / -1; padding: 14px 0; font-size: 11.5px"
        >
          服务端状态不可用：{{ db?.server.lastError || '未知错误' }} —— 连接池与慢 SQL 计数不受影响，db.mysql.* 该 tick 不产样本
        </div>
        <div
          class="muted"
          style="font-size: 11px; margin-top: 12px; line-height: 1.6"
        >
          池读数逐 tick 采样（HikariPoolMXBean）；等待线程持续 &gt; 0 时优先排查慢 SQL 与连接泄漏。服务端仅只读查询，不修改任何全局变量。
        </div>
      </div>

      <div class="card">
        <div class="card-title">
          慢 SQL Top 5
          <span class="sub">MyBatis 拦截 · 阈值 {{ db?.sql.slowThresholdMs ?? '—' }}ms</span>
          <span class="right">
            <span
              class="chip"
              :class="slowChipClass"
            >近 5 分钟 {{ db?.sql.slowCount5m ?? '—' }} 次</span>
            <a
              class="link"
              style="font-size: 11px"
              @click="openSlowDrawer"
            >全部慢 SQL →</a>
          </span>
        </div>
        <div class="sql-head">
          <span>最大耗时</span><span>次数</span><span>SQL 模板 · mapper</span><span>最近</span>
        </div>
        <template v-if="db?.slowTop.length">
          <div
            v-for="r in db.slowTop.slice(0, 5)"
            :key="r.digestId"
            class="sql-row"
            @click="openSlowDrawer"
          >
            <div class="sql-cost">{{ r.maxMs }}ms</div>
            <div class="sql-cnt">{{ r.slowCount }} 次</div>
            <div class="sql-digest">
              <div
                class="q"
                :title="r.sql"
              >{{ r.sql }}</div>
              <div class="m">{{ r.mapperId }}</div>
            </div>
            <div class="sql-time">{{ shortTime(r.lastTime) }}</div>
          </div>
        </template>
        <div
          v-else
          class="empty"
        >
          暂无慢 SQL
        </div>
        <div
          class="muted"
          style="font-size: 11px; margin-top: 10px; line-height: 1.6"
        >
          SQL {{ db?.sql.execPerSec ?? '—' }} 次/s · 平均 {{ db?.sql.avgRtMs ?? '—' }}ms · 自 {{ shortTime(db?.sql.since) }} 起累计 {{ db?.sql.slowCountTotal ?? '—' }} 次<br>
          仅存 SQL 模板（不采参数值）· 摘要 ≤500 / 事件 ≤100 · 进程重启清零
        </div>
      </div>
    </div>

    <!-- 慢 SQL 明细抽屉(照原型:kv 摘要 + 最近事件表) -->
    <el-drawer
      v-model="slowDrawer"
      title="慢 SQL 明细"
      size="560px"
    >
      <template v-if="slow">
        <div
          class="kv"
          style="margin-bottom: 14px"
        >
          <div>慢 SQL 阈值</div><div class="mono">{{ slow.thresholdMs }} ms（snail.monitor.db.slow-sql.threshold-ms）</div>
          <div>统计起点</div><div class="mono">{{ slow.since }}</div>
          <div>采集口径</div><div>MyBatis Executor 拦截 · 模板聚合 · 不含参数值 · 内存保留 · 重启清零</div>
          <div>接口</div><div class="mono">GET /monitor/db/slow · 权限 platform:monitor:overview</div>
        </div>
        <div class="card-title">
          最近 {{ slow.recent.length }} 条<span class="sub">按时间倒序 · 线上返回最近 50 条</span>
        </div>
        <el-table
          :data="slow.recent"
          size="small"
        >
          <el-table-column
            label="时间"
            width="160"
          >
            <template #default="{ row }"><span class="mono" style="font-size: 11px">{{ row.time }}</span></template>
          </el-table-column>
          <el-table-column
            label="耗时"
            width="80"
          >
            <template #default="{ row }"><span class="mono" style="color: var(--app-danger); font-weight: 700">{{ row.costMs }}ms</span></template>
          </el-table-column>
          <el-table-column label="SQL 模板 · mapper">
            <template #default="{ row }">
              <div
                class="mono"
                style="font-size: 10.5px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis"
                :title="row.sql"
              >{{ row.sql }}</div>
              <div
                class="muted"
                style="font-size: 10px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis"
              >{{ row.mapperId }}</div>
            </template>
          </el-table-column>
          <el-table-column
            label="traceId"
            width="130"
          >
            <template #default="{ row }">
              <a
                v-if="row.traceId"
                class="link mono"
                style="font-size: 10.5px"
                title="跳日志页按 traceId 查询(DD-21)"
                @click="router.push(`/log?traceId=${encodeURIComponent(row.traceId)}`)"
              >{{ row.traceId }}</a>
              <span
                v-else
                class="muted"
              >—</span>
            </template>
          </el-table-column>
        </el-table>
        <p
          class="muted"
          style="font-size: 11.5px; margin-top: 10px; line-height: 1.6"
        >
          SQL 文本已转义渲染；参数值、结果集、绑定变量一律不采集、不落库。慢 SQL 明细不保留历史，进程重启清零。
        </p>
      </template>
    </el-drawer>
  </div>
</template>

<script lang="ts" setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { fetchOverviewSummary, fetchOverviewTrends, type OverviewSummary, type TrendsVO } from '@/api/monitor/overview';
import { fetchRealtime, fetchHistory, type MetricPoint, type MetricSeries } from '@/api/monitor/metric';
import { fetchDbSummary, fetchDbSlow, type DbSummary, type SlowSqlDigest, type SlowSqlEvent } from '@/api/monitor/db';
import { fetchActiveAlerts, type MonitorAlert } from '@/api/monitor/alert';
import Sparkline from '@/components/Sparkline.vue';
import SvgLineChart from '@/components/SvgLineChart.vue';
import SvgBarChart from '@/components/SvgBarChart.vue';
import { useAuthStore } from '@/stores/auth';
import { usePolling } from '@/composables/usePolling';

const router = useRouter();
const auth = useAuthStore();

/** DB 面板权限门控(§4.8 v-permission 口径):无 platform:monitor:db 时不渲染也不发请求,避免 403 弹窗。 */
const canDb = computed(() => auth.has('platform:monitor:db'));

/** 指标卡 8 张(§4.8);th 为告警阈值芯片,着色阈值同原型 80%/100% 两档。 */
interface CardMetric {
  key: string;
  name: string;
  unit: string;
  th?: number;
}

const metricCards = ref<CardMetric[]>([
  { key: 'system.cpu.usage', name: 'CPU 使用率', unit: '%', th: 85 },
  { key: 'jvm.heap.used.percent', name: '堆内存', unit: '%', th: 90 },
  { key: 'jvm.threads.live', name: '活跃线程', unit: '个', th: 500 },
  { key: 'http.server.qps', name: 'QPS', unit: '次/s' },
  { key: 'http.server.error.rate', name: '5xx 错误率', unit: '%', th: 5 },
  { key: 'http.server.rt.avg.ms', name: '平均 RT', unit: 'ms' },
  { key: 'online.users', name: '在线用户', unit: '人' },
  { key: 'disk.usage.percent', name: '磁盘使用率', unit: '%', th: 90 },
]);

const summary = ref<OverviewSummary | null>(null);
const trends = ref<TrendsVO | null>(null);
const db = ref<DbSummary | null>(null);
const recentAlerts = ref<MonitorAlert[]>([]);
const lastUpdate = ref('--:--:--');

/** 曲线可选指标(照 MetricKey 白名单挑常用 10 键);默认 cpu/heap/qps/rt(§4.8)。 */
const CHART_OPTIONS = [
  { label: 'CPU 使用率', value: 'system.cpu.usage' },
  { label: '堆使用率', value: 'jvm.heap.used.percent' },
  { label: '活跃线程', value: 'jvm.threads.live' },
  { label: 'QPS', value: 'http.server.qps' },
  { label: '5xx 错误率', value: 'http.server.error.rate' },
  { label: '平均 RT', value: 'http.server.rt.avg.ms' },
  { label: '在线用户', value: 'online.users' },
  { label: '磁盘使用率', value: 'disk.usage.percent' },
  { label: '池使用率', value: 'db.pool.usage.percent' },
  { label: 'SQL 平均 RT', value: 'db.sql.rt.avg.ms' },
] as { label: string; value: string }[];

/** 历史快捷档(§4.8);步长映射后端白名单 60/300/3600,7 天 3600 步 = 168 点。 */
const HISTORY_OPTIONS = [
  { label: '1h', value: '1h' },
  { label: '6h', value: '6h' },
  { label: '24h', value: '24h' },
  { label: '7d', value: '7d' },
] as { label: string; value: string }[];
const STEP_BY_RANGE: Record<string, number> = { '1h': 60, '6h': 300, '24h': 3600, '7d': 3600 };
const SPAN_HOURS_BY_RANGE: Record<string, number> = { '1h': 1, '6h': 6, '24h': 24, '7d': 24 * 7 };

const rtMetrics = ref<string[]>(['system.cpu.usage', 'jvm.heap.used.percent', 'http.server.qps', 'http.server.rt.avg.ms']);
const rtSeries = ref<{ name: string; values: number[]; color: string; area?: boolean; unit?: string }[]>([]);
const historyMetric = ref('system.cpu.usage');
const historyRange = ref('24h');
const historySeries = ref<{ name: string; values: number[]; color: string; area?: boolean; unit?: string }[]>([]);
const historyHint = ref('');
const chartLabels = ref<string[]>([]);
const sparkData = ref<Record<string, number[]>>({});

const historyLabel = computed(() => {
  const step = STEP_BY_RANGE[historyRange.value] ?? 3600;
  const span = SPAN_HOURS_BY_RANGE[historyRange.value] ?? 24;
  const agg = step >= 3600 ? '1 小时' : `${step / 60} 分钟`;
  return span >= 24 ? `近 ${span / 24} 天 · ${agg}聚合` : `近 ${span} 小时 · ${agg}聚合`;
});

// ---------- 健康卡 ----------
const HEALTH_META: { key: string; name: string; icon: string }[] = [
  { key: 'db', name: 'MySQL', icon: 'M12 2C7 2 4 3.5 4 5.5v13C4 20.5 7 22 12 22s8-1.5 8-3.5v-13C20 3.5 17 2 12 2z M4 5.5C4 7.5 7 9 12 9s8-1.5 8-3.5 M4 12c0 2 3 3.5 8 3.5s8-1.5 8-3.5' },
  { key: 'valkey', name: 'Valkey', icon: 'M13 2L3 14h9l-1 8 10-12h-9l1-8z' },
  { key: 'disk', name: '磁盘（logs 卷）', icon: 'M22 12H2 M5.5 5h13l3 7v5a2 2 0 0 1-2 2H4.5a2 2 0 0 1-2-2v-5l3-7z' },
  { key: 'agent', name: 'Agent 探针', icon: 'M12 1v4M12 19v4M4.2 4.2l2.8 2.8M17 17l2.8 2.8M1 12h4M19 12h4M4.2 19.8L7 17M17 7l2.8-2.8' },
];

const healthCards = computed(() =>
  HEALTH_META.map((meta) => {
    const item = summary.value?.health?.[meta.key];
    const ok = item?.status === 'up';
    let val = '—';
    if (item) {
      if (meta.key === 'disk') {
        const usage = summary.value?.metrics?.['disk.usage.percent'];
        val = usage === undefined || usage === null ? (ok ? '正常' : 'DOWN') : `${Math.round(usage)}% 已用`;
      } else {
        val = ok ? '正常' : 'DOWN';
      }
    }
    const lat = item?.latencyMs !== undefined && item?.latencyMs !== null ? `${item.latencyMs}ms` : '—';
    return { name: meta.name, icon: meta.icon, ok, val, lat };
  }),
);

// ---------- 指标卡辅助 ----------

function metricValue(key: string): number | null {
  return summary.value?.metrics?.[key] ?? null;
}

function formatVal(m: CardMetric): string {
  const v = metricValue(m.key);
  if (v === null) return '—';
  return v < 10 ? String(Math.round(v * 10) / 10) : String(Math.round(v * 10) / 10);
}

function valueColor(m: CardMetric): string {
  const v = metricValue(m.key);
  if (v === null || m.th === undefined) return 'var(--app-primary)';
  if (v > m.th) return 'var(--app-danger)';
  if (v > m.th * 0.8) return 'var(--app-warning)';
  return 'var(--app-primary)';
}

/** 增量:实时窗口首末差值(较上小时口径近似)。 */
function deltaOf(key: string): number | null {
  const spark = sparkData.value[key];
  if (!spark || spark.length < 2) return null;
  return Math.round((spark[spark.length - 1]! - spark[0]!) * 10) / 10;
}

const BAD_UP = new Set(['system.cpu.usage', 'jvm.heap.used.percent', 'http.server.error.rate']);

function deltaClass(m: CardMetric): string {
  const d = deltaOf(m.key);
  if (d === null || d === 0) return 'flat';
  const badIsUp = BAD_UP.has(m.key);
  if (d > 0) return badIsUp ? 'up' : 'down';
  return badIsUp ? 'down' : 'up';
}

function deltaText(m: CardMetric): string {
  const d = deltaOf(m.key);
  if (d === null) return '— 采样中';
  const arrow = d > 0 ? '▲' : d < 0 ? '▼' : '—';
  return `${arrow} ${Math.abs(d)}${m.unit} · 较上小时`;
}

function sparkOf(key: string): number[] {
  return sparkData.value[key] ?? [0, 0];
}

// ---------- 文案辅助 ----------
function levelChip(level: string): string {
  return level === 'critical' ? 'chip-danger' : level === 'warn' ? 'chip-warning' : 'chip-info';
}

function statusChip(status: string): string {
  return status === 'resolved' ? 'chip-success' : status === 'acked' ? 'chip-warning' : 'chip-danger';
}

function statusName(status: string): string {
  return status === 'resolved' ? '已恢复' : status === 'acked' ? '已确认' : '触发中';
}

function shortTime(value?: string | null): string {
  if (!value) return '—';
  const normalized = value.replace('T', ' ');
  return normalized.length >= 16 ? normalized.slice(5, 16) : normalized;
}

// ---------- 数据库面板 ----------
const poolUsage = computed(() => {
  const p = db.value?.pool;
  if (!p || p.usagePercent === null || p.usagePercent === undefined) return null;
  return p.usagePercent;
});

const poolUsageText = computed(() => (poolUsage.value === null ? '—' : poolUsage.value.toFixed(1)));
const poolColor = computed(() => {
  const u = poolUsage.value;
  if (u === null) return 'var(--app-primary)';
  return u > 90 ? 'var(--app-danger)' : u > 80 ? 'var(--app-warning)' : 'var(--app-primary)';
});
const poolChipClass = computed(() => {
  const u = poolUsage.value;
  if (u === null) return 'chip-muted';
  return u > 90 ? 'chip-danger' : u > 80 ? 'chip-warning' : 'chip-success';
});
const poolChipText = computed(() => {
  const u = poolUsage.value;
  if (u === null) return '未知';
  return u > 90 ? '使用率过高' : u > 80 ? '接近上限' : '正常';
});
const poolSub = computed(() => {
  const p = db.value?.pool;
  if (!p) return '—';
  return `${p.active ?? '—'} / ${p.max ?? '—'} 连接被占用 · 等待 ${p.pending ?? 0}`;
});
const poolActivePct = computed(() => {
  const p = db.value?.pool;
  if (!p || !p.max) return 0;
  return ((p.active ?? 0) / p.max) * 100;
});
const poolIdlePct = computed(() => {
  const p = db.value?.pool;
  if (!p || !p.max) return 0;
  return ((p.idle ?? 0) / p.max) * 100;
});

const slowChipClass = computed(() => {
  const n = db.value?.sql.slowCount5m ?? 0;
  return n > 50 ? 'chip-danger' : n > 10 ? 'chip-warning' : 'chip-muted';
});

// ---------- 慢 SQL 抽屉 ----------
const slowDrawer = ref(false);
const slow = ref<{ thresholdMs: number; since: string; top: SlowSqlDigest[]; recent: SlowSqlEvent[] } | null>(null);

async function openSlowDrawer(): Promise<void> {
  slowDrawer.value = true;
  slow.value = await fetchDbSlow();
}

// ---------- 趋势图 ----------
const trendGroups = computed(() => {
  const days = trends.value?.loginTrend ?? [];
  return days.map((d) => ({
    label: d.date.slice(5),
    values: [d.total, d.fail],
    colors: ['var(--app-chart-1)', 'var(--app-danger)'],
    names: ['成功', '失败'],
  }));
});

function toValues(points: MetricPoint[]): number[] {
  return points.map((p) => Math.round(p.v * 100) / 100);
}

function timeLabels(points: MetricPoint[], withDate = false): string[] {
  const step = Math.max(1, Math.round(points.length / 8));
  return points.map((p, i) => {
    if (i % step !== 0) return '';
    const d = new Date(p.t);
    const pad = (n: number) => String(n).padStart(2, '0');
    if (!withDate) return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
    return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  });
}

const SERIES_COLORS = ['var(--app-chart-1)', 'var(--app-chart-3)', 'var(--app-chart-4)', 'var(--app-info)', 'var(--app-warning)', 'var(--app-danger)'];

function labelOf(key: string): string {
  return CHART_OPTIONS.find((o) => o.value === key)?.label ?? key;
}

// ---------- 实时曲线(§4.8:指标多选,环形缓冲 60 分钟) ----------
async function loadRealtime(): Promise<void> {
  const vo = await fetchRealtime(60);
  const selected = rtMetrics.value.length ? rtMetrics.value : ['system.cpu.usage'];
  const series = selected
    .map((key) => vo.series.find((s) => s.metric === key))
    .filter((s): s is MetricSeries => Boolean(s));
  chartLabels.value = series[0] ? timeLabels(series[0].points) : [];
  rtSeries.value = series.map((s, i) => ({
    name: labelOf(s.metric),
    values: toValues(s.points),
    color: SERIES_COLORS[i % SERIES_COLORS.length]!,
    area: i === 0,
  }));
  collectSparks(vo.series);
}

// ---------- 历史曲线(§4.8:快捷档 1h/6h/24h/7d;2000 点保护 400 → 自动降 5 分钟步长重查) ----------
async function loadHistory(): Promise<void> {
  historyHint.value = '';
  const end = new Date();
  const begin = new Date(end.getTime() - (SPAN_HOURS_BY_RANGE[historyRange.value] ?? 24) * 3600 * 1000);
  const fmt = (d: Date) => {
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:00`;
  };
  const step = STEP_BY_RANGE[historyRange.value] ?? 3600;
  let points: MetricPoint[] | null = null;
  try {
    points = (await fetchHistory(historyMetric.value, fmt(begin), fmt(end), step)).points;
  } catch (e) {
    if (step < 300) {
      // 2000 点保护:后端 400 时把步长提到 300 重查一次(§4.8)
      try {
        points = (await fetchHistory(historyMetric.value, fmt(begin), fmt(end), 300)).points;
        historyHint.value = '数据点超限，已自动切换 5 分钟步长';
      } catch (err) {
        historyHint.value = `查询失败：${err instanceof Error ? err.message : '未知错误'}`;
      }
    } else {
      historyHint.value = `查询失败：${e instanceof Error ? e.message : '未知错误'}`;
    }
  }
  chartLabels.value = points ? timeLabels(points, (SPAN_HOURS_BY_RANGE[historyRange.value] ?? 24) > 24) : [];
  historySeries.value = [
    {
      name: labelOf(historyMetric.value),
      values: points ? toValues(points) : [],
      color: 'var(--app-chart-1)',
      area: true,
    },
  ];
}

function collectSparks(series: { metric: string; points: MetricPoint[] }[]): void {
  const map: Record<string, number[]> = {};
  for (const s of series) {
    map[s.metric] = toValues(s.points.slice(-30));
  }
  sparkData.value = map;
}

// ---------- 加载与轮询(usePolling:自动刷新开关 + visibilitychange 暂停,§4.8) ----------
async function loadAll(): Promise<void> {
  const now = new Date();
  lastUpdate.value = [now.getHours(), now.getMinutes(), now.getSeconds()].map((n) => String(n).padStart(2, '0')).join(':');
  summary.value = await fetchOverviewSummary().catch(() => summary.value);
  trends.value = await fetchOverviewTrends(7).catch(() => trends.value);
  if (canDb.value) db.value = await fetchDbSummary().catch(() => db.value);
  recentAlerts.value = (await fetchActiveAlerts().catch(() => recentAlerts.value as MonitorAlert[])).slice(0, 5);
  await loadRealtime();
}

const { autoRefresh } = usePolling(loadAll, 30000);

let refreshListener: (() => void) | undefined;

onMounted(() => {
  void loadHistory();
  refreshListener = () => void loadAll();
  window.addEventListener('monitor-refresh', refreshListener);
});

onBeforeUnmount(() => {
  if (refreshListener) window.removeEventListener('monitor-refresh', refreshListener);
});
</script>

<style>
.overview-foot {
  display: flex;
  justify-content: space-between;
  margin-top: 10px;
  font-size: 11.5px;
  color: var(--app-muted);
  align-items: center;
  gap: 10px;
}
</style>
