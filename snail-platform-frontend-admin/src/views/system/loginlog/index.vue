<script setup lang="ts">
/** 组件名与路由 meta.viewName 对应,多页签 KeepAlive include 依此缓存。 */
defineOptions({ name: 'SystemLoginlog' });
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import AppIcon from '@/components/AppIcon.vue';
import {
  cleanLoginLogs,
  exportLoginLogs,
  pageLoginLogs,
  type LoginLogQuery,
  type LoginLogVO,
} from '@/api/log';
import { pageTenants, type TenantVO } from '@/api/tenant';
import { usePageList } from '@/composables/usePageList';
import { useAuthStore } from '@/stores/auth';
import { dateTime } from '@/utils/format';

const auth = useAuthStore();

/** 平台侧(持跨租户权限)才加载全量租户做 ID→名称映射并提供租户筛选;租户侧只看本租户日志。 */
const isPlatform = computed(() => auth.has('platform:loginlog:all'));

const query = reactive<LoginLogQuery>({
  username: '',
  ip: '',
  status: null,
  scene: '',
});

/** 结果筛选的 UI 态:ElRadioButton 的 value 不收 null,空串代表"全部",提交前折成 null。 */
const statusFilter = ref<number | ''>('');

function statusOf(): number | null {
  return statusFilter.value === '' ? null : statusFilter.value;
}

/** 登录端下拉:与后端 LoginScene 白名单一一对应(app/miniapp 同属移动端产品位),tone 决定徽标配色。 */
const SCENE_OPTIONS = [
  { value: 'admin', label: '管理系统', tone: 'primary' },
  { value: 'workflow', label: '流程系统', tone: 'info' },
  { value: 'app', label: '移动端', tone: 'success' },
  { value: 'miniapp', label: '移动端小程序', tone: 'warning' },
  { value: 'dashboard', label: '数据大屏', tone: 'chart4' },
  { value: 'portal', label: '企业门户', tone: 'accent' },
];

/** 登录端中文标签与徽标 tone:未登记的新端原样回显、走默认配色,防后端扩枚举时列空白。 */
function sceneOf(scene: string): { label: string; tone: string } {
  return SCENE_OPTIONS.find((opt) => opt.value === scene) ?? { label: scene, tone: '' };
}

/* ----------------------------- 租户 ID→名称映射 ----------------------------- */

const tenantMap = ref(new Map<number, TenantVO>());
const tenantOptions = computed(() => [...tenantMap.value.values()]);

/** 平台侧把租户整表拉齐(200/页循环翻页),列表与筛选才有名称可显;失败静默,列降级显示 #ID。 */
async function loadTenants(): Promise<void> {
  if (!isPlatform.value) return;
  const map = new Map<number, TenantVO>();
  let pageNo = 1;
  try {
    for (;;) {
      const page = await pageTenants({ pageNum: pageNo, pageSize: 200 });
      page.rows.forEach((item) => map.set(item.tenantId, item));
      if (page.rows.length === 0 || map.size >= page.total) break;
      pageNo += 1;
    }
  } catch {
    // 租户检索失败不拦页面:名称缺失时按 #ID 兜底
  } finally {
    tenantMap.value = map;
  }
}

/** 平台侧缺失映射时按 #ID 兜底(回显自家租户名反而误导);租户侧行恒属本租户,直接用登录态租户名。 */
function tenantLabel(tenantId: number): string {
  const hit = tenantMap.value.get(tenantId);
  if (hit) return hit.name;
  return isPlatform.value ? `#${tenantId}` : (auth.user?.tenantName ?? `#${tenantId}`);
}

function tenantCode(tenantId: number): string | null {
  return tenantMap.value.get(tenantId)?.tenantCode ?? null;
}

/** 租户筛选用下拉承接,clearable 清空后可能是 undefined,统一折成 null。 */
const tenantId = ref<number | null>(null);

function tenantIdOf(): number | null {
  return typeof tenantId.value === 'number' ? tenantId.value : null;
}

/** 时间区间只作 UI 状态,提交前拆成 beginTime/endTime 两个 ISO 局部日期时间参数。 */
const range = ref<string[]>([]);

const { rows, total, loading, pageNum, pageSize, refresh, search } = usePageList<LoginLogVO>(
  (page) =>
    pageLoginLogs({
      ...query,
      status: statusOf(),
      tenantId: tenantIdOf(),
      beginTime: range.value[0],
      endTime: range.value[1],
      ...page,
    }),
  20,
);

onMounted(() => {
  void loadTenants();
  void refresh();
});

function reset(): void {
  query.username = '';
  query.ip = '';
  statusFilter.value = '';
  query.scene = '';
  tenantId.value = null;
  range.value = [];
  void search();
}

const exporting = ref(false);

