<script setup lang="ts">
/** 组件名与路由 meta.viewName 对应,多页签 KeepAlive include 依此缓存。 */
defineOptions({ name: 'SystemOperlog' });
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import AppIcon from '@/components/AppIcon.vue';
import {
  cleanOperLogs,
  exportOperLogs,
  getOperLog,
  pageOperLogs,
  type OperLogDetailVO,
  type OperLogListVO,
  type OperLogQuery,
} from '@/api/log';
import { pageTenants, type TenantVO } from '@/api/tenant';
import { usePageList } from '@/composables/usePageList';
import { useAuthStore } from '@/stores/auth';
import { dateTime } from '@/utils/format';

const auth = useAuthStore();

/** 平台侧(持跨租户权限)才加载全量租户做 ID→名称映射并提供租户筛选;租户侧由后端钉死本租户。 */
const isPlatform = computed(() => auth.has('platform:operlog:all'));

/** 与后端 BusinessType 枚举同一份口径,tone 决定类型徽标配色。 */
const BUSINESS_TYPES = [
  { value: 1, label: '新增', tone: 'success' },
  { value: 2, label: '修改', tone: 'info' },
  { value: 3, label: '删除', tone: 'danger' },
  { value: 4, label: '导出', tone: 'warning' },
  { value: 5, label: '导入', tone: 'warning' },
  { value: 9, label: '其他', tone: '' },
];

function businessOf(value: number): { label: string; tone: string } {
  return BUSINESS_TYPES.find((item) => item.value === value) ?? { label: String(value), tone: '' };
}

/** 请求方法徽标配色:未知方法走默认灰。 */
function methodTone(method: string): string {
  const m = method.toUpperCase();
  if (m === 'GET') return 'info';
  if (m === 'POST') return 'success';
  if (m === 'PUT') return 'warning';
  if (m === 'DELETE') return 'danger';
  return '';
}

const query = reactive<OperLogQuery>({ module: '', operator: '', businessType: null, status: null });
/** datetimerange 是一个数组,提交前才拆成 beginTime/endTime 两个 ISO 参数。 */
const range = ref<string[]>([]);

/** 结果筛选的 UI 态:ElRadioButton 的 value 不收 null,空串代表"全部",提交前折成 null。 */
const statusFilter = ref<number | ''>('');

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

/** 条件汇总成一份:分页与导出共用,保证下载件与页面所见同集合。 */
function currentQuery(): OperLogQuery {
  return {
    ...query,
    status: statusFilter.value === '' ? null : statusFilter.value,
    tenantId: tenantIdOf(),
    beginTime: range.value[0],
    endTime: range.value[1],
  };
}

const { rows, total, loading, pageNum, pageSize, refresh, search } = usePageList<OperLogListVO>(
  (page) => pageOperLogs({ ...currentQuery(), ...page }),
  20,
);

onMounted(() => {
  void loadTenants();
  void refresh();
});

function reset(): void {
  query.module = '';
  query.operator = '';
  query.businessType = null;
  statusFilter.value = '';
  range.value = [];
  tenantId.value = null;
  void search();
}

const exporting = ref(false);

/** 超 5 万行时后端整体拒绝,download 会把返回的 JSON 信封当错误抛出,这里只负责转成"开始下载"提示。 */
async function exportLogs(): Promise<void> {
  exporting.value = true;
  try {
    await exportOperLogs(currentQuery());
    ElMessage.success('已开始下载');
  } finally {
    exporting.value = false;
  }
}

/* --------------------------------- 历史清除 --------------------------------- */

/** 清除档位:command 值与后端 LogCleanRequest.before 的三档白名单一致。 */
const CLEAN_OPTIONS: Record<string, string> = { '1y': '一年前', '6m': '半年前', '1m': '一个月前' };
const cleaning = ref(false);

/** 二次确认后再发清除请求:不可逆动作,确认文案必须点明"永久删除/不可恢复"。 */
async function cleanLogs(command: string | number | object): Promise<void> {
  const before = String(command) as '1m' | '6m' | '1y';
  const label = CLEAN_OPTIONS[before] ?? before;
  await ElMessageBox.confirm(
    `将永久删除 ${label}产生的全部操作日志,删除后不可恢复,确认执行?`,
    '清除操作日志',
    { type: 'warning', confirmButtonText: '确认清除', cancelButtonText: '取消' },
  );
  cleaning.value = true;
  try {
    const count = await cleanOperLogs({ before, tenantId: tenantIdOf() });
    ElMessage.success(`已清除 ${count} 条历史日志`);
    await search();
  } finally {
    cleaning.value = false;
  }
}

/* --------------------------------- 详情 ----------------------------------- */

const drawerVisible = ref(false);
const detail = ref<OperLogDetailVO | null>(null);
const detailLoading = ref(false);

/** 抽屉先开再取数:param/result 是 TEXT 全文,按行现取而不是随列表下发。 */
async function openDetail(row: OperLogListVO): Promise<void> {
  drawerVisible.value = true;
  detailLoading.value = true;
  try {
    detail.value = await getOperLog(row.id);
  } finally {
    detailLoading.value = false;
  }
}

