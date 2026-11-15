<template>
  <div class="page">
    <el-tabs v-model="activeTab">
      <!-- 活动 + 已确认告警:按级别权重排序(§6.7) -->
      <el-tab-pane label="活动告警" name="active">
        <div class="toolbar">
          <el-button :loading="activeLoading" @click="loadActive">刷新</el-button>
          <span class="hint">活动告警按 严重>警告>提示 排序;「确认」表示已知悉,恢复判定仍由连续 2 个未命中自动处理</span>
        </div>
        <el-table :data="activeList" v-loading="activeLoading" size="default" border>
          <el-table-column label="级别" width="90">
            <template #default="{ row }">
              <el-tag :type="levelType(row.level)" size="small">{{ levelText(row.level) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="ruleName" label="规则" min-width="160" />
          <el-table-column prop="metric" label="指标" min-width="160" />
          <el-table-column label="触发值 / 阈值" width="150">
            <template #default="{ row }">{{ row.triggerValue }} {{ row.comparator }} {{ row.threshold }}</template>
          </el-table-column>
          <el-table-column label="首次触发" width="170">
            <template #default="{ row }">{{ formatTime(row.firstTime) }}</template>
          </el-table-column>
          <el-table-column label="最近触发" width="170">
            <template #default="{ row }">{{ formatTime(row.lastTime) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">{{ row.status === 'acked' ? '已确认' : '触发中' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button size="small" link type="primary" @click="openDetail(row.alertId)">详情</el-button>
              <el-button v-if="row.status === 'firing'" size="small" link type="warning" @click="doAck(row as MonitorAlert)">确认</el-button>
              <el-button size="small" link @click="openSilence(row as MonitorAlert)">静默</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 历史告警(已恢复) -->
      <el-tab-pane label="历史告警" name="history">
        <div class="toolbar">
          <el-select v-model="historyLevel" placeholder="级别" clearable size="small" style="width: 120px">
            <el-option label="严重" value="critical" />
            <el-option label="警告" value="warn" />
            <el-option label="提示" value="info" />
          </el-select>
          <el-button size="small" :loading="historyLoading" @click="loadHistory(1)">查询</el-button>
        </div>
        <el-table :data="historyPage.rows" v-loading="historyLoading" size="default" border>
          <el-table-column label="级别" width="90">
            <template #default="{ row }">
              <el-tag :type="levelType(row.level)" size="small">{{ levelText(row.level) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="ruleName" label="规则" min-width="160" />
          <el-table-column prop="metric" label="指标" min-width="160" />
          <el-table-column label="触发值 / 阈值" width="150">
            <template #default="{ row }">{{ row.triggerValue }} {{ row.comparator }} {{ row.threshold }}</template>
          </el-table-column>
          <el-table-column label="首次触发" width="170">
            <template #default="{ row }">{{ formatTime(row.firstTime) }}</template>
          </el-table-column>
          <el-table-column label="恢复时间" width="170">
            <template #default="{ row }">{{ formatTime(row.resolveTime) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button size="small" link type="primary" @click="openDetail(row.alertId)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          class="pager"
          layout="total, prev, pager, next"
          :total="historyPage.total"
          :page-size="historyPage.size"
          :current-page="historyPage.current"
          @current-change="(p: number) => loadHistory(p)"
        />
      </el-tab-pane>

      <!-- 规则管理:内置规则只读(§6.7,DD-16 静默 1-1440 分钟) -->
      <el-tab-pane label="告警规则" name="rules">
        <div class="toolbar">
          <el-button size="small" type="primary" v-permission="'platform:monitor:alert:rule'" @click="openRuleEditor()">新建规则</el-button>
          <el-button size="small" :loading="rulesLoading" @click="loadRules(1)">刷新</el-button>
        </div>
        <el-table :data="rulePage.rows" v-loading="rulesLoading" size="default" border>
          <el-table-column prop="name" label="名称" min-width="150" />
          <el-table-column prop="metric" label="指标" min-width="170" />
          <el-table-column label="条件" width="160">
            <template #default="{ row }">连续 {{ row.durationPoints }} 点 {{ comparatorText(row.comparator) }} {{ row.threshold }}</template>
          </el-table-column>
          <el-table-column label="级别" width="80">
            <template #default="{ row }">
              <el-tag :type="levelType(row.level)" size="small">{{ levelText(row.level) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-switch
                :model-value="row.enabled === 1"
                :disabled="row.isSystem === 1 || !auth.has('platform:monitor:alert:rule')"
                @change="(v: string | number | boolean) => doToggleRule(row as AlertRule, Boolean(v))"
              />
            </template>
          </el-table-column>
          <el-table-column label="静默至" width="170">
            <template #default="{ row }">{{ row.silenceUntil ? formatTime(row.silenceUntil) : '—' }}</template>
          </el-table-column>
          <el-table-column label="备注" min-width="120">
            <template #default="{ row }">{{ row.remark || '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <template v-if="row.isSystem !== 1">
                <el-button size="small" link type="primary" v-permission="'platform:monitor:alert:rule'" @click="openRuleEditor(row as AlertRule)">编辑</el-button>
                <el-button size="small" link type="danger" v-permission="'platform:monitor:alert:rule'" @click="doDeleteRule(row as AlertRule)">删除</el-button>
              </template>
              <span v-else class="builtin-tag">内置</span>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          class="pager"
          layout="total, prev, pager, next"
          :total="rulePage.total"
          :page-size="rulePage.size"
          :current-page="rulePage.current"
          @current-change="(p: number) => loadRules(p)"
        />
      </el-tab-pane>
    </el-tabs>

    <!-- 告警详情抽屉:触发序列 + 通知记录 -->
    <el-drawer v-model="detailDrawer" title="告警详情" size="620px">
      <template v-if="detail">
        <el-descriptions :column="2" size="small" border>
          <el-descriptions-item label="规则">{{ detail.alert.ruleName }}</el-descriptions-item>
          <el-descriptions-item label="级别">{{ levelText(detail.alert.level) }}</el-descriptions-item>
          <el-descriptions-item label="指标">{{ detail.alert.metric }}</el-descriptions-item>
          <el-descriptions-item label="触发值 / 阈值">
            {{ detail.alert.triggerValue }} {{ detail.alert.comparator }} {{ detail.alert.threshold }}
          </el-descriptions-item>
          <el-descriptions-item label="首次触发">{{ formatTime(detail.alert.firstTime) }}</el-descriptions-item>
          <el-descriptions-item label="恢复时间">{{ formatTime(detail.alert.resolveTime) }}</el-descriptions-item>
          <el-descriptions-item label="确认人">{{ detail.alert.ackBy || '—' }}</el-descriptions-item>
          <el-descriptions-item label="静默至">{{ formatTime(detail.alert.silenceUntil) }}</el-descriptions-item>
        </el-descriptions>
        <h5>触发序列</h5>
        <p v-if="detail.seriesExpired" class="hint">采样数据已过期(超出保留期),序列不可用</p>
        <div ref="detailChartEl" class="detail-chart" v-show="!detail.seriesExpired" />
        <h5>通知记录</h5>
        <el-table v-if="detail.notifyRecords.length" :data="detail.notifyRecords" size="small" border>
          <el-table-column label="时间" width="160">
            <template #default="{ row }">{{ formatTime(row.time) }}</template>
          </el-table-column>
          <el-table-column prop="type" label="类型" width="90" />
          <el-table-column prop="recipients" label="接收人数" width="90" />
          <el-table-column label="通道" min-width="120">
            <template #default="{ row }">{{ row.channels.join(' / ') }}</template>
          </el-table-column>
          <el-table-column label="错误" min-width="140">
            <template #default="{ row }">{{ row.error || '—' }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="无通知记录(提示级别不通知)" :image-size="60" />
      </template>
    </el-drawer>

    <!-- 静默对话框 -->
    <el-dialog v-model="silenceDialog" title="静默告警" width="420px">
      <p class="hint">静默期间该告警不评估、不通知(1-1440 分钟);到期自动恢复评估。</p>
      <el-input-number v-model="silenceMinutes" :min="1" :max="1440" :step="10" style="width: 100%" />
      <template #footer>
        <el-button @click="silenceDialog = false">取消</el-button>
        <el-button type="primary" :loading="silencing" @click="doSilence">确认静默</el-button>
      </template>
    </el-dialog>

    <!-- 规则编辑抽屉 -->
    <el-drawer v-model="ruleDrawer" :title="ruleForm.ruleId ? '编辑规则' : '新建规则'" size="480px">
      <el-form ref="ruleFormRef" :model="ruleForm" :rules="ruleRules" label-width="110px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="ruleForm.name" maxlength="64" />
        </el-form-item>
        <el-form-item label="指标" prop="metric">
          <el-select v-model="ruleForm.metric" style="width: 100%">
            <el-option v-for="m in metricOptions" :key="m.value" :label="m.label" :value="m.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="比较符" prop="comparator">
          <el-select v-model="ruleForm.comparator" style="width: 100%">
            <el-option label="大于 >" value="gt" />
            <el-option label="小于 <" value="lt" />
            <el-option label="等于 =" value="eq" />
          </el-select>
        </el-form-item>
        <el-form-item label="阈值" prop="threshold">
          <el-input-number v-model="ruleForm.threshold" :min="0" style="width: 100%" />
        </el-form-item>
        <el-form-item label="连续点数" prop="durationPoints">
          <el-input-number v-model="ruleForm.durationPoints" :min="1" :max="5" style="width: 100%" />
        </el-form-item>
        <el-form-item label="级别" prop="level">
          <el-select v-model="ruleForm.level" style="width: 100%">
            <el-option label="严重" value="critical" />
            <el-option label="警告" value="warn" />
            <el-option label="提示" value="info" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="ruleForm.remark" type="textarea" :rows="2" maxlength="200" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleDrawer = false">取消</el-button>
        <el-button type="primary" :loading="ruleSaving" @click="saveRule">保存</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script lang="ts" setup>
import { onMounted, reactive, ref } from 'vue';
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
import { useChart, echarts } from '@/composables/useChart';

const auth = useAuthStore();

const activeTab = ref('active');

// ---- 活动告警 ----
const activeList = ref<MonitorAlert[]>([]);
const activeLoading = ref(false);

async function loadActive(): Promise<void> {
  activeLoading.value = true;
  try {
    activeList.value = await fetchActiveAlerts();
  } finally {
    activeLoading.value = false;
  }
}

// ---- 历史告警 ----
const historyLevel = ref('');
const historyLoading = ref(false);
const historyPage = reactive({ rows: [] as MonitorAlert[], total: 0, size: 20, current: 1 });

async function loadHistory(page: number): Promise<void> {
  historyLoading.value = true;
  try {
    const params: Record<string, unknown> = { page, size: historyPage.size, status: 'resolved' };
    if (historyLevel.value) params.level = historyLevel.value;
    const result = await fetchAlertPage(params);
    historyPage.rows = result.rows;
    historyPage.total = result.total;
    historyPage.current = page;
  } finally {
    historyLoading.value = false;
  }
}

// ---- 规则 ----
const rulePage = reactive({ rows: [] as AlertRule[], total: 0, size: 20, current: 1 });
const rulesLoading = ref(false);

async function loadRules(page: number): Promise<void> {
  rulesLoading.value = true;
  try {
    const result = await fetchRulePage({ page, size: rulePage.size });
    rulePage.rows = result.rows;
    rulePage.total = result.total;
    rulePage.current = page;
  } finally {
    rulesLoading.value = false;
  }
}

// ---- 文案辅助 ----
function levelType(level: string): 'danger' | 'warning' | 'info' {
  return level === 'critical' ? 'danger' : level === 'warn' ? 'warning' : 'info';
}

function levelText(level: string): string {
  return level === 'critical' ? '严重' : level === 'warn' ? '警告' : '提示';
}

function comparatorText(c: string): string {
  return c === 'gt' ? '>' : c === 'lt' ? '<' : '=';
}

function formatTime(value?: string | null): string {
  if (!value) return '—';
  return value.replace('T', ' ').slice(0, 19);
}

// ---- 详情抽屉 ----
const detailDrawer = ref(false);
const detail = ref<AlertDetail | null>(null);
const detailChart = useChart((chart, colors) => renderDetail(chart, colors));
const detailChartEl = detailChart.el;

function renderDetail(chart: echarts.ECharts, colors: string[]): void {
  const d = detail.value;
  if (!d) return;
  chart.setOption({
    color: colors,
    tooltip: { trigger: 'axis' },
    grid: { left: 48, right: 24, top: 16, bottom: 32 },
    xAxis: { type: 'time' },
    yAxis: { type: 'value', scale: true },
    series: [{ name: d.alert.metric, type: 'line', showSymbol: false, data: d.series.map((p) => [p.t, p.v]) }],
  });
}

async function openDetail(alertId: number): Promise<void> {
  detail.value = await fetchAlertDetail(alertId);
  detailDrawer.value = true;
  if (!detail.value.seriesExpired) {
    requestAnimationFrame(() => detailChart.update());
  }
}

// ---- 确认 / 静默 ----
async function doAck(row: MonitorAlert): Promise<void> {
  await ElMessageBox.confirm(`确认已知悉「${row.ruleName}」?`, '确认告警', { type: 'warning' });
  await ackAlert(row.alertId);
  ElMessage.success('已确认');
  await loadActive();
}

const silenceDialog = ref(false);
const silenceMinutes = ref(60);
const silencing = ref(false);
let silenceTarget: MonitorAlert | null = null;

function openSilence(row: MonitorAlert): void {
  silenceTarget = row;
  silenceMinutes.value = 60;
  silenceDialog.value = true;
}

async function doSilence(): Promise<void> {
  if (!silenceTarget) return;
  silencing.value = true;
  try {
    const until = await silenceAlert(silenceTarget.alertId, silenceMinutes.value);
    ElMessage.success(`已静默至 ${formatTime(until)}`);
    silenceDialog.value = false;
    await loadActive();
  } finally {
    silencing.value = false;
  }
}

// ---- 规则编辑 ----
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
const ruleRules: FormRules = {
  name: [{ required: true, message: '请输入规则名称', trigger: 'blur' }],
  metric: [{ required: true, message: '请选择指标', trigger: 'change' }],
  threshold: [{ required: true, message: '请输入阈值', trigger: 'blur' }],
};

const metricOptions = [
  'system.cpu.usage', 'process.cpu.usage', 'jvm.heap.used.percent', 'jvm.heap.used.mb',
  'jvm.nonheap.used.mb', 'jvm.threads.live', 'jvm.gc.pause.ms', 'disk.usage.percent',
  'http.server.qps', 'http.server.rt.avg.ms', 'http.server.error.rate',
  'db.pool.active', 'db.pool.idle', 'db.pool.pending', 'db.pool.usage.percent',
  'db.sql.qps', 'db.sql.rt.avg.ms', 'db.sql.slow.5m',
  'db.mysql.threads.connected', 'db.mysql.threads.running', 'db.mysql.row.lock.waits',
  'db.up', 'valkey.up', 'online.users', 'login.fail.5m',
].map((v) => ({ value: v, label: v }));

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
  } else {
    Object.assign(ruleForm, { ruleId: 0, name: '', metric: '', comparator: 'gt', threshold: 80, durationPoints: 2, level: 'warn', remark: '' });
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
    } else {
      await createRule(body);
    }
    ElMessage.success('已保存');
    ruleDrawer.value = false;
    await loadRules(1);
  } finally {
    ruleSaving.value = false;
  }
}

async function doDeleteRule(rule: AlertRule): Promise<void> {
  await ElMessageBox.confirm(`删除规则「${rule.name}」?删除后不可恢复。`, '删除规则', { type: 'warning' });
  await deleteRule(rule.ruleId);
  ElMessage.success('已删除');
  await loadRules(1);
}

async function doToggleRule(rule: AlertRule, enabled: boolean): Promise<void> {
  await toggleRule(rule.ruleId, enabled ? 1 : 0);
  ElMessage.success(enabled ? '规则已启用' : '规则已停用');
  await loadRules(rulePage.current);
}

onMounted(() => {
  void loadActive();
  void loadHistory(1);
  void loadRules(1);
});
</script>

<style scoped>
.page {
  padding: 16px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.hint {
  font-size: 12px;
  color: var(--text-secondary);
  margin: 0;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.builtin-tag {
  font-size: 12px;
  color: var(--text-secondary);
}

.detail-chart {
  height: 220px;
  width: 100%;
}

h5 {
  margin: 16px 0 8px;
  font-size: 13px;
}
</style>
