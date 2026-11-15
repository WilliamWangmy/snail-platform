<template>
  <div class="page">
    <el-tabs v-model="activeTab">
      <!-- 系统体检:12 项检查,10s 限频(§6.9) -->
      <el-tab-pane label="系统体检" name="checkup">
        <div class="toolbar">
          <el-button type="primary" size="small" :loading="running" v-permission="'platform:monitor:diag'" @click="runNow">立即体检</el-button>
          <span class="hint">12 项检查并行执行,单项与整体超时保护;限频 10 秒</span>
        </div>
        <el-descriptions v-if="report" :column="3" size="small" border class="mb">
          <el-descriptions-item label="总体结论">
            <el-tag :type="overallType(report.overall)" size="small">{{ overallText(report.overall) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="耗时">{{ report.costMs }} ms</el-descriptions-item>
          <el-descriptions-item label="触发时间">{{ formatTime(report.triggerTime) }}({{ report.triggerBy }})</el-descriptions-item>
        </el-descriptions>
        <el-table :data="report?.items ?? []" v-loading="running" size="small" border>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="itemType(row.status)" size="small" effect="plain">{{ itemText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="name" label="检查项" width="180" />
          <el-table-column label="耗时" width="90">
            <template #default="{ row }">{{ row.latencyMs === null ? '—' : `${row.latencyMs} ms` }}</template>
          </el-table-column>
          <el-table-column label="说明" min-width="220">
            <template #default="{ row }">{{ row.detail || '—' }}</template>
          </el-table-column>
          <el-table-column label="建议" min-width="220">
            <template #default="{ row }">{{ row.suggestion || '—' }}</template>
          </el-table-column>
        </el-table>

        <h5>历史体检报告</h5>
        <el-table :data="reportPage.rows" size="small" border>
          <el-table-column prop="reportId" label="ID" width="80" />
          <el-table-column label="结论" width="90">
            <template #default="{ row }">
              <el-tag :type="overallType(row.overall)" size="small" effect="plain">{{ overallText(row.overall) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="triggerBy" label="触发人" width="120" />
          <el-table-column label="触发时间" width="170">
            <template #default="{ row }">{{ formatTime(row.triggerTime) }}</template>
          </el-table-column>
          <el-table-column prop="costMs" label="耗时(ms)" width="100" />
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button size="small" link type="primary" @click="openHistory(row.reportId)">查看</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          class="pager"
          layout="total, prev, pager, next"
          :total="reportPage.total"
          :page-size="reportPage.size"
          :current-page="reportPage.current"
          @current-change="(p: number) => loadReports(p)"
        />
      </el-tab-pane>

      <!-- 线程快照 -->
      <el-tab-pane label="线程快照" name="threads">
        <div class="toolbar">
          <el-button size="small" :loading="threadsLoading" @click="loadThreads">刷新快照</el-button>
          <el-button size="small" @click="downloadThreads">下载完整 dump</el-button>
          <el-tag v-if="threads && threads.deadlocked.length" type="danger" size="small">检测到死锁:{{ threads.deadlocked.join(',') }}</el-tag>
          <el-tag v-else-if="threads" type="success" size="small" effect="plain">无死锁</el-tag>
        </div>
        <el-table v-if="threads" :data="pagedThreads" v-loading="threadsLoading" size="small" border>
          <el-table-column prop="name" label="线程名" min-width="200" />
          <el-table-column prop="state" label="状态" width="120">
            <template #default="{ row }">
              <el-tag :type="threadType(row.state)" size="small" effect="plain">{{ row.state }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="守护" width="70">
            <template #default="{ row }">{{ row.daemon ? '是' : '否' }}</template>
          </el-table-column>
          <el-table-column label="栈顶" min-width="360">
            <template #default="{ row }">
              <div v-for="(frame, i) in row.stackTop" :key="i" class="stack-frame">{{ frame }}</div>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-if="threads"
          class="pager"
          layout="total, prev, pager, next"
          :total="threads.threads.length"
          :page-size="threadPageSize"
          :current-page="threadPage"
          @current-change="(p: number) => (threadPage = p)"
        />
      </el-tab-pane>

      <!-- 环境快照:敏感项已脱敏(DD-26/EnvSnapshotService) -->
      <el-tab-pane label="环境快照" name="env">
        <div class="toolbar">
          <el-button size="small" :loading="envLoading" @click="loadEnv">刷新</el-button>
          <span class="hint">含 secret/password/token/key/credential 的项已脱敏展示</span>
        </div>
        <template v-if="env">
          <h5>系统属性</h5>
          <el-table :data="envRows.system" size="small" border>
            <el-table-column prop="k" label="键" width="320" />
            <el-table-column prop="v" label="值" min-width="400">
              <template #default="{ row }">
                <span class="mono break">{{ row.v }}</span>
              </template>
            </el-table-column>
          </el-table>
          <h5>环境变量</h5>
          <el-table :data="envRows.env" size="small" border>
            <el-table-column prop="k" label="键" width="320" />
            <el-table-column prop="v" label="值" min-width="400">
              <template #default="{ row }">
                <span class="mono break">{{ row.v }}</span>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </el-tab-pane>

      <!-- Arthas 控制台:iframe + agent 表(§6.9) -->
      <el-tab-pane label="Arthas 控制台" name="arthas">
        <ArthasConsole />
      </el-tab-pane>
    </el-tabs>

    <!-- 历史报告详情抽屉 -->
    <el-drawer v-model="historyDrawer" :title="`体检报告 #${historyReport?.reportId ?? ''}`" size="640px">
      <template v-if="historyReport">
        <el-descriptions :column="2" size="small" border class="mb">
          <el-descriptions-item label="总体结论">{{ overallText(historyReport.overall) }}</el-descriptions-item>
          <el-descriptions-item label="耗时">{{ historyReport.costMs }} ms</el-descriptions-item>
          <el-descriptions-item label="触发">{{ historyReport.triggerBy }}</el-descriptions-item>
          <el-descriptions-item label="时间">{{ formatTime(historyReport.triggerTime) }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="historyReport.items ?? []" size="small" border>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag :type="itemType(row.status)" size="small" effect="plain">{{ itemText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="name" label="检查项" width="160" />
          <el-table-column label="说明" min-width="180">
            <template #default="{ row }">{{ row.detail || '—' }}</template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import {
  downloadThreads,
  fetchEnv,
  fetchReportDetail,
  fetchReportPage,
  fetchThreads,
  runDiag,
  type DiagItem,
  type DiagReport,
  type ThreadSnapshot,
} from '@/api/monitor/diag';
import ArthasConsole from './components/ArthasConsole.vue';

const activeTab = ref('checkup');

// ---- 系统体检 ----
const running = ref(false);
const report = ref<DiagReport | null>(null);
const reportPage = ref({ rows: [] as DiagReport[], total: 0, size: 20, current: 1 });

async function runNow(): Promise<void> {
  running.value = true;
  try {
    report.value = await runDiag();
    ElMessage.success(`体检完成:${overallText(report.value.overall)}`);
    await loadReports(1);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '体检失败');
  } finally {
    running.value = false;
  }
}

async function loadReports(page: number): Promise<void> {
  const result = await fetchReportPage({ page, size: reportPage.value.size });
  reportPage.value = { rows: result.rows, total: result.total, size: reportPage.value.size, current: page };
}

const historyDrawer = ref(false);
const historyReport = ref<DiagReport | null>(null);

async function openHistory(reportId: number): Promise<void> {
  historyReport.value = await fetchReportDetail(reportId);
  historyDrawer.value = true;
}

function itemType(status: string): 'success' | 'warning' | 'danger' | 'info' {
  return status === 'pass' ? 'success' : status === 'warn' ? 'warning' : status === 'fail' ? 'danger' : 'info';
}

function itemText(status: string): string {
  return status === 'pass' ? '通过' : status === 'warn' ? '警告' : status === 'fail' ? '失败' : '跳过';
}

function overallType(status: string): 'success' | 'warning' | 'danger' {
  return status === 'pass' ? 'success' : status === 'warn' ? 'warning' : 'danger';
}

function overallText(status: string): string {
  return status === 'pass' ? '通过' : status === 'warn' ? '警告' : '失败';
}

// ---- 线程快照 ----
const threads = ref<ThreadSnapshot | null>(null);
const threadsLoading = ref(false);
const threadPage = ref(1);
const threadPageSize = 20;
const pagedThreads = computed(() => threads.value?.threads.slice((threadPage.value - 1) * threadPageSize, threadPage.value * threadPageSize) ?? []);

async function loadThreads(): Promise<void> {
  threadsLoading.value = true;
  threadPage.value = 1;
  try {
    threads.value = await fetchThreads();
  } finally {
    threadsLoading.value = false;
  }
}

function threadType(state: string): 'danger' | 'warning' | 'info' {
  if (state === 'BLOCKED' || state === 'WAITING') return 'warning';
  if (state === 'RUNNABLE') return 'info';
  return 'danger';
}

// ---- 环境快照 ----
const env = ref<Record<string, Record<string, string>> | null>(null);
const envLoading = ref(false);
const envRows = computed(() => {
  const system = Object.entries(env.value?.system ?? {}).map(([k, v]) => ({ k, v }));
  const processEnv = Object.entries(env.value?.env ?? {}).map(([k, v]) => ({ k, v }));
  return { system, env: processEnv };
});

async function loadEnv(): Promise<void> {
  envLoading.value = true;
  try {
    env.value = await fetchEnv();
  } finally {
    envLoading.value = false;
  }
}

function formatTime(value?: string | null): string {
  if (!value) return '—';
  return value.replace('T', ' ').slice(0, 19);
}

onMounted(() => {
  void loadReports(1);
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
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.mb {
  margin-bottom: 12px;
}

.mono {
  font-family: var(--font-mono, monospace);
  font-size: 12px;
}

.break {
  word-break: break-all;
}

.stack-frame {
  font-family: var(--font-mono, monospace);
  font-size: 11px;
  line-height: 18px;
  color: var(--text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

h5 {
  margin: 16px 0 8px;
  font-size: 13px;
}
</style>