/** 全文复制:剪贴板不可用(非安全上下文)时静默提示失败,不拦抽屉。 */
async function copyText(text: string | null): Promise<void> {
  if (!text) return;
  try {
    await navigator.clipboard.writeText(text);
    ElMessage.success('已复制到剪贴板');
  } catch {
    ElMessage.error('当前环境不支持复制');
  }
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="eyebrow">
          OPERATION LOG
        </div>
        <h1>操作日志</h1>
        <p>写操作全链路审计留痕,请求参数与返回结果可回查</p>
      </div>
      <div class="head-actions">
        <ElDropdown
          v-permission="'system:operlog:clean'"
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
          v-permission="'system:operlog:export'"
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
        <ElFormItem label="模块">
          <ElInput
            v-model="query.module"
            clearable
            placeholder="模糊匹配"
            style="width: 170px"
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
        <ElFormItem label="操作人">
          <ElInput
            v-model="query.operator"
            clearable
            placeholder="登录名,模糊匹配"
            style="width: 180px"
            @keyup.enter="search"
          />
        </ElFormItem>
        <ElFormItem label="类型">
          <ElSelect
            v-model="query.businessType"
            clearable
            placeholder="不限"
            style="width: 110px"
          >
            <ElOption
              v-for="item in BUSINESS_TYPES"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </ElSelect>
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
        <ElFormItem label="操作时间">
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
          <h3>操作记录 <span>共 {{ total }} 条</span></h3>
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
          label="操作时间"
          min-width="155"
        >
          <template #default="{ row }">
            <span class="mono">{{ dateTime(row.operTime) }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn
          prop="module"
          label="模块"
          min-width="130"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <span class="module-text">{{ row.module }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="类型"
          width="78"
          align="center"
        >
          <template #default="{ row }">
            <span
              class="type-pill"
              :class="businessOf(row.businessType).tone"
            >{{ businessOf(row.businessType).label }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="操作人"
          min-width="125"
        >
          <template #default="{ row }">
            <div class="user-cell">
              <span class="avatar">{{ row.operator.slice(0, 1).toUpperCase() }}</span>
              <b>{{ row.operator }}</b>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn
          v-if="isPlatform"
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
          label="请求"
          min-width="240"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <div class="request-cell">
              <span
                class="method-badge"
                :class="methodTone(row.requestMethod)"
              >{{ row.requestMethod }}</span>
              <span class="request-url">{{ row.url }}</span>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="IP"
          min-width="120"
        >
          <template #default="{ row }">
            <span class="mono">{{ row.ip ?? '—' }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="耗时"
          width="92"
          align="right"
        >
          <template #default="{ row }">
            <span
              class="cost"
              :class="{ warn: (row.costTime ?? 0) > 1000, slow: (row.costTime ?? 0) > 3000 }"
            >{{ row.costTime ?? 0 }} ms</span>
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
          label="操作"
          width="80"
          align="center"
          fixed="right"
        >
          <template #default="{ row }">
            <ElButton
              link
              type="primary"
              @click="openDetail(row as OperLogListVO)"
            >
              详情
            </ElButton>
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

    <ElDrawer
      v-model="drawerVisible"
      size="680px"
    >
      <template #header>
        <div class="drawer-head">
          <h3>{{ detail?.module ?? '操作日志详情' }}</h3>
          <template v-if="detail">
            <span
              class="type-pill"
              :class="businessOf(detail.businessType).tone"
            >{{ businessOf(detail.businessType).label }}</span>
            <span
              class="status-pill"
              :class="detail.status === 1 ? 'success' : 'danger'"
            >
              {{ detail.status === 1 ? '成功' : '失败' }}
            </span>
          </template>
        </div>
      </template>

      <ElSkeleton
        v-if="detailLoading"
        :rows="6"
      />
      <template v-else-if="detail">
        <ElDescriptions
          :column="2"
          border
          class="detail-desc"
        >
          <ElDescriptionsItem label="操作人">
            {{ detail.operator }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="租户">
            <div class="tenant-inline">
              {{ tenantLabel(detail.tenantId) }}
              <small v-if="tenantCode(detail.tenantId)">{{ tenantCode(detail.tenantId) }}</small>
            </div>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="IP">
            <span class="mono">{{ detail.ip ?? '—' }}</span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="耗时">
            <span
              class="cost"
              :class="{ warn: (detail.costTime ?? 0) > 1000, slow: (detail.costTime ?? 0) > 3000 }"
            >{{ detail.costTime ?? 0 }} ms</span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="请求">
            <div class="request-cell">
              <span
                class="method-badge"
                :class="methodTone(detail.requestMethod)"
              >{{ detail.requestMethod }}</span>
              <span class="request-url">{{ detail.url }}</span>
            </div>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="方法">
            <span class="mono">{{ detail.method }}</span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="TraceId">
            <span class="mono">{{ detail.traceId ?? '—' }}</span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="操作时间">
            <span class="mono">{{ dateTime(detail.operTime) }}</span>
          </ElDescriptionsItem>
          <ElDescriptionsItem
            v-if="detail.errorMsg"
            label="异常信息"
            :span="2"
          >
            <span class="fail-msg">{{ detail.errorMsg }}</span>
          </ElDescriptionsItem>
        </ElDescriptions>

        <!-- 入参/出参原样铺:敏感键在落库前已由后端 SensitiveDataMasker 替成 ******,前端不再二次处理 -->
        <div class="payload">
          <div class="payload-head">
            <span class="payload-title">请求参数</span>
            <button
              v-if="detail.param"
              class="copy-btn"
              type="button"
              @click="copyText(detail.param)"
            >
              <AppIcon
                name="copy"
                :size="13"
              />
              复制
            </button>
          </div>
          <pre class="payload-body">{{ detail.param ?? '—' }}</pre>
        </div>
        <div class="payload">
          <div class="payload-head">
            <span class="payload-title">返回结果</span>
            <button
              v-if="detail.result"
              class="copy-btn"
              type="button"
              @click="copyText(detail.result)"
            >
              <AppIcon
                name="copy"
                :size="13"
              />
              复制
            </button>
          </div>
          <pre class="payload-body">{{ detail.result ?? '—' }}</pre>
        </div>
      </template>
    </ElDrawer>
  </div>
</template>

<style scoped>
/* 业务类型彩色徽标:增删改导分色,弱底不抢结果列的视觉权重。 */
.type-pill {
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

.type-pill.success {
  color: var(--app-success);
  background: rgba(var(--app-success-rgb), 0.1);
}

.type-pill.info {
  color: var(--app-info);
  background: rgba(var(--app-info-rgb), 0.1);
}

.type-pill.warning {
  color: var(--app-warning);
  background: rgba(var(--app-warning-rgb), 0.12);
}

.type-pill.danger {
  color: var(--app-danger);
  background: rgba(var(--app-danger-rgb), 0.1);
}

/* 请求方法徽标:等宽短标 + URL 同排,扫读时方法先行。 */
.method-badge {
  display: inline-flex;
  padding: 1px 6px;
  border-radius: 6px;
  color: var(--app-muted);
  background: var(--app-soft-2);
  font-family: var(--app-font-mono);
  font-size: 10px;
  font-weight: 700;
  white-space: nowrap;
}

.method-badge.info {
  color: var(--app-info);
  background: rgba(var(--app-info-rgb), 0.1);
}

.method-badge.success {
  color: var(--app-success);
  background: rgba(var(--app-success-rgb), 0.1);
}

.method-badge.warning {
  color: var(--app-warning);
  background: rgba(var(--app-warning-rgb), 0.12);
}

.method-badge.danger {
  color: var(--app-danger);
  background: rgba(var(--app-danger-rgb), 0.1);
}

.request-cell {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
  max-width: 100%;
}

.request-url {
  overflow: hidden;
  color: var(--app-ink);
  font-family: var(--app-font-mono);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 用户格:首字母小方块 + 登录名。 */
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

/* 租户格:名称为主、编码为辅的等宽小字。 */
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

.module-text {
  color: var(--app-ink);
  font-weight: 550;
}

/* 时间与 IP 走等宽字形,逐位对齐更易扫读。 */
.mono {
  font-family: var(--app-font-mono);
  font-size: 11px;
}

/* 耗时分档:超 1s 转警示,超 3s 转危险,正常行弱化。 */
.cost {
  font-family: var(--app-font-mono);
  font-size: 11px;
  color: var(--app-muted);
}

.cost.warn {
  color: var(--app-warning);
}

.cost.slow {
  color: var(--app-danger);
  font-weight: 650;
}

.fail-msg {
  color: var(--app-danger);
}

/* 抽屉头:模块名 + 类型/结果双徽标。 */
.drawer-head {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.drawer-head h3 {
  overflow: hidden;
  margin: 0;
  color: var(--app-ink);
  font-size: 15px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-desc {
  margin-bottom: 4px;
}

.tenant-inline small {
  margin-left: 6px;
  color: var(--app-muted);
  font-family: var(--app-font-mono);
  font-size: 10px;
}

.payload {
  margin-top: 16px;
}

.payload-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.payload-title {
  color: var(--app-muted);
  font-size: 12px;
}

.copy-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  border: 0;
  border-radius: 6px;
  background: none;
  color: var(--app-muted);
  cursor: pointer;
  font-size: 11px;
  transition:
    background 0.16s,
    color 0.16s;
}

.copy-btn:hover {
  background: var(--app-soft-2);
  color: var(--app-primary);
}

.payload-body {
  margin: 0;
  max-height: 320px;
  padding: 10px 12px;
  overflow-y: auto;
  border: 1px solid var(--app-line);
  border-radius: 10px;
  background: var(--app-soft);
  color: var(--app-ink);
  font-family: var(--app-font-mono);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
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