/** 条件在列表回调与这里各拼一遍,两处同形:加筛选项必须同时改,否则下载件与页面所见不是同一集合。 */
async function exportLogs(): Promise<void> {
  exporting.value = true;
  try {
    await exportLoginLogs({
      ...query,
      status: statusOf(),
      tenantId: tenantIdOf(),
      beginTime: range.value[0],
      endTime: range.value[1],
    });
    ElMessage.success('已开始下载');
  } finally {
    exporting.value = false;
  }
}

/* --------------------------------- 历史清除 --------------------------------- */

/** 清除档位:command 值与后端 LogCleanRequest.before 的三档白名单一致。 */
const CLEAN_OPTIONS: Record<string, string> = { '1y': '一年前', '6m': '半年前', '1m': '一个月前' };
const cleaning = ref(false);

/** 二次确认后再发清除请求;tenantId 与查询同口径,仅平台侧生效、留空跨租户全量。 */
async function cleanLogs(command: string | number | object): Promise<void> {
  const before = String(command) as '1m' | '6m' | '1y';
  const label = CLEAN_OPTIONS[before] ?? before;
  await ElMessageBox.confirm(
    `将永久删除 ${label}产生的全部登录日志,删除后不可恢复,确认执行?`,
    '清除登录日志',
    { type: 'warning', confirmButtonText: '确认清除', cancelButtonText: '取消' },
  );
  cleaning.value = true;
  try {
    const count = await cleanLoginLogs({ before, tenantId: tenantIdOf() });
    ElMessage.success(`已清除 ${count} 条历史日志`);
    await search();
  } finally {
    cleaning.value = false;
  }
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="eyebrow">
          LOGIN LOG
        </div>
        <h1>登录日志</h1>
        <p>各端登录成败与来源环境留痕,支持按租户检索与历史清理</p>
      </div>
      <div class="head-actions">
        <ElDropdown
          v-permission="'system:loginlog:clean'"
          @command="cleanLogs"
        >
          <ElButton
            plain
            type="danger"
            :loading="cleaning"
          >
            <AppIcon
              name="trash"
              :size="15"
            />
            清除历史
          </ElButton>
          <template #dropdown>
            <ElDropdownMenu>
              <ElDropdownItem command="1y">
                清除一年前
              </ElDropdownItem>
              <ElDropdownItem command="6m">
                清除半年前
              </ElDropdownItem>
              <ElDropdownItem command="1m">
                清除一个月前
              </ElDropdownItem>
            </ElDropdownMenu>
          </template>
        </ElDropdown>
        <ElButton
          v-permission="'system:loginlog:export'"
          plain
          :loading="exporting"
          @click="exportLogs"
        >
          <AppIcon
            name="download"
            :size="15"
          />
          导出 xlsx
        </ElButton>
      </div>
    </div>

    <ElCard
      shadow="never"
      class="filter-card"
    >
      <ElForm
        inline
        class="filter-form"
        :model="query"
        @submit.prevent="search"
      >
        <ElFormItem label="登录名">
          <ElInput
            v-model="query.username"
            clearable
            placeholder="模糊匹配"
            style="width: 190px"
            @keyup.enter="search"
          >
            <template #prefix>
              <AppIcon
                name="search"
                :size="14"
              />
            </template>
          </ElInput>
        </ElFormItem>
        <ElFormItem label="IP">
          <ElInput
            v-model="query.ip"
            clearable
            placeholder="模糊匹配"
            style="width: 170px"
            @keyup.enter="search"
          />
        </ElFormItem>
        <ElFormItem label="结果">
          <ElRadioGroup
            v-model="statusFilter"
            @change="search"
          >
            <ElRadioButton value="">
              全部
            </ElRadioButton>
            <ElRadioButton :value="1">
              成功
            </ElRadioButton>
            <ElRadioButton :value="0">
              失败
            </ElRadioButton>
          </ElRadioGroup>
        </ElFormItem>
        <ElFormItem
          v-if="isPlatform"
          label="租户"
        >
          <ElSelect
            v-model="tenantId"
            clearable
            filterable
            placeholder="全部租户"
            style="width: 200px"
          >
            <ElOption
              v-for="item in tenantOptions"
              :key="item.tenantId"
              :label="item.name"
              :value="item.tenantId"
            >
              <div class="tenant-option">
                <span>{{ item.name }}</span>
                <small>{{ item.tenantCode }}</small>
              </div>
            </ElOption>
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="登录端">
          <ElSelect
            v-model="query.scene"
            clearable
            filterable
            placeholder="不限"
            style="width: 150px"
          >
            <ElOption
              v-for="opt in SCENE_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="登录时间">
          <ElDatePicker
            v-model="range"
            type="datetimerange"
            value-format="YYYY-MM-DDTHH:mm:ss"
            start-placeholder="起始"
            end-placeholder="截止"
            range-separator="至"
          />
        </ElFormItem>
        <ElFormItem>
          <ElButton @click="reset">
            重置
          </ElButton>
          <ElButton
            type="primary"
            @click="search"
          >
            查询
          </ElButton>
        </ElFormItem>
      </ElForm>
    </ElCard>

    <ElCard
      shadow="never"
      class="table-card"
    >
      <template #header>
        <div class="table-card-head">
          <h3>登录记录 <span>共 {{ total }} 条</span></h3>
          <ElButton
            class="icon-btn"
            :loading="loading"
            @click="refresh"
          >
            <AppIcon
              name="refresh"
              :size="15"
            />
          </ElButton>
        </div>
      </template>

      <ElTable
        v-loading="loading"
        :data="rows"
        row-key="id"
      >
        <ElTableColumn
          label="登录时间"
          min-width="155"
        >
          <template #default="{ row }">
            <span class="mono">{{ dateTime(row.loginTime) }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="用户"
          min-width="130"
        >
          <template #default="{ row }">
            <div class="user-cell">
              <span class="avatar">{{ row.username.slice(0, 1).toUpperCase() }}</span>
              <b>{{ row.username }}</b>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="租户"
          min-width="150"
        >
          <template #default="{ row }">
            <div class="tenant-cell">
              <b>{{ tenantLabel(row.tenantId) }}</b>
              <small v-if="tenantCode(row.tenantId)">{{ tenantCode(row.tenantId) }}</small>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="登录端"
          width="120"
        >
          <template #default="{ row }">
            <span
              class="scene-chip"
              :class="sceneOf(row.scene).tone"
            >{{ sceneOf(row.scene).label }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="结果"
          width="84"
          align="center"
        >
          <template #default="{ row }">
            <span
              class="status-pill"
              :class="row.status === 1 ? 'success' : 'danger'"
            >
              {{ row.status === 1 ? '成功' : '失败' }}
            </span>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="IP"
          min-width="125"
        >
          <template #default="{ row }">
            <span class="mono">{{ row.ip ?? '—' }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="环境"
          min-width="170"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ [row.browser, row.os].filter(Boolean).join(' · ') || '—' }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="说明"
          min-width="170"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <span :class="{ 'fail-msg': row.status !== 1 }">{{ row.msg ?? '—' }}</span>
          </template>
        </ElTableColumn>
      </ElTable>

      <div class="table-footer">
        <span>共 {{ total }} 条</span>
        <ElPagination
          v-model:current-page="pageNum"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[20, 50, 100, 200]"
          layout="sizes, prev, pager, next"
          @change="refresh"
        />
      </div>
    </ElCard>
  </div>
</template>

<style scoped>
/* 登录端彩色徽标:按端分色弱底渲染,不抢结果列的视觉权重。 */
.scene-chip {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 99px;
  color: var(--app-muted);
  background: var(--app-soft);
  font-size: 10px;
  font-weight: 600;
  white-space: nowrap;
}

.scene-chip.primary {
  color: var(--app-primary);
  background: rgba(var(--app-primary-rgb), 0.1);
}

.scene-chip.info {
  color: var(--app-info);
  background: rgba(var(--app-info-rgb), 0.1);
}

.scene-chip.success {
  color: var(--app-success);
  background: rgba(var(--app-success-rgb), 0.1);
}

.scene-chip.warning {
  color: var(--app-warning);
  background: rgba(var(--app-warning-rgb), 0.12);
}

.scene-chip.accent {
  color: var(--app-accent);
  background: rgba(var(--app-accent-rgb), 0.12);
}

.scene-chip.chart4 {
  color: var(--app-chart-4);
  background: rgba(138, 167, 196, 0.14);
}

/* 用户格:首字母小方块 + 登录名,替代裸文本列。 */
.avatar {
  display: inline-grid;
  place-items: center;
  width: 26px;
  height: 26px;
  flex: none;
  border-radius: 8px;
  color: var(--app-primary);
  background: rgba(var(--app-primary-rgb), 0.12);
  font-size: 11px;
  font-weight: 700;
}

.user-cell b {
  color: var(--app-ink);
  font-size: 11.5px;
}

/* 租户格:名称为主、编码为辅的等宽小字;无编码时只留名称。 */
.tenant-cell b {
  display: block;
  color: var(--app-ink);
  font-size: 11.5px;
}

.tenant-cell small {
  display: block;
  margin-top: 1px;
  color: var(--app-muted);
  font-family: var(--app-font-mono);
  font-size: 9px;
}

/* 时间与 IP 走等宽字形,逐位对齐更易扫读。 */
.mono {
  font-family: var(--app-font-mono);
  font-size: 11px;
}

.fail-msg {
  color: var(--app-danger);
}

/* 租户下拉选项:名称 + 编码双行信息。 */
.tenant-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.tenant-option small {
  color: var(--app-muted);
  font-family: var(--app-font-mono);
  font-size: 10px;
}
</style>
