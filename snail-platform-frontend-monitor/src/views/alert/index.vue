<template>
  <div class="card">
    <el-tabs v-model="activeTab">
      <!-- 当前告警:级别权重排序(§6.7),徽标计数;30s 轮询(DD-24)可开关 -->
      <el-tab-pane name="active">
        <template #label>
          当前告警 <span class="cnt">{{ openCount }}</span>
        </template>
        <div class="filter-bar" style="margin-bottom: 8px">
          <span style="flex: 1" />
          <span class="muted" style="font-size: 11.5px">自动刷新（30s）<el-switch v-model="autoRefresh" size="small" /></span>
        </div>
        <el-table
          :data="activeList"
          v-loading="activeLoading"
          size="default"
        >
          <el-table-column
            label="级别"
            width="90"
          >
            <template #default="{ row }">
              <span
                class="chip"
                :class="levelChip(row.level)"
              >{{ row.level }}</span>
            </template>
          </el-table-column>
          <el-table-column label="规则">
            <template #default="{ row }">
              <a
                class="link"
                @click="openDetail(row as MonitorAlert)"
              >{{ row.ruleName }}</a>
              <span
                v-if="row.silenceUntil"
                class="chip chip-muted"
                style="margin-left: 6px"
              >静默至 {{ shortTime(row.silenceUntil) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="指标 / 触发值">
            <template #default="{ row }">
              <span class="mono" style="font-size: 11.5px">{{ row.metric }}</span><br>
              <b style="color: var(--app-danger)">{{ row.triggerValue }}</b>
            </template>
          </el-table-column>
          <el-table-column label="阈值">
            <template #default="{ row }">
              <span class="mono">{{ cmpText(row.comparator) }} {{ row.threshold }}</span>
            </template>
          </el-table-column>
          <el-table-column label="首次触发">
            <template #default="{ row }"><span class="mono">{{ formatTime(row.firstTime) }}</span></template>
          </el-table-column>
          <el-table-column label="持续">
            <template #default="{ row }">{{ durationText(row.firstTime) }}</template>
          </el-table-column>
          <el-table-column label="状态">
            <template #default="{ row }">
              <span
                class="chip"
                :class="statusChip(row.status)"
              >{{ statusName(row.status) }}</span>
              <div
                v-if="row.ackBy"
                class="muted"
                style="font-size: 10.5px"
              >{{ row.ackBy }}</div>
            </template>
          </el-table-column>
          <el-table-column
            label="操作"
            align="right"
            width="200"
          >
            <template #default="{ row }">
              <el-button
                v-if="row.status === 'firing'"
                v-permission="'platform:monitor:ack'"
                size="small"
                @click="doAck(row as MonitorAlert)"
              >确认</el-button>
              <el-button
                v-permission="'platform:monitor:silence'"
                size="small"
                @click="openSilence(row as MonitorAlert)"
              >静默</el-button>
              <el-button
                size="small"
                @click="openDetail(row as MonitorAlert)"
              >详情</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 告警历史(已恢复/已确认) -->
      <el-tab-pane label="告警历史" name="history">
        <div class="filter-bar">
          <label>状态</label>
          <el-select v-model="historyStatus" size="small" style="width: 110px">
            <el-option label="全部" value="" />
            <el-option label="触发中" value="firing" />
            <el-option label="已确认" value="acked" />
            <el-option label="已恢复" value="resolved" />
          </el-select>
          <label>级别</label>
          <el-select v-model="historyLevel" size="small" style="width: 110px">
            <el-option label="全部" value="" />
            <el-option label="critical" value="critical" />
            <el-option label="warning" value="warn" />
            <el-option label="info" value="info" />
          </el-select>
          <el-button size="small" type="primary" :loading="historyLoading" @click="loadHistory(1)">查询</el-button>
        </div>
        <el-table :data="historyPage.rows" v-loading="historyLoading" size="default">
          <el-table-column label="级别" width="90">
            <template #default="{ row }">
              <span class="chip" :class="levelChip(row.level)">{{ row.level }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="ruleName" label="规则" min-width="150" />
          <el-table-column label="触发值">
            <template #default="{ row }"><span class="mono">{{ row.triggerValue }}</span></template>
          </el-table-column>
          <el-table-column label="首次触发">
            <template #default="{ row }"><span class="mono">{{ formatTime(row.firstTime) }}</span></template>
          </el-table-column>
          <el-table-column label="恢复时间">
            <template #default="{ row }"><span class="mono">{{ formatTime(row.resolveTime) }}</span></template>
          </el-table-column>
          <el-table-column label="确认人">
            <template #default="{ row }">{{ row.ackBy || '—' }}</template>
          </el-table-column>
          <el-table-column label="状态">
            <template #default="{ row }">
              <span class="chip" :class="statusChip(row.status)">{{ statusName(row.status) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          layout="total, prev, pager, next"
          :total="historyPage.total"
          :page-size="historyPage.size"
          :current-page="historyPage.current"
          @current-change="(p: number) => loadHistory(p)"
        />
      </el-tab-pane>

      <!-- 规则管理:内置规则只读(§6.7,DD-16 静默 1-1440 分钟) -->
      <el-tab-pane label="规则管理" name="rules">
        <div class="filter-bar">
          <el-input v-model="ruleKeyword" placeholder="搜索规则名称…" size="small" style="width: 200px" clearable @keyup.enter="loadRules(1)" />
          <el-select v-model="ruleEnabledFilter" size="small" style="width: 110px" placeholder="全部状态" clearable>
            <el-option label="启用" value="1" />
            <el-option label="停用" value="0" />
          </el-select>
          <el-button size="small" type="primary" :loading="rulesLoading" @click="loadRules(1)">查询</el-button>
          <span style="flex: 1" />
          <el-button size="small" type="primary" v-permission="'platform:monitor:rule'" @click="openRuleEditor()">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width: 13px; height: 13px"><path d="M12 5v14M5 12h14" /></svg>
            新建规则
          </el-button>
        </div>
        <el-table :data="rulePage.rows" v-loading="rulesLoading" size="default">
          <el-table-column label="规则名称" min-width="150">
            <template #default="{ row }">
              {{ row.name }}
              <span v-if="row.isSystem === 1" class="chip chip-primary">内置</span>
            </template>
          </el-table-column>
          <el-table-column label="指标">
            <template #default="{ row }"><span class="mono" style="font-size: 11.5px">{{ row.metric }}</span></template>
          </el-table-column>
          <el-table-column label="条件">
            <template #default="{ row }"><span class="mono">{{ cmpText(row.comparator) }} {{ row.threshold }}</span></template>
          </el-table-column>
          <el-table-column label="持续">
            <template #default="{ row }">{{ row.durationPoints }} 个采样点</template>
          </el-table-column>
          <el-table-column label="级别" width="90">
            <template #default="{ row }">
              <span class="chip" :class="levelChip(row.level)">{{ row.level }}</span>
            </template>
          </el-table-column>
          <el-table-column label="静默">
            <template #default="{ row }">
              <span v-if="row.silenceUntil" class="chip chip-muted">至 {{ shortTime(row.silenceUntil) }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="启用" width="70">
            <template #default="{ row }">
              <el-switch
                :model-value="row.enabled === 1"
                :disabled="row.isSystem === 1 || !auth.has('platform:monitor:rule')"
                @change="(v: string | number | boolean) => doToggleRule(row as AlertRule, Boolean(v))"
              />
            </template>
          </el-table-column>
          <el-table-column label="操作" align="right" width="130">
            <template #default="{ row }">
              <template v-if="row.isSystem !== 1">
                <el-button size="small" v-permission="'platform:monitor:rule'" @click="openRuleEditor(row as AlertRule)">编辑</el-button>
                <el-button size="small" type="danger" plain v-permission="'platform:monitor:rule'" @click="doDeleteRule(row as AlertRule)">删除</el-button>
              </template>
              <span v-else class="muted" style="font-size: 11px">内置</span>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          layout="total, prev, pager, next"
          :total="rulePage.total"
          :page-size="rulePage.size"
          :current-page="rulePage.current"
          @current-change="(p: number) => loadRules(p)"
        />
      </el-tab-pane>
    </el-tabs>

    <!-- 告警详情抽屉:kv + 触发序列图 + 通知时间线(照原型) -->
    <el-drawer v-model="detailDrawer" size="560px" :title="detail ? `告警详情 · ${detail.alert.ruleName}` : '告警详情'">
      <template v-if="detail">
        <div class="kv" style="margin-bottom: 16px">
          <div>告警 ID</div><div class="mono">#{{ detail.alert.alertId }}</div>
          <div>级别</div><div><span class="chip" :class="levelChip(detail.alert.level)">{{ detail.alert.level }}</span></div>
          <div>状态</div><div><span class="chip" :class="statusChip(detail.alert.status)">{{ statusName(detail.alert.status) }}</span></div>
          <div>指标</div><div class="mono">{{ detail.alert.metric }}</div>
          <div>触发值 / 阈值</div><div class="mono"><b style="color: var(--app-danger)">{{ detail.alert.triggerValue }}</b> / {{ cmpText(detail.alert.comparator) }} {{ detail.alert.threshold }}</div>
          <div>首次触发</div><div class="mono">{{ formatTime(detail.alert.firstTime) }}</div>
          <div>最近触发</div><div class="mono">{{ formatTime(detail.alert.lastTime) }}</div>
          <div>确认人</div><div>{{ detail.alert.ackBy || '—' }}</div>
          <div>静默至</div><div>{{ formatTime(detail.alert.silenceUntil) }}</div>
        </div>
        <div class="card-title">触发值序列<span class="sub">聚合读(1 分钟步长)</span></div>
        <p v-if="detail.seriesExpired" class="muted" style="font-size: 12px">采样数据已过期(超出保留期),序列不可用</p>
        <SvgLineChart
          v-else
          :series="detailSeries"
          :labels="[]"
          :height="150"
          zero
        />
        <div class="card-title" style="margin-top: 16px">通知记录</div>
        <div v-if="detail.notifyRecords.length" class="timeline">
          <div v-for="(rec, i) in detail.notifyRecords" :key="i" class="tl-item">
            <b>{{ rec.type === 'resolve' ? '恢复通知' : '触发通知' }}</b> · {{ rec.channels.join(' / ') }} → {{ rec.recipients }} 人
            <div class="t">{{ formatTime(rec.time) }}<template v-if="rec.error"> · <span style="color: var(--app-danger)">{{ rec.error }}</span></template></div>
          </div>
        </div>
        <div v-else class="muted" style="font-size: 12px">无通知记录(提示级别仅记录不通知)</div>
        <p class="muted" style="font-size: 11px; margin-top: 10px">
          「确认」表示已知悉,幂等可重复;恢复判定由连续 2 个采样点未命中自动处理并发恢复通知。
        </p>
      </template>
    </el-drawer>

    <!-- 静默抽屉 -->
    <el-drawer v-model="silenceDrawer" size="440px" :title="silenceTarget ? `静默告警 · ${silenceTarget.ruleName}` : '静默告警'">
      <div class="filter-bar" style="display: block">
        <div class="kv" style="margin-bottom: 14px">
          <div>规则</div><div>{{ silenceTarget?.ruleName }}</div>
          <div>指标</div><div class="mono">{{ silenceTarget?.metric }}</div>
          <div>当前状态</div><div><span v-if="silenceTarget" class="chip" :class="statusChip(silenceTarget.status)">{{ statusName(silenceTarget.status) }}</span></div>
        </div>
        <p style="font-size: 12px; color: var(--app-muted); margin-bottom: 8px">静默时长(作用于规则,期间不新开告警、不发通知)</p>
        <el-select v-model="silenceMinutes" style="width: 100%">
          <el-option label="30 分钟" :value="30" />
          <el-option label="1 小时" :value="60" />
          <el-option label="4 小时" :value="240" />
          <el-option label="24 小时(上限)" :value="1440" />
        </el-select>
        <p class="muted" style="font-size: 12px; margin-top: 12px">
          已开放告警照常流转,仅抑制新告警与通知;到期自动恢复评估。
        </p>
      </div>
      <template #footer>
        <el-button @click="silenceDrawer = false">取消</el-button>
        <el-button type="primary" :loading="silencing" @click="doSilence">确认静默</el-button>
      </template>
    </el-drawer>

    <!-- 规则编辑抽屉 -->
    <el-drawer v-model="ruleDrawer" size="480px" :title="ruleForm.ruleId ? `编辑规则 · ${ruleForm.name}` : '新建告警规则'">
      <el-form ref="ruleFormRef" :model="ruleForm" :rules="ruleRules" label-position="top" size="default">
        <el-form-item label="规则名称(≤64,全局唯一)" prop="name">
          <el-input v-model="ruleForm.name" maxlength="64" />
        </el-form-item>
        <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 12px">
          <el-form-item label="监控指标" prop="metric">
            <el-select v-model="ruleForm.metric" style="width: 100%" :disabled="editingBuiltin">
              <el-option v-for="m in metricOptions" :key="m" :label="m" :value="m" />
            </el-select>
          </el-form-item>
          <el-form-item label="比较符" prop="comparator">
            <el-select v-model="ruleForm.comparator" style="width: 100%">
              <el-option label="> gt" value="gt" />
              <el-option label="< lt" value="lt" />
              <el-option label="= eq" value="eq" />
            </el-select>
          </el-form-item>
          <el-form-item label="阈值" prop="threshold">
            <el-input-number v-model="ruleForm.threshold" :min="0" style="width: 100%" />
          </el-form-item>
          <el-form-item label="持续采样点(1~5)" prop="durationPoints">
            <el-input-number v-model="ruleForm.durationPoints" :min="1" :max="5" style="width: 100%" />
          </el-form-item>
        </div>
        <el-form-item label="告警级别" prop="level">
          <el-select v-model="ruleForm.level" style="width: 100%">
            <el-option label="info(仅记录,不通知)" value="info" />
            <el-option label="warning" value="warn" />
            <el-option label="critical" value="critical" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="ruleForm.remark" type="textarea" :rows="2" maxlength="200" />
        </el-form-item>
        <p v-if="editingBuiltin" class="muted" style="font-size: 11.5px">
          内置规则:名称与指标不可改,仅阈值/持续/级别/启停可调;不允许删除(可停用)。
        </p>
      </el-form>
      <template #footer>
        <el-button @click="ruleDrawer = false">取消</el-button>
        <el-button type="primary" :loading="ruleSaving" @click="saveRule">保存</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
import type { FormInstance, FormRules } from 'element-plus';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  ackAlert,
  createRule,
  deleteRule,
  fetchActiveAlerts,
  fetchAlertDetail,
  fetchAlertPage,
  fetchRulePage,
  silenceAlert,
  toggleRule,
  updateRule,
  type MonitorAlert,
  type AlertDetail,
  type AlertRule,
} from '@/api/monitor/alert';
import { useAuthStore } from '@/stores/auth';
import { usePolling } from '@/composables/usePolling';
import SvgLineChart from '@/components/SvgLineChart.vue';

const auth = useAuthStore();
const activeTab = ref('active');

// ---------- 活动告警 ----------
const activeList = ref<MonitorAlert[]>([]);
const activeLoading = ref(false);

const openCount = computed(() => activeList.value.filter((a) => a.status !== 'resolved').length);

async function loadActive(): Promise<void> {
  activeLoading.value = true;
  try {
    activeList.value = await fetchActiveAlerts();
  } finally {
    activeLoading.value = false;
  }
}

// ---------- 历史告警 ----------
const historyStatus = ref('resolved');
const historyLevel = ref('');
const historyLoading = ref(false);
const historyPage = reactive({ rows: [] as MonitorAlert[], total: 0, size: 20, current: 1 });

async function loadHistory(page: number): Promise<void> {
  historyLoading.value = true;
  try {
    const params: Record<string, unknown> = { page, size: historyPage.size };
    if (historyStatus.value) params.status = historyStatus.value;
    if (historyLevel.value) params.level = historyLevel.value;
    const result = await fetchAlertPage(params);
    historyPage.rows = result.rows;
    historyPage.total = result.total;
    historyPage.current = page;
  } finally {
    historyLoading.value = false;
  }
}

// ---------- 规则 ----------
const ruleKeyword = ref('');
const ruleEnabledFilter = ref('');
const rulePage = reactive({ rows: [] as AlertRule[], total: 0, size: 20, current: 1 });
const rulesLoading = ref(false);

async function loadRules(page: number): Promise<void> {
  rulesLoading.value = true;
  try {
    const params: Record<string, unknown> = { page, size: rulePage.size };
    if (ruleKeyword.value.trim()) params.name = ruleKeyword.value.trim();
    if (ruleEnabledFilter.value) params.enabled = ruleEnabledFilter.value;
    const result = await fetchRulePage(params);
    rulePage.rows = result.rows;
    rulePage.total = result.total;
    rulePage.current = page;
  } finally {
    rulesLoading.value = false;
  }
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

function cmpText(c: string): string {
  return c === 'gt' ? '>' : c === 'lt' ? '<' : '=';
}

function formatTime(value?: string | null): string {
  if (!value) return '—';
  return value.replace('T', ' ').slice(0, 19);
}

function shortTime(value?: string | null): string {
  if (!value) return '—';
  const normalized = value.replace('T', ' ');
  return normalized.length >= 16 ? normalized.slice(5, 16) : normalized;
}

function durationText(from?: string | null): string {
  if (!from) return '—';
  const ms = Date.now() - Date.parse(from.replace(' ', 'T'));
  if (Number.isNaN(ms)) return '—';
  const m = Math.floor(ms / 60000);
  return m < 60 ? `${m} 分钟` : `${Math.floor(m / 60)} 小时 ${m % 60} 分`;
}

// ---------- 详情抽屉 ----------
const detailDrawer = ref(false);
const detail = ref<AlertDetail | null>(null);

const detailSeries = computed(() => [
  { name: '触发值', values: detail.value?.series.map((p) => p.v) ?? [], color: 'var(--app-danger)', area: true },
]);

async function openDetail(row: MonitorAlert): Promise<void> {
  detail.value = await fetchAlertDetail(row.alertId);
  detailDrawer.value = true;
}

// ---------- 确认 / 静默 ----------
async function doAck(row: MonitorAlert): Promise<void> {
  await ElMessageBox.confirm(`确认已知悉「${row.ruleName}」?`, '确认告警', { type: 'warning' });
  await ackAlert(row.alertId);
  ElMessage.success('告警已确认(幂等:重复确认返回 ok)');
  await loadActive();
}

const silenceDrawer = ref(false);
const silenceMinutes = ref(60);
const silencing = ref(false);
const silenceTarget = ref<MonitorAlert | null>(null);

function openSilence(row: MonitorAlert): void {
  silenceTarget.value = row;
  silenceMinutes.value = 60;
  silenceDrawer.value = true;
}

async function doSilence(): Promise<void> {
  if (!silenceTarget.value) return;
  silencing.value = true;
  try {
    const until = await silenceAlert(silenceTarget.value.alertId, silenceMinutes.value);
    ElMessage.success(`已静默 ${silenceMinutes.value} 分钟(覆盖式),至 ${formatTime(until)}`);
    silenceDrawer.value = false;
    await Promise.all([loadActive(), loadRules(rulePage.current)]);
  } finally {
    silencing.value = false;
  }
}

// ---------- 规则编辑 ----------
const ruleDrawer = ref(false);
const ruleSaving = ref(false);
const ruleFormRef = ref<FormInstance | null>(null);
const ruleForm = reactive({
  ruleId: 0,
  name: '',
  metric: '',
  comparator: 'gt',
  threshold: 80,
  durationPoints: 2,
  level: 'warn',
  remark: '',
});
const editingBuiltin = ref(false);
const ruleRules: FormRules = {
  name: [{ required: true, message: '名称必填', trigger: 'blur' }],
  metric: [{ required: true, message: '请选择指标', trigger: 'change' }],
  threshold: [{ required: true, message: '阈值必填且为数字', trigger: 'blur' }],
};

const metricOptions = [
  'system.cpu.usage', 'process.cpu.usage', 'jvm.heap.used.percent', 'jvm.heap.used.mb',
  'jvm.nonheap.used.mb', 'jvm.threads.live', 'jvm.gc.pause.ms', 'disk.usage.percent',
  'http.server.qps', 'http.server.rt.avg.ms', 'http.server.error.rate',
  'db.pool.active', 'db.pool.idle', 'db.pool.pending', 'db.pool.usage.percent',
  'db.sql.qps', 'db.sql.rt.avg.ms', 'db.sql.slow.5m',
  'db.mysql.threads.connected', 'db.mysql.threads.running', 'db.mysql.row.lock.waits',
  'db.up', 'valkey.up', 'online.users', 'login.fail.5m',
];

function openRuleEditor(rule?: AlertRule): void {
  if (rule) {
    Object.assign(ruleForm, {
      ruleId: rule.ruleId,
      name: rule.name,
      metric: rule.metric,
      comparator: rule.comparator,
      threshold: rule.threshold,
      durationPoints: rule.durationPoints,
      level: rule.level,
      remark: rule.remark ?? '',
    });
    editingBuiltin.value = rule.isSystem === 1;
  } else {
    Object.assign(ruleForm, { ruleId: 0, name: '', metric: '', comparator: 'gt', threshold: 80, durationPoints: 2, level: 'warn', remark: '' });
    editingBuiltin.value = false;
  }
  ruleDrawer.value = true;
}

async function saveRule(): Promise<void> {
  const valid = await ruleFormRef.value?.validate().catch(() => false);
  if (!valid) return;
  ruleSaving.value = true;
  try {
    const body = {
      name: ruleForm.name,
      metric: ruleForm.metric,
      comparator: ruleForm.comparator,
      threshold: ruleForm.threshold,
      durationPoints: ruleForm.durationPoints,
      level: ruleForm.level,
      remark: ruleForm.remark,
    };
    if (ruleForm.ruleId) {
      await updateRule(ruleForm.ruleId, body);
      ElMessage.success('规则已更新');
    } else {
      await createRule(body);
      ElMessage.success('规则已创建');
    }
    ruleDrawer.value = false;
    await loadRules(1);
  } finally {
    ruleSaving.value = false;
  }
}

async function doDeleteRule(rule: AlertRule): Promise<void> {
  if (rule.isSystem === 1) {
    ElMessage.error('内置规则不允许删除(可停用)');
    return;
  }
  await ElMessageBox.confirm(`确认删除规则「${rule.name}」？删除后历史告警保留。`, '删除规则', { type: 'warning' });
  await deleteRule(rule.ruleId);
  ElMessage.success('规则已删除');
  await loadRules(1);
}

async function doToggleRule(rule: AlertRule, enabled: boolean): Promise<void> {
  await toggleRule(rule.ruleId, enabled ? 1 : 0);
  ElMessage.success(`规则「${rule.name}」已${enabled ? '启用' : '停用'}`);
  await loadRules(rulePage.current);
}

// ---------- 刷新事件 ----------
function onRefresh(): void {
  void loadActive();
  if (activeTab.value === 'history') void loadHistory(historyPage.current);
  if (activeTab.value === 'rules') void loadRules(rulePage.current);
}

/** 当前告警 30s 轮询(DD-24):usePolling 自动刷新 + visibilitychange 暂停;仅在当前告警 tab 生效。 */
const { autoRefresh } = usePolling(() => {
  if (activeTab.value === 'active') void loadActive();
}, 30000);

onMounted(() => {
  void loadActive();
  void loadHistory(1);
  void loadRules(1);
  window.addEventListener('monitor-refresh', onRefresh);
});

onUnmounted(() => {
  window.removeEventListener('monitor-refresh', onRefresh);
});
</script>

<style>
.cnt {
  background: rgba(var(--app-danger-rgb), 0.12);
  color: var(--app-danger);
  border-radius: 8px;
  font-size: 10.5px;
  padding: 1px 6px;
  font-weight: 700;
}
</style>
