<template>
  <div class="card">
    <el-tabs v-model="activeTab">
      <!-- 应用日志:文件(file)/selector(loki)/时间/级别/traceId/关键字过滤 + 实时 tail(D-05,§7.4) -->
      <el-tab-pane label="应用日志" name="app">
        <div class="filter-bar">
          <template v-if="logSource === 'file'">
            <label>文件</label>
            <el-select v-model="fileId" size="small" style="width: 200px" @change="loadAppPage(1)">
              <el-option v-for="f in files" :key="f.fileId" :label="f.name" :value="f.fileId" />
            </el-select>
          </template>
          <span v-else class="chip chip-info mono" :title="files[0]?.name">Loki · {{ files[0]?.name || 'selector' }}</span>
          <label>时间</label>
          <el-select v-model="rangeMinutes" size="small" style="width: 110px">
            <el-option label="近 15 分钟" :value="15" />
            <el-option label="近 1 小时" :value="60" />
            <el-option label="近 6 小时" :value="360" />
            <el-option label="近 24 小时" :value="1440" />
          </el-select>
          <label>级别</label>
          <el-select v-model="appLevel" size="small" style="width: 92px" clearable placeholder="全部">
            <el-option label="ERROR" value="ERROR" />
            <el-option label="WARN" value="WARN" />
            <el-option label="INFO" value="INFO" />
            <el-option label="DEBUG" value="DEBUG" />
          </el-select>
          <el-input v-model="appTrace" placeholder="traceId 精确匹配" size="small" class="mono" style="width: 170px" @keyup.enter="loadAppPage(1)" />
          <el-input v-model="appKeyword" placeholder="关键字（子串匹配）" size="small" style="width: 180px" @keyup.enter="loadAppPage(1)" />
          <el-button size="small" type="primary" :loading="appLoading" @click="loadAppPage(1)">查询</el-button>
          <el-button size="small" @click="resetFilters">重置</el-button>
          <span style="flex: 1" />
          <label>实时 tail</label>
          <el-switch v-model="tailOn" size="small" />
        </div>
        <div class="muted" style="font-size: 11.5px; margin-bottom: 8px">
          扫描 {{ appVo?.scannedLines ?? 0 }} 行 · 返回 {{ appVo?.rows.length ?? 0 }} 条 · 文件 {{ activeFileName }}
          <span v-if="appVo?.truncated" style="color: var(--app-warning)"> · ⚠ 结果已截断，请缩小时间窗或追加过滤条件</span>
        </div>
        <el-table
          :data="appVo?.rows ?? []"
          v-loading="appLoading"
          size="default"
          @row-click="(row: LogLine) => logSource === 'file' && openLogDetail(row)"
        >
          <el-table-column label="时间" width="160">
            <template #default="{ row }"><span class="mono" style="font-size: 11.5px">{{ row.time }}</span></template>
          </el-table-column>
          <el-table-column label="级别" width="72">
            <template #default="{ row }">
              <span class="chip" :class="levelChip(row.level)">{{ row.level }}</span>
            </template>
          </el-table-column>
          <el-table-column label="traceId" width="150">
            <template #default="{ row }">
              <a
                v-if="row.traceId"
                class="link mono"
                style="font-size: 11px"
                @click.stop="filterByTrace(row.traceId)"
              >{{ row.traceId }}</a>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="线程" width="130">
            <template #default="{ row }"><span class="mono" style="font-size: 11px">{{ row.thread }}</span></template>
          </el-table-column>
          <el-table-column label="Logger" width="220">
            <template #default="{ row }"><span class="mono" style="font-size: 11px" :title="row.logger">{{ row.logger }}</span></template>
          </el-table-column>
          <el-table-column label="消息">
            <template #default="{ row }">
              <span style="display: inline-block; max-width: 420px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; vertical-align: middle" :title="row.message">{{ firstLine(row.message) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          layout="prev, pager, next"
          :total="appPager.total"
          :page-size="appPager.size"
          :current-page="appPager.current"
          @current-change="(p: number) => loadAppPage(p)"
        />
      </el-tab-pane>

      <!-- 操作日志:复用既有端点不改(§4.6) -->
      <el-tab-pane label="操作日志" name="oper">
        <div class="muted" style="font-size: 11.5px; margin-bottom: 10px">
          复用既有端点 <span class="mono">/system/log/oper/page</span> · 权限码 system:operlog:list（运维角色已授予）
        </div>
        <div class="filter-bar">
          <el-input v-model="operKeyword" placeholder="操作人 / 模块" size="small" style="width: 180px" @keyup.enter="loadOperPage(1)" />
          <el-select v-model="operStatus" size="small" style="width: 110px" clearable placeholder="全部状态">
            <el-option label="成功" value="true" />
            <el-option label="失败" value="false" />
          </el-select>
          <el-button size="small" type="primary" :loading="operLoading" @click="loadOperPage(1)">查询</el-button>
        </div>
        <el-table :data="operPage.rows" v-loading="operLoading" size="default">
          <el-table-column label="操作时间" width="160">
            <template #default="{ row }"><span class="mono">{{ formatTime(row.createTime) }}</span></template>
          </el-table-column>
          <el-table-column label="操作人" width="110">
            <template #default="{ row }">{{ row.username ?? row.operName ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="模块" width="130">
            <template #default="{ row }">{{ row.module ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="操作类型" min-width="150">
            <template #default="{ row }">{{ row.action ?? row.description ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <span class="chip" :class="String(row.success ?? row.status) === 'false' ? 'chip-danger' : 'chip-success'">
                {{ String(row.success ?? row.status) === 'false' ? '失败' : '成功' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="traceId" width="140">
            <template #default="{ row }"><span class="mono" style="font-size: 11px">{{ row.traceId ?? '—' }}</span></template>
          </el-table-column>
          <el-table-column label="详情">
            <template #default="{ row }">
              <span style="display: inline-block; max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; vertical-align: middle" :title="String(row.detail ?? row.errorMsg ?? '')">{{ firstLine(String(row.detail ?? row.errorMsg ?? '—')) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          layout="total, prev, pager, next"
          :total="operPage.total"
          :page-size="operPage.size"
          :current-page="operPage.current"
          @current-change="(p: number) => loadOperPage(p)"
        />
      </el-tab-pane>

      <!-- 登录日志:复用既有端点不改 -->
      <el-tab-pane label="登录日志" name="login">
        <div class="muted" style="font-size: 11.5px; margin-bottom: 10px">
          复用既有端点 <span class="mono">/system/log/login/page</span> · 权限码 system:loginlog:list
        </div>
        <div class="filter-bar">
          <el-input v-model="loginKeyword" placeholder="登录名" size="small" style="width: 160px" @keyup.enter="loadLoginPage(1)" />
          <el-select v-model="loginResult" size="small" style="width: 100px" clearable placeholder="全部状态">
            <el-option label="成功" value="true" />
            <el-option label="失败" value="false" />
          </el-select>
          <el-button size="small" type="primary" :loading="loginLoading" @click="loadLoginPage(1)">查询</el-button>
        </div>
        <el-table :data="loginPage.rows" v-loading="loginLoading" size="default">
          <el-table-column label="登录时间" width="160">
            <template #default="{ row }"><span class="mono">{{ formatTime(row.createTime ?? row.loginTime) }}</span></template>
          </el-table-column>
          <el-table-column label="登录名" width="120">
            <template #default="{ row }">{{ row.username ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="端" width="90">
            <template #default="{ row }">{{ row.scene ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="类型" width="90">
            <template #default="{ row }">{{ row.loginType ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="IP" width="140">
            <template #default="{ row }"><span class="mono">{{ row.ip ?? row.loginIp ?? '—' }}</span></template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <span class="chip" :class="String(row.success ?? row.status) === 'false' ? 'chip-danger' : 'chip-success'">
                {{ String(row.success ?? row.status) === 'false' ? '失败' : '成功' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="消息">
            <template #default="{ row }">{{ row.failReason ?? row.message ?? '—' }}</template>
          </el-table-column>
        </el-table>
        <el-pagination
          layout="total, prev, pager, next"
          :total="loginPage.total"
          :page-size="loginPage.size"
          :current-page="loginPage.current"
          @current-change="(p: number) => loadLoginPage(p)"
        />
      </el-tab-pane>
    </el-tabs>

    <!-- 日志详情抽屉:kv + 上下文 log-line(照原型) -->
    <el-drawer v-model="detailDrawer" size="620px" :title="detailVo ? `日志详情 · 行 ${detailVo.target.lineNo}` : '日志详情'">
      <template v-if="detailVo">
        <div class="kv" style="margin-bottom: 14px">
          <div>文件</div><div class="mono">{{ detailVo.target.fileId }}</div>
          <div>行号</div><div class="mono">{{ detailVo.target.lineNo }}</div>
          <div>traceId</div><div class="mono">{{ detailVo.target.traceId || '—' }}</div>
          <div>线程 / Logger</div><div class="mono">{{ detailVo.target.thread }} · {{ detailVo.target.logger }}</div>
        </div>
        <div class="card-title">上下文（前 {{ detailVo.before.length }} 行 / 后 {{ detailVo.after.length }} 行）</div>
        <div style="background: var(--app-soft); border: 1px solid var(--app-line); border-radius: 9px; padding: 10px">
          <div
            v-for="l in detailVo.before"
            :key="'b' + l.lineNo"
            class="log-line ctx"
            :class="`lv-${l.level}`"
          >{{ logLineText(l) }}</div>
          <div
            class="log-line target"
            :class="`lv-${detailVo.target.level}`"
          >{{ logLineText(detailVo.target) }}</div>
          <div
            v-for="l in detailVo.after"
            :key="'a' + l.lineNo"
            class="log-line ctx"
            :class="`lv-${l.level}`"
          >{{ logLineText(l) }}</div>
        </div>
        <p
          v-if="detailVo.target.level === 'ERROR'"
          class="muted"
          style="font-size: 12px; margin-top: 12px"
        >
          可复制 traceId <span class="mono link">{{ detailVo.target.traceId || '—' }}</span> 到操作/登录日志互查关联记录。
        </p>
      </template>
    </el-drawer>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import {
  fetchLogDetail,
  fetchLogFiles,
  fetchLogPage,
  fetchLogTail,
  fetchLoginLogPage,
  fetchOperLogPage,
  type AppLogDetailVO,
  type AppLogPageVO,
  type LogLine,
} from '@/api/monitor/log';

const activeTab = ref('app');

const route = useRoute();

// ---------- 应用日志 ----------
const files = ref<{ fileId: string; name: string }[]>([]);
const appVo = ref<AppLogPageVO | null>(null);
const appLoading = ref(false);
const appPager = reactive({ total: 0, size: 100, current: 1 });
const rangeMinutes = ref(60);
const appLevel = ref('');
const appTrace = ref('');
const appKeyword = ref('');
/** 日志源模式(§7.4):file 显示文件下拉、行可点开上下文;loki 显示 selector、隐藏行上下文入口。 */
const logSource = ref('file');
const fileId = ref('');

const activeFileName = computed(() => {
  const f = files.value.find((item) => item.fileId === (fileId.value || appVo.value?.source));
  return f ? `${f.name}（active）` : appVo.value?.source || '—';
});

async function loadFiles(): Promise<void> {
  const vo = await fetchLogFiles();
  logSource.value = vo.source;
  files.value = vo.files;
  fileId.value = vo.files[0]?.fileId ?? 'active';
}

function rangeToParams(): Record<string, unknown> {
  const params: Record<string, unknown> = {
    page: appPager.current,
    size: appPager.size,
  };
  if (logSource.value === 'file') params.fileId = fileId.value || files.value[0]?.fileId || 'active';
  if (appLevel.value) params.level = appLevel.value;
  if (appTrace.value.trim()) params.traceId = appTrace.value.trim();
  if (appKeyword.value.trim()) params.keyword = appKeyword.value.trim();
  const end = new Date();
  const begin = new Date(end.getTime() - rangeMinutes.value * 60000);
  const fmt = (d: Date) => {
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:00`;
  };
  params.beginTime = fmt(begin);
  params.endTime = fmt(end);
  return params;
}

async function loadAppPage(page: number): Promise<void> {
  appPager.current = page;
  appLoading.value = true;
  try {
    appVo.value = await fetchLogPage(rangeToParams());
    // rows 满页说明可能还有下一页(端点无 total)
    appPager.total = appVo.value.rows.length === appPager.size ? appPager.current * appPager.size + appPager.size : appPager.current * appPager.size;
  } finally {
    appLoading.value = false;
  }
}

function resetFilters(): void {
  appLevel.value = '';
  appTrace.value = '';
  appKeyword.value = '';
  rangeMinutes.value = 60;
  void loadAppPage(1);
}

function filterByTrace(traceId: string): void {
  appTrace.value = traceId;
  void loadAppPage(1);
}

/** 追踪模式:5s 轮询 tail,新行追加到顶部,最多保留 200 行(§6.8);页面隐藏暂停、恢复立即补拉(DD-24)。 */
const tailOn = ref(false);
let tailTimer: number | undefined;
let tailSince = 0;

function onVisibility(): void {
  if (!document.hidden && tailOn.value) void pollTail();
}

watch(tailOn, (on) => {
  if (on) {
    tailSince = Date.now();
    tailTimer = window.setInterval(() => {
      if (document.hidden) return;
      void pollTail();
    }, 5000);
    document.addEventListener('visibilitychange', onVisibility);
  } else if (tailTimer) {
    window.clearInterval(tailTimer);
    tailTimer = undefined;
    document.removeEventListener('visibilitychange', onVisibility);
  }
});

async function pollTail(): Promise<void> {
  try {
    const lines = await fetchLogTail(tailSince, 200);
    if (!lines.length || !appVo.value) return;
    const known = new Set(appVo.value.rows.map((r) => `${r.fileId}:${r.lineNo}`));
    const fresh = lines.filter((l) => !known.has(`${l.fileId}:${l.lineNo}`));
    if (fresh.length) {
      appVo.value = { ...appVo.value, rows: [...fresh.reverse(), ...appVo.value.rows].slice(0, 200) };
      tailSince = Math.max(...lines.map((l) => Date.parse(l.time.replace(' ', 'T'))));
    }
  } catch {
    /* 轮询失败静默,下一轮重试 */
  }
}

// ---------- 详情 ----------
const detailDrawer = ref(false);
const detailVo = ref<AppLogDetailVO | null>(null);

async function openLogDetail(row: LogLine): Promise<void> {
  detailVo.value = await fetchLogDetail(row.fileId, row.lineNo, 50);
  detailDrawer.value = true;
}

function logLineText(l: LogLine): string {
  return `${l.time} ${l.level.padEnd(5)} [${l.traceId || '-'}] [${l.thread}] ${l.logger} - ${firstLine(l.message)}`;
}

// ---------- 操作日志 ----------
const operKeyword = ref('');
const operStatus = ref('');
const operLoading = ref(false);
const operPage = reactive({ rows: [] as Record<string, unknown>[], total: 0, size: 20, current: 1 });

async function loadOperPage(page: number): Promise<void> {
  operLoading.value = true;
  try {
    const params: Record<string, unknown> = { page, size: operPage.size };
    if (operKeyword.value.trim()) params.keyword = operKeyword.value.trim();
    if (operStatus.value) params.success = operStatus.value;
    const result = await fetchOperLogPage(params);
    operPage.rows = result.rows;
    operPage.total = result.total;
    operPage.current = page;
  } finally {
    operLoading.value = false;
  }
}

// ---------- 登录日志 ----------
const loginKeyword = ref('');
const loginResult = ref('');
const loginLoading = ref(false);
const loginPage = reactive({ rows: [] as Record<string, unknown>[], total: 0, size: 20, current: 1 });

async function loadLoginPage(page: number): Promise<void> {
  loginLoading.value = true;
  try {
    const params: Record<string, unknown> = { page, size: loginPage.size };
    if (loginKeyword.value.trim()) params.username = loginKeyword.value.trim();
    if (loginResult.value) params.success = loginResult.value;
    const result = await fetchLoginLogPage(params);
    loginPage.rows = result.rows;
    loginPage.total = result.total;
    loginPage.current = page;
  } finally {
    loginLoading.value = false;
  }
}

// ---------- 辅助 ----------
function levelChip(level: string): string {
  return level === 'ERROR' ? 'chip-danger' : level === 'WARN' ? 'chip-warning' : level === 'DEBUG' ? 'chip-muted' : 'chip-info';
}

function formatTime(value?: unknown): string {
  if (!value) return '—';
  return String(value).replace('T', ' ').slice(0, 19);
}

function firstLine(message: string): string {
  return message.split('\n')[0] ?? '';
}

function onRefresh(): void {
  if (activeTab.value === 'app') void loadAppPage(appPager.current);
  if (activeTab.value === 'oper') void loadOperPage(operPage.current);
  if (activeTab.value === 'login') void loadLoginPage(loginPage.current);
}

onMounted(async () => {
  await loadFiles();
  // DD-21:/log?traceId=xxx 自动切应用日志 tab、填参并查询;时间窗放宽到 24h 提高命中率
  const queryTrace = route.query.traceId;
  if (typeof queryTrace === 'string' && queryTrace) {
    activeTab.value = 'app';
    appTrace.value = queryTrace;
    rangeMinutes.value = 1440;
  }
  await loadAppPage(1);
  await loadOperPage(1);
  await loadLoginPage(1);
  window.addEventListener('monitor-refresh', onRefresh);
});

onUnmounted(() => {
  window.removeEventListener('monitor-refresh', onRefresh);
  if (tailTimer) window.clearInterval(tailTimer);
});
</script>
