<template>
  <div class="page">
    <el-tabs v-model="activeTab">
      <!-- 应用日志:文件白名单 + 级别/时间/关键字过滤,追踪模式(D-05) -->
      <el-tab-pane label="应用日志" name="app">
        <div class="toolbar">
          <el-select v-model="appQuery.fileId" placeholder="日志文件" size="small" style="width: 260px" @change="loadAppPage(1)">
            <el-option v-for="f in files" :key="f.fileId" :label="`${f.name}(${formatSize(f.sizeBytes)})`" :value="f.fileId" />
          </el-select>
          <el-select v-model="appQuery.level" placeholder="级别" clearable size="small" style="width: 110px">
            <el-option label="ERROR" value="ERROR" />
            <el-option label="WARN" value="WARN" />
            <el-option label="INFO" value="INFO" />
            <el-option label="DEBUG" value="DEBUG" />
          </el-select>
          <el-date-picker
            v-model="appQuery.range"
            type="datetimerange"
            size="small"
            value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 340px"
          />
          <el-input v-model="appQuery.keyword" placeholder="消息/traceId 关键字" size="small" clearable style="width: 220px" @keyup.enter="loadAppPage(1)" />
          <el-checkbox v-model="tailOn" size="small">追踪模式</el-checkbox>
          <el-button size="small" type="primary" :loading="appLoading" @click="loadAppPage(1)">查询</el-button>
        </div>
        <p v-if="appVo?.truncated" class="hint warning">结果已截断:扫描行数上限 {{ appVo.scannedLines }} 行,请缩小时间范围或追加过滤条件</p>
        <p class="hint">共扫描 {{ appVo?.scannedLines ?? 0 }} 行 · 文件仅提供白名单内的本机日志;Loki 数据源下关键字走 LogQL |=</p>
        <el-table :data="appVo?.rows ?? []" v-loading="appLoading" size="small" border>
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ row.time }}</template>
          </el-table-column>
          <el-table-column label="级别" width="80">
            <template #default="{ row }">
              <el-tag :type="levelType(row.level)" size="small" effect="plain">{{ row.level }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="traceId" label="TraceId" width="150">
            <template #default="{ row }">
              <span class="mono">{{ row.traceId || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="logger" label="Logger" width="220">
            <template #default="{ row }">
              <span class="mono" :title="row.logger">{{ shortLogger(row.logger) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="消息" min-width="300">
            <template #default="{ row }">
              <span class="msg-text" :title="row.message">{{ firstLine(row.message) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80" fixed="right">
            <template #default="{ row }">
              <el-button size="small" link type="primary" @click="openLogDetail(row as LogLine)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          class="pager"
          layout="total, prev, pager, next"
          :total="appPager.total"
          :page-size="appPager.size"
          :current-page="appPager.current"
          @current-change="(p: number) => loadAppPage(p)"
        />
      </el-tab-pane>

      <!-- 操作日志:复用既有端点不改(§4.6) -->
      <el-tab-pane label="操作日志" name="oper">
        <div class="toolbar">
          <el-input v-model="operQuery.username" placeholder="操作人" size="small" clearable style="width: 160px" />
          <el-button size="small" type="primary" :loading="operLoading" @click="loadOperPage(1)">查询</el-button>
        </div>
        <el-table :data="operPage.rows" v-loading="operLoading" size="small" border>
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
          </el-table-column>
          <el-table-column label="操作人" width="120">
            <template #default="{ row }">{{ row.username ?? row.operName ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="模块" width="140">
            <template #default="{ row }">{{ row.module ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="动作" min-width="200">
            <template #default="{ row }">{{ row.action ?? row.description ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="结果" width="100">
            <template #default="{ row }">
              <el-tag :type="String(row.success ?? row.status) === 'false' ? 'danger' : 'success'" size="small" effect="plain">
                {{ String(row.success ?? row.status) === 'false' ? '失败' : '成功' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="详情" min-width="240">
            <template #default="{ row }">
              <span class="msg-text" :title="row.detail ?? row.errorMsg ?? ''">{{ firstLine(String(row.detail ?? row.errorMsg ?? '—')) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          class="pager"
          layout="total, prev, pager, next"
          :total="operPage.total"
          :page-size="operPage.size"
          :current-page="operPage.current"
          @current-change="(p: number) => loadOperPage(p)"
        />
      </el-tab-pane>

      <!-- 登录日志:复用既有端点不改 -->
      <el-tab-pane label="登录日志" name="login">
        <div class="toolbar">
          <el-input v-model="loginQuery.username" placeholder="账号" size="small" clearable style="width: 160px" />
          <el-select v-model="loginQuery.result" placeholder="结果" clearable size="small" style="width: 110px">
            <el-option label="成功" value="true" />
            <el-option label="失败" value="false" />
          </el-select>
          <el-button size="small" type="primary" :loading="loginLoading" @click="loadLoginPage(1)">查询</el-button>
        </div>
        <el-table :data="loginPage.rows" v-loading="loginLoading" size="small" border>
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ formatTime(row.createTime ?? row.loginTime) }}</template>
          </el-table-column>
          <el-table-column label="账号" width="140">
            <template #default="{ row }">{{ row.username ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="来源 IP" width="150">
            <template #default="{ row }">{{ row.ip ?? row.loginIp ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="方式" width="110">
            <template #default="{ row }">{{ row.loginType ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="结果" width="90">
            <template #default="{ row }">
              <el-tag :type="String(row.success ?? row.status) === 'false' ? 'danger' : 'success'" size="small" effect="plain">
                {{ String(row.success ?? row.status) === 'false' ? '失败' : '成功' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="失败原因" min-width="220">
            <template #default="{ row }">{{ row.failReason ?? row.message ?? '—' }}</template>
          </el-table-column>
        </el-table>
        <el-pagination
          class="pager"
          layout="total, prev, pager, next"
          :total="loginPage.total"
          :page-size="loginPage.size"
          :current-page="loginPage.current"
          @current-change="(p: number) => loadLoginPage(p)"
        />
      </el-tab-pane>
    </el-tabs>

    <!-- 日志详情抽屉:上下文 ±50 行(§6.8) -->
    <el-drawer v-model="detailDrawer" title="日志详情(含上下文)" size="720px">
      <template v-if="detailVo">
        <p class="hint">文件 {{ detailVo.target.fileId }} · 定位行 {{ detailVo.target.lineNo }}</p>
        <el-tag :type="levelType(detailVo.target.level)" size="small">{{ detailVo.target.level }}</el-tag>
        <span class="detail-time">{{ detailVo.target.time }}</span>
        <p class="mono msg-detail">{{ detailVo.target.message }}</p>
        <template v-if="detailVo.before.length">
          <h5>上文({{ detailVo.before.length }} 行)</h5>
          <div class="ctx">
            <div v-for="l in detailVo.before" :key="l.lineNo" class="ctx-line" :class="{ err: l.level === 'ERROR' }">
              <span class="ctx-no">{{ l.lineNo }}</span>
              <span class="ctx-msg">{{ firstLine(l.message) }}</span>
            </div>
          </div>
        </template>
        <template v-if="detailVo.after.length">
          <h5>下文({{ detailVo.after.length }} 行)</h5>
          <div class="ctx">
            <div v-for="l in detailVo.after" :key="l.lineNo" class="ctx-line" :class="{ err: l.level === 'ERROR' }">
              <span class="ctx-no">{{ l.lineNo }}</span>
              <span class="ctx-msg">{{ firstLine(l.message) }}</span>
            </div>
          </div>
        </template>
      </template>
    </el-drawer>
  </div>
</template>

<script lang="ts" setup>
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import {
  fetchLogDetail,
  fetchLogFiles,
  fetchLogPage,
  fetchLogTail,
  fetchLoginLogPage,
  fetchOperLogPage,
  type AppLogDetailVO,
  type AppLogPageVO,
  type LogFilesVO,
  type LogLine,
} from '@/api/monitor/log';

const activeTab = ref('app');

// ---- 应用日志 ----
const files = ref<LogFilesVO['files']>([]);
const appVo = ref<AppLogPageVO | null>(null);
const appLoading = ref(false);
const appPager = reactive({ total: 0, size: 100, current: 1 });
const appQuery = reactive({
  fileId: '',
  level: '',
  keyword: '',
  range: null as [string, string] | null,
});

async function loadFiles(): Promise<void> {
  const vo = await fetchLogFiles();
  files.value = vo.files;
  if (!appQuery.fileId && vo.files.length) {
    appQuery.fileId = vo.files[0]!.fileId;
  }
}

async function loadAppPage(page: number): Promise<void> {
  if (!appQuery.fileId) return;
  appLoading.value = true;
  try {
    const params: Record<string, unknown> = {
      fileId: appQuery.fileId,
      page,
      size: appPager.size,
    };
    if (appQuery.level) params.level = appQuery.level;
    if (appQuery.keyword) params.keyword = appQuery.keyword;
    if (appQuery.range) {
      params.beginTime = appQuery.range[0];
      params.endTime = appQuery.range[1];
    }
    appVo.value = await fetchLogPage(params);
    appPager.current = page;
    appPager.total = appVo.value.rows.length < appPager.size ? appPager.current * appPager.size : appPager.current * appPager.size + 1;
  } finally {
    appLoading.value = false;
  }
}

/** 追踪模式:5s 轮询 tail,新行(行号大于当前页尾)追加到顶部,最多保留 200 行(§6.8)。 */
const tailOn = ref(false);
let tailTimer: number | undefined;
let tailSince = 0;

watch(tailOn, (on) => {
  if (on) {
    tailSince = Date.now();
    tailTimer = window.setInterval(() => void pollTail(), 5000);
  } else if (tailTimer) {
    window.clearInterval(tailTimer);
    tailTimer = undefined;
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
      tailSince = Math.max(...lines.map((l) => Date.parse(l.time.replace(' ', 'T')))).valueOf();
    }
  } catch {
    /* 轮询失败静默,下一轮重试 */
  }
}

// ---- 详情 ----
const detailDrawer = ref(false);
const detailVo = ref<AppLogDetailVO | null>(null);

async function openLogDetail(row: LogLine): Promise<void> {
  detailVo.value = await fetchLogDetail(row.fileId, row.lineNo, 50);
  detailDrawer.value = true;
}

// ---- 操作日志 ----
const operQuery = reactive({ username: '' });
const operLoading = ref(false);
const operPage = reactive({ rows: [] as Record<string, unknown>[], total: 0, size: 20, current: 1 });

async function loadOperPage(page: number): Promise<void> {
  operLoading.value = true;
  try {
    const params: Record<string, unknown> = { page, size: operPage.size };
    if (operQuery.username) params.username = operQuery.username;
    const result = await fetchOperLogPage(params);
    operPage.rows = result.rows;
    operPage.total = result.total;
    operPage.current = page;
  } finally {
    operLoading.value = false;
  }
}

// ---- 登录日志 ----
const loginQuery = reactive({ username: '', result: '' });
const loginLoading = ref(false);
const loginPage = reactive({ rows: [] as Record<string, unknown>[], total: 0, size: 20, current: 1 });

async function loadLoginPage(page: number): Promise<void> {
  loginLoading.value = true;
  try {
    const params: Record<string, unknown> = { page, size: loginPage.size };
    if (loginQuery.username) params.username = loginQuery.username;
    if (loginQuery.result) params.success = loginQuery.result;
    const result = await fetchLoginLogPage(params);
    loginPage.rows = result.rows;
    loginPage.total = result.total;
    loginPage.current = page;
  } finally {
    loginLoading.value = false;
  }
}

// ---- 辅助 ----
function levelType(level: string): 'danger' | 'warning' | 'info' {
  return level === 'ERROR' ? 'danger' : level === 'WARN' ? 'warning' : 'info';
}

function formatTime(value?: unknown): string {
  if (!value) return '—';
  return String(value).replace('T', ' ').slice(0, 19);
}

function firstLine(message: string): string {
  return message.split('\n')[0] ?? '';
}

function shortLogger(logger: string): string {
  return logger.length > 28 ? `…${logger.slice(-27)}` : logger;
}

function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)}MB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(0)}KB`;
  return `${bytes}B`;
}

onMounted(async () => {
  await loadFiles();
  await loadAppPage(1);
  await loadOperPage(1);
  await loadLoginPage(1);
});

onBeforeUnmount(() => {
  if (tailTimer) window.clearInterval(tailTimer);
});
</script>

<style scoped>
.page {
  padding: 16px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.hint {
  font-size: 12px;
  color: var(--text-secondary);
  margin: 0 0 8px;
}

.hint.warning {
  color: var(--warning-color, #e6a23c);
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.mono {
  font-family: var(--font-mono, monospace);
  font-size: 12px;
}

.msg-text {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
}

.detail-time {
  margin-left: 8px;
  font-size: 12px;
  color: var(--text-secondary);
}

.msg-detail {
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 220px;
  overflow: auto;
  background: color-mix(in srgb, var(--text-secondary, #909399) 8%, transparent);
  padding: 10px;
  border-radius: 6px;
  margin-top: 10px;
}

.ctx {
  max-height: 260px;
  overflow: auto;
  border: 1px solid var(--border-color);
  border-radius: 6px;
}

.ctx-line {
  display: flex;
  gap: 10px;
  padding: 2px 10px;
  font-size: 12px;
  line-height: 20px;
}

.ctx-line.err {
  color: var(--danger-color, #f56c6c);
}

.ctx-no {
  color: var(--text-secondary);
  width: 70px;
  text-align: right;
  flex-shrink: 0;
  font-family: var(--font-mono, monospace);
}

.ctx-msg {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-family: var(--font-mono, monospace);
}

h5 {
  margin: 16px 0 8px;
  font-size: 13px;
}
</style>
