<template>
  <div>
    <el-tabs v-model="activeTab">
      <!-- 一键体检:12 项检查,10s 限频(§6.9) -->
      <el-tab-pane name="check">
        <template #label>
          一键体检
        </template>
        <div
          class="card"
          style="margin-bottom: 14px"
        >
          <div style="display: flex; align-items: center; gap: 14px; flex-wrap: wrap">
            <el-button
              type="primary"
              size="default"
              v-permission="'platform:monitor:diag'"
              :loading="running"
              @click="runNow"
            >
              <svg
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
                style="width: 14px; height: 14px; margin-right: 4px"
              ><path d="M22 12h-4l-3 9L9 3l-3 9H2" /></svg>
              一键体检
            </el-button>
            <span
              class="muted"
              style="font-size: 12px"
            >12 项检查 · 单项超时 2s · 总超时 10s · 同用户限频 10s</span>
            <span style="flex: 1" />
            <span
              v-if="report"
              class="chip"
              :class="overallChip(report.overall)"
            >{{ overallText(report.overall) }}</span>
            <el-button
              size="small"
              @click="threadsDrawer = true; loadThreads()"
            >线程快照</el-button>
            <el-button
              size="small"
              @click="envDrawer = true; loadEnv()"
            >环境快照</el-button>
          </div>
          <div
            v-if="running"
            class="progress"
          >
            <i style="width: 60%" />
          </div>
        </div>

        <div
          v-if="report?.items?.length"
          class="diag-grid"
          style="margin-bottom: 14px"
        >
          <div
            v-for="item in report.items"
            :key="item.key"
            class="diag-item"
          >
            <div
              class="diag-ico"
              :class="item.status"
            >{{ itemIcon(item.status) }}</div>
            <div style="min-width: 0">
              <div class="diag-name">
                {{ item.name }}
                <span
                  class="chip"
                  :class="itemChip(item.status)"
                  style="font-size: 9.5px"
                >{{ itemText(item.status) }}</span>
                <span
                  v-if="item.latencyMs !== null"
                  class="muted"
                  style="font-size: 10px; font-weight: 400"
                >{{ item.latencyMs }}ms</span>
              </div>
              <div
                v-if="item.detail"
                class="diag-detail"
              >{{ item.detail }}</div>
              <div
                v-if="item.suggestion"
                class="diag-sug"
              >建议：{{ item.suggestion }}</div>
            </div>
          </div>
        </div>
        <div
          v-else
          class="card"
          style="margin-bottom: 14px"
        >
          <div class="empty">点击「一键体检」运行 12 项检查；结果落库保留 30 天</div>
        </div>

        <div class="card">
          <div class="card-title">
            诊断报告历史
            <span class="sub">保留 30 天</span>
          </div>
          <el-table
            :data="reportPage.rows"
            size="default"
          >
            <el-table-column
              label="报告时间"
              width="170"
            >
              <template #default="{ row }"><span class="mono">{{ formatTime(row.triggerTime) }}</span></template>
            </el-table-column>
            <el-table-column
              prop="triggerBy"
              label="触发人"
              width="110"
            />
            <el-table-column
              label="耗时"
              width="90"
            >
              <template #default="{ row }">{{ row.costMs }}ms</template>
            </el-table-column>
            <el-table-column
              label="结论"
              width="90"
            >
              <template #default="{ row }">
                <span
                  class="chip"
                  :class="overallChip(row.overall)"
                >{{ overallText(row.overall) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="异常项">
              <template #default="{ row }">
                <span class="muted">{{ badItems(row.items) }}</span>
              </template>
            </el-table-column>
            <el-table-column
              label="操作"
              align="right"
              width="80"
            >
              <template #default="{ row }">
                <el-button
                  size="small"
                  link
                  type="primary"
                  @click="openHistory(row.reportId)"
                >查看</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            layout="total, prev, pager, next"
            :total="reportPage.total"
            :page-size="reportPage.size"
            :current-page="reportPage.current"
            @current-change="(p: number) => loadReports(p)"
          />
        </div>
      </el-tab-pane>

      <!-- Arthas 在线诊断 -->
      <el-tab-pane
        label="Arthas 在线诊断"
        name="arthas"
      >
        <ArthasConsole />
      </el-tab-pane>
    </el-tabs>

    <!-- 线程快照抽屉 -->
    <el-drawer
      v-model="threadsDrawer"
      title="线程快照"
      size="720px"
    >
      <div class="filter-bar">
        <span
          v-if="threads && threads.deadlocked.length"
          class="chip chip-danger"
        >检测到死锁：{{ threads.deadlocked.join(',') }}</span>
        <span
          v-else-if="threads"
          class="chip chip-success"
        >无死锁</span>
        <span style="flex: 1" />
        <el-button
          size="small"
          :loading="threadsLoading"
          @click="loadThreads"
        >刷新快照</el-button>
        <el-button
          size="small"
          @click="downloadThreads"
        >下载完整 dump</el-button>
      </div>
      <el-table
        :data="pagedThreads"
        v-loading="threadsLoading"
        size="small"
      >
        <el-table-column
          prop="name"
          label="线程名"
          min-width="180"
        />
        <el-table-column
          label="状态"
          width="130"
        >
          <template #default="{ row }">
            <span
              class="chip"
              :class="threadChip(row.state)"
            >{{ row.state }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="守护"
          width="60"
        >
          <template #default="{ row }">{{ row.daemon ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column label="栈顶">
          <template #default="{ row }">
            <div
              v-for="(frame, i) in row.stackTop"
              :key="i"
              class="mono muted"
              style="font-size: 11px; line-height: 18px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis"
            >{{ frame }}</div>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        layout="total, prev, pager, next"
        :total="threads?.threads.length ?? 0"
        :page-size="threadPageSize"
        :current-page="threadPage"
        @current-change="(p: number) => (threadPage = p)"
      />
    </el-drawer>

    <!-- 环境快照抽屉:敏感项已脱敏(EnvSnapshotService) -->
    <el-drawer
      v-model="envDrawer"
      title="环境快照"
      size="680px"
    >
      <p
        class="muted"
        style="font-size: 11.5px; margin-bottom: 12px"
      >
        含 secret/password/token/key/credential 的项已脱敏展示（******）。
      </p>
      <template v-if="env">
        <div class="card-title">系统属性</div>
        <el-table
          :data="envRows.system"
          size="small"
        >
          <el-table-column
            prop="k"
            label="键"
            width="300"
          />
          <el-table-column label="值">
            <template #default="{ row }"><span class="mono" style="font-size: 11.5px; word-break: break-all">{{ row.v }}</span></template>
          </el-table-column>
        </el-table>
        <div class="card-title" style="margin-top: 16px">环境变量</div>
        <el-table
          :data="envRows.env"
          size="small"
        >
          <el-table-column
            prop="k"
            label="键"
            width="300"
          />
          <el-table-column label="值">
            <template #default="{ row }"><span class="mono" style="font-size: 11.5px; word-break: break-all">{{ row.v }}</span></template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>

    <!-- 历史报告详情抽屉 -->
    <el-drawer
      v-model="historyDrawer"
      size="620px"
      :title="historyReport ? `体检报告 #${historyReport.reportId}` : '体检报告'"
    >
      <template v-if="historyReport">
        <div class="kv" style="margin-bottom: 14px">
          <div>总体结论</div><div><span class="chip" :class="overallChip(historyReport.overall)">{{ overallText(historyReport.overall) }}</span></div>
          <div>耗时</div><div>{{ historyReport.costMs }} ms</div>
          <div>触发</div><div>{{ historyReport.triggerBy }} · {{ formatTime(historyReport.triggerTime) }}</div>
        </div>
        <el-table
          :data="historyReport.items ?? []"
          size="small"
        >
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <span class="chip" :class="itemChip(row.status)">{{ itemText(row.status) }}</span>
            </template>
          </el-table-column>
          <el-table-column
            prop="name"
            label="检查项"
            width="160"
          />
          <el-table-column label="说明">
            <template #default="{ row }">{{ row.detail || '—' }}</template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, onUnmounted, ref } from 'vue';
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

const activeTab = ref('check');

// ---------- 系统体检 ----------
const running = ref(false);
const report = ref<DiagReport | null>(null);
const reportPage = ref({ rows: [] as DiagReport[], total: 0, size: 20, current: 1 });

async function runNow(): Promise<void> {
  running.value = true;
  try {
    report.value = await runDiag();
    ElMessage.success(`体检完成：${overallText(report.value.overall)}`);
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

function itemIcon(status: string): string {
  return status === 'pass' ? '✓' : status === 'warn' ? '!' : status === 'fail' ? '✕' : '–';
}

function itemChip(status: string): string {
  return status === 'pass' ? 'chip-success' : status === 'warn' ? 'chip-warning' : status === 'fail' ? 'chip-danger' : 'chip-muted';
}

function itemText(status: string): string {
  return status === 'pass' ? '通过' : status === 'warn' ? '警告' : status === 'fail' ? '失败' : '跳过';
}

function overallChip(status: string): string {
  return status === 'pass' ? 'chip-success' : status === 'warn' ? 'chip-warning' : 'chip-danger';
}

function overallText(status: string): string {
  return status === 'pass' ? '通过' : status === 'warn' ? '警告' : '失败';
}

function badItems(items: DiagItem[] | null): string {
  if (!items) return '—';
  const bad = items.filter((i) => i.status === 'warn' || i.status === 'fail').map((i) => i.key);
  return bad.length ? bad.join(' / ') : '—';
}

// ---------- 线程快照 ----------
const threadsDrawer = ref(false);
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

function threadChip(state: string): string {
  if (state === 'RUNNABLE') return 'chip-info';
  if (state === 'BLOCKED') return 'chip-danger';
  return 'chip-warning';
}

// ---------- 环境快照 ----------
const envDrawer = ref(false);
const env = ref<Record<string, Record<string, string>> | null>(null);
const envRows = computed(() => {
  const system = Object.entries(env.value?.system ?? {}).map(([k, v]) => ({ k, v }));
  const processEnv = Object.entries(env.value?.env ?? {}).map(([k, v]) => ({ k, v }));
  return { system, env: processEnv };
});

async function loadEnv(): Promise<void> {
  env.value = await fetchEnv();
}

function formatTime(value?: string | null): string {
  if (!value) return '—';
  return value.replace('T', ' ').slice(0, 19);
}

function onRefresh(): void {
  void loadReports(reportPage.value.current);
}

let refreshListener: (() => void) | undefined;

onMounted(() => {
  void loadReports(1);
  refreshListener = onRefresh;
  window.addEventListener('monitor-refresh', refreshListener);
});

onUnmounted(() => {
  if (refreshListener) window.removeEventListener('monitor-refresh', refreshListener);
});
</script>
