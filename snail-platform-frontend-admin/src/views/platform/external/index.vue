<script setup lang="ts">
/** 组件名与路由 meta.viewName 对应,多页签 KeepAlive include 依此缓存。 */
defineOptions({ name: 'PlatformExternal' });
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import AppIcon from '@/components/AppIcon.vue';
import {
  cleanExternalCallLogs,
  pageExternalCalls,
  type ExternalCallLogVO,
  type ExternalCallQuery,
} from '@/api/external';
import { usePageList } from '@/composables/usePageList';
import { dateTime } from '@/utils/format';

/* --------------------------------- 筛选状态 --------------------------------- */

const query = reactive<ExternalCallQuery>({
  vendor: '',
  caller: '',
  status: null,
});

/** 时间区间只作 UI 状态,提交前拆成 beginTime/endTime 两个 ISO 局部日期时间参数(与日志页同口径)。 */
const range = ref<string[]>([]);

/** 时间范围快捷项:排障高频场景是"看今天/看最近一小时",免手动拉时间轴。 */
const TIME_SHORTCUTS = [
  {
    text: '今天',
    value: () => {
      const start = new Date();
      start.setHours(0, 0, 0, 0);
      return [start, new Date()];
    },
  },
  {
    text: '近 1 小时',
    value: () => [new Date(Date.now() - 3_600_000), new Date()],
  },
  {
    text: '近 24 小时',
    value: () => [new Date(Date.now() - 86_400_000), new Date()],
  },
  {
    text: '近 7 天',
    value: () => [new Date(Date.now() - 7 * 86_400_000), new Date()],
  },
];

/**
 * 厂商下拉选项:与 external 模块各 client 自报的 vendor() 一一对应;
 * 新厂商接入后端即可落新值,下拉用 allow-create 兜住,不必等前端改代码。
 */
const VENDOR_OPTIONS = [
  { value: 'smtp-mail', label: 'SMTP 邮件' },
  { value: 'mock-sms', label: '短信(mock)' },
  { value: 'dingtalk', label: '钉钉登录' },
  { value: 'feishu', label: '飞书登录' },
  { value: 'wecom', label: '企业微信登录' },
  { value: 'mock-sso', label: 'SSO(mock)' },
];

/** 调用方下拉选项:自报标识配中文语义,allow-create 兜底后续新调用方。 */
const CALLER_OPTIONS = [
  { value: 'notify', label: '消息通知' },
  { value: 'auth', label: '认证登录' },
  { value: 'system', label: '系统任务' },
];

/** 调用方语义映射:落库的是自报英文标识,列表翻译成人话,未登记的标识原样回显。 */
const CALLER_LABELS: Record<string, string> = {
  notify: '消息通知',
  auth: '认证登录',
  system: '系统任务',
  unknown: '未标识',
};

function callerLabel(caller: string): string {
  return CALLER_LABELS[caller] ?? caller;
}

/** 动作语义映射:与厂商 client 的 action 取值一一对应,新动作未登记则原样显示。 */
const ACTION_LABELS: Record<string, string> = {
  'send-mail': '发送邮件',
  'send-sms': '发送短信',
  'geo-ip': 'IP 定位',
  'oauth-token': '换取访问令牌',
  'oauth-userinfo': '拉取用户信息',
};

function actionLabel(action: string): string {
  return ACTION_LABELS[action] ?? action;
}

/** 终态分段筛选:0 成功 1 失败(与操作日志相反),选中即查,免去"点下拉再点查询"两步。 */
function setStatus(value: 0 | 1 | null): void {
  query.status = value;
  void search();
}

/* --------------------------------- 列表加载 --------------------------------- */

const { rows, total, loading, pageNum, pageSize, refresh, search } = usePageList<ExternalCallLogVO>(
  (page) =>
    pageExternalCalls({
      ...query,
      beginTime: range.value[0],
      endTime: range.value[1],
      ...page,
    }),
  20,
);

onMounted(refresh);

function reset(): void {
  query.vendor = '';
  query.caller = '';
  query.status = null;
  range.value = [];
  void search();
}

/* --------------------------------- 本页概览 --------------------------------- */

/**
 * 概览卡只统计当前页:接口没有汇总端点,不在前端假造"全量成功率";
 * 标签统一写"本页",口径诚实,当页翻动即随之刷新。
 */
const pageStats = computed(() => {
  const list = rows.value;
  const failed = list.filter((row) => row.status !== 0).length;
  const avg = list.length
    ? Math.round(list.reduce((sum, row) => sum + row.costMs, 0) / list.length)
    : 0;
  const slowest = list.reduce((max, row) => Math.max(max, row.costMs), 0);
  return { total: list.length, ok: list.length - failed, failed, avg, slowest };
});

/* --------------------------------- 展示辅助 --------------------------------- */

/** 厂商展示名:下拉映射表反查,allow-create 的自定义厂商原样回显。 */
function vendorLabel(vendor: string): string {
  return VENDOR_OPTIONS.find((opt) => opt.value === vendor)?.label ?? vendor;
}

/** 厂商图标:按语义归类(邮件/短信/三方登录/兜底连线),新厂商落默认值即可。 */
function vendorIcon(vendor: string): string {
  if (vendor.includes('mail')) return 'mail';
  if (vendor.includes('sms')) return 'phone';
  if (vendor.includes('sso') || vendor.includes('oauth')) return 'shield';
  return 'chat';
}

/** 厂商色档:字符哈希稳定分档,同一厂商永远同色,扫一眼就能按颜色分组。 */
function vendorTone(vendor: string): string {
  let hash = 0;
  for (const ch of vendor) hash = (hash * 31 + ch.charCodeAt(0)) % 997;
  return ['tone-a', 'tone-b', 'tone-c', 'tone-d'][hash % 4]!;
}

/** 耗时分级:1s 内正常,1-5s 预警,5s 以上视为异常,与微条同色呼应。 */
function costTone(costMs: number): string {
  if (costMs <= 1000) return '';
  if (costMs <= 5000) return 'warning';
  return 'danger';
}

/** 微条宽度按 5s 封顶线性映射,慢调用一眼满条。 */
function costWidth(costMs: number): string {
  return `${Math.min(100, Math.round((costMs / 5000) * 100))}%`;
}

/* --------------------------------- 详情抽屉 --------------------------------- */

const detail = ref<ExternalCallLogVO | null>(null);
const detailOpen = ref(false);

/** ElTable 的 row-click 回调参数是宽型 DefaultRow,入口收 unknown、在此单点收窄。 */
function openDetail(row: unknown): void {
  detail.value = row as ExternalCallLogVO;
  detailOpen.value = true;
}

/**
 * 快照美化:请求/响应多为 JSON,能解析就缩进展开便于逐字段核对;
 * 截断导致的残缺报文解析失败则原样回显,不二次加工。
 */
function prettyOf(raw: string | null): string {
  if (!raw) return '';
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

async function copyText(text: string, tip: string): Promise<void> {
  if (!text) return;
  try {
    await navigator.clipboard.writeText(text);
    ElMessage.success(tip);
  } catch {
    ElMessage.error('复制失败,请手动选择');
  }
}

/** 展开行的空值兜底:快照字段允许为空,统一显示占位符。 */
function orDash(value: string | number | null): string | number {
  return value ?? '—';
}

/* --------------------------------- 历史清除 --------------------------------- */

/** 清除档位:command 值与后端 ExternalCallLogCleanRequest.before 的三档白名单一致。 */
const CLEAN_OPTIONS: Record<string, string> = { '1y': '一年前', '6m': '半年前', '1m': '一个月前' };
const cleaning = ref(false);

/** 二次确认后再发清除请求:流水是平台面账本,清除即整表按期删、无租户维度可选。 */
async function cleanLogs(command: string | number | object): Promise<void> {
  const before = String(command) as '1m' | '6m' | '1y';
  const label = CLEAN_OPTIONS[before] ?? before;
  await ElMessageBox.confirm(
    `将永久删除 ${label}产生的全部调用流水,删除后不可恢复,确认执行?`,
    '清除调用流水',
    { type: 'warning', confirmButtonText: '确认清除', cancelButtonText: '取消' },
  );
  cleaning.value = true;
  try {
    const count = await cleanExternalCallLogs(before);
    ElMessage.success(`已清除 ${count} 条历史流水`);
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
          EXTERNAL CALLS
        </div>
        <h1>第三方调用</h1>
        <p>邮件/短信/IP 定位/三方登录等所有出站调用的留痕台账,便于对账与排障,支持按保留期清除历史</p>
      </div>
      <div class="head-actions">
        <ElDropdown
          v-permission="'platform:external:clean'"
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
      </div>
    </div>

    <div class="ext-stats">
      <div class="stat-card">
        <div class="stat-ico">
          <AppIcon
            name="external"
            :size="16"
          />
        </div>
        <div class="stat-info">
          <b class="mono">{{ pageStats.total }}</b>
          <span>本页调用</span>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-ico ok">
          <AppIcon
            name="check"
            :size="16"
          />
        </div>
        <div class="stat-info">
          <b class="mono">{{ pageStats.ok }}</b>
          <span>成功</span>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-ico bad">
          <AppIcon
            name="alert"
            :size="16"
          />
        </div>
        <div class="stat-info">
          <b
            class="mono"
            :class="{ 'is-bad': pageStats.failed > 0 }"
          >
            {{ pageStats.failed }}
          </b>
          <span>失败</span>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-ico slow">
          <AppIcon
            name="clock"
            :size="16"
          />
        </div>
        <div class="stat-info">
          <b class="mono">
            {{ pageStats.avg }}<i>ms</i>
          </b>
          <span>平均耗时 · 最慢 {{ pageStats.slowest }}ms</span>
        </div>
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
        <ElFormItem label="厂商">
          <ElSelect
            v-model="query.vendor"
            clearable
            filterable
            allow-create
            default-first-option
            placeholder="不限"
            style="width: 170px"
          >
            <ElOption
              v-for="opt in VENDOR_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="调用方">
          <ElSelect
            v-model="query.caller"
            clearable
            filterable
            allow-create
            default-first-option
            placeholder="不限"
            style="width: 140px"
          >
            <ElOption
              v-for="opt in CALLER_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="终态">
          <div class="seg">
            <button
              type="button"
              :class="{ on: query.status === null }"
              @click="setStatus(null)"
            >
              全部
            </button>
            <button
              type="button"
              class="s-ok"
              :class="{ on: query.status === 0 }"
              @click="setStatus(0)"
            >
              成功
            </button>
            <button
              type="button"
              class="s-bad"
              :class="{ on: query.status === 1 }"
              @click="setStatus(1)"
            >
              失败
            </button>
          </div>
        </ElFormItem>
        <ElFormItem label="调用时间">
          <ElDatePicker
            v-model="range"
            type="datetimerange"
            value-format="YYYY-MM-DDTHH:mm:ss"
            start-placeholder="起始"
            end-placeholder="截止"
            range-separator="至"
            :shortcuts="TIME_SHORTCUTS"
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
          <h3>调用流水 <span>共 {{ total }} 条 · 点击行查看详情</span></h3>
        </div>
      </template>

      <ElTable
        v-loading="loading"
        :data="rows"
        row-key="callId"
        class="ext-table"
        @row-click="openDetail"
      >
        <ElTableColumn
          label="调用时间"
          min-width="150"
        >
          <template #default="{ row }">
            <span class="time-mono">{{ dateTime(row.createTime) }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="厂商 / 动作"
          min-width="200"
        >
          <template #default="{ row }">
            <div class="va-cell">
              <span
                class="vendor-chip"
                :class="vendorTone(row.vendor)"
              >
                <AppIcon
                  :name="vendorIcon(row.vendor)"
                  :size="12"
                />
                {{ vendorLabel(row.vendor) }}
              </span>
              <code
                class="action-code"
                :title="actionLabel(row.action)"
              >{{ row.action }}</code>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="调用方"
          min-width="140"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <div class="caller-cell">
              <b>{{ callerLabel(row.caller) }}</b>
              <span v-if="row.operatorId">触发人#{{ row.operatorId }}</span>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="业务"
          min-width="120"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <span
              v-if="row.bizType || row.bizId"
              class="biz-mono"
            >
              {{ row.bizType ?? '—' }}<template v-if="row.bizId"> · #{{ row.bizId }}</template>
            </span>
            <span
              v-else
              class="cell-dash"
            >—</span>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="终态"
          width="76"
          align="center"
        >
          <template #default="{ row }">
            <span
              class="status-pill"
              :class="row.status === 0 ? 'success' : 'danger'"
            >
              {{ row.status === 0 ? '成功' : '失败' }}
            </span>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="耗时"
          width="110"
          align="right"
        >
          <template #default="{ row }">
            <div class="cost-cell">
              <b
                class="cost-num"
                :class="costTone(row.costMs)"
              >
                {{ row.costMs }}<i>ms</i>
              </b>
              <span class="cost-bar"><i
                :class="costTone(row.costMs)"
                :style="{ width: costWidth(row.costMs) }"
              ></i></span>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="租户"
          width="64"
          align="center"
        >
          <template #default="{ row }">
            <span
              v-if="row.tenantId !== null"
              class="biz-mono"
            >{{ row.tenantId }}</span>
            <span
              v-else
              class="cell-dash"
            >—</span>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="失败原因"
          min-width="170"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <span :class="{ 'reason-text': row.error }">{{ row.error ?? '—' }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="操作"
          width="80"
          align="center"
        >
          <template #default="{ row }">
            <button
              type="button"
              class="row-link"
              @click.stop="openDetail(row)"
            >
              详情
            </button>
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
      v-model="detailOpen"
      size="580px"
      class="ext-drawer"
    >
      <template #header>
        <div
          v-if="detail"
          class="drawer-head"
        >
          <div class="drawer-title">
            <b>{{ vendorLabel(detail.vendor) }} · {{ actionLabel(detail.action) }}</b>
            <span class="mono">{{ detail.action }} · {{ dateTime(detail.createTime) }}</span>
          </div>
          <span
            class="status-pill lg"
            :class="detail.status === 0 ? 'success' : 'danger'"
          >
            {{ detail.status === 0 ? '成功' : '失败' }}
          </span>
        </div>
      </template>

      <template v-if="detail">
        <div
          v-if="detail.error"
          class="fail-banner"
        >
          <AppIcon
            name="alert"
            :size="14"
          />
          <span>{{ detail.error }}</span>
        </div>

        <div class="meta-grid">
          <div class="meta-item">
            <span>调用方</span>
            <b>{{ callerLabel(detail.caller) }}</b>
          </div>
          <div class="meta-item">
            <span>触发人</span>
            <b class="mono">{{ orDash(detail.operatorId) }}</b>
          </div>
          <div class="meta-item">
            <span>租户 ID</span>
            <b class="mono">{{ orDash(detail.tenantId) }}</b>
          </div>
          <div class="meta-item">
            <span>业务域</span>
            <b>{{ orDash(detail.bizType) }}</b>
          </div>
          <div class="meta-item">
            <span>业务 ID</span>
            <div class="meta-val">
              <b class="mono">{{ orDash(detail.bizId) }}</b>
              <button
                v-if="detail.bizId !== null"
                type="button"
                class="copy-btn sm"
                title="复制业务 ID"
                @click="copyText(String(detail.bizId), '业务 ID 已复制')"
              >
                <AppIcon
                  name="copy"
                  :size="11"
                />
              </button>
            </div>
          </div>
          <div class="meta-item">
            <span>耗时</span>
            <b class="mono">{{ detail.costMs }} ms</b>
          </div>
          <div class="meta-item span-2">
            <span>链路 ID</span>
            <div class="meta-val">
              <b class="mono">{{ orDash(detail.traceId) }}</b>
              <button
                v-if="detail.traceId"
                type="button"
                class="copy-btn sm"
                title="复制链路 ID"
                @click="copyText(detail.traceId, '链路 ID 已复制')"
              >
                <AppIcon
                  name="copy"
                  :size="11"
                />
              </button>
            </div>
          </div>
        </div>

        <div class="snap-block">
          <div class="snap-head">
            <span class="snap-title">
              <AppIcon
                name="send"
                :size="13"
              />
              请求快照<small>已脱敏截断</small>
            </span>
            <button
              v-if="detail.request"
              type="button"
              class="copy-btn"
              @click="copyText(prettyOf(detail.request), '请求快照已复制')"
            >
              <AppIcon
                name="copy"
                :size="12"
              />
              复制
            </button>
          </div>
          <pre class="snap-body">{{ prettyOf(detail.request) || '—' }}</pre>
        </div>

        <div class="snap-block">
          <div class="snap-head">
            <span class="snap-title">
              <AppIcon
                name="receipt"
                :size="13"
              />
              响应摘要<small>已脱敏截断</small>
            </span>
            <button
              v-if="detail.response"
              type="button"
              class="copy-btn"
              @click="copyText(prettyOf(detail.response), '响应摘要已复制')"
            >
              <AppIcon
                name="copy"
                :size="12"
              />
              复制
            </button>
          </div>
          <pre class="snap-body">{{ prettyOf(detail.response) || '—' }}</pre>
        </div>
      </template>
    </ElDrawer>
  </div>
</template>

<style scoped>
/* 抽屉骨架:头部收紧、副行贴标题,避免默认 32px 大空隙。 */
.ext-drawer :deep(.el-drawer__header) {
  margin-bottom: 0;
  padding: 18px 20px 14px;
  border-bottom: 1px solid var(--app-line);
}

.ext-drawer :deep(.el-drawer__body) {
  padding: 16px 20px;
}

/* 概览卡:只读账本的当页速览,四档等宽排布。 */
.ext-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 15px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 13px 16px;
  border: 1px solid var(--app-line);
  border-radius: var(--app-radius);
  background: var(--app-card);
  box-shadow: var(--app-shadow);
}

.stat-ico {
  display: inline-grid;
  place-items: center;
  width: 34px;
  height: 34px;
  flex: none;
  border-radius: 10px;
  color: var(--app-primary);
  background: var(--app-soft);
}

.stat-ico.ok {
  color: var(--app-success);
  background: rgba(var(--app-success-rgb), 0.1);
}

.stat-ico.bad {
  color: var(--app-danger);
  background: rgba(var(--app-danger-rgb), 0.1);
}

.stat-ico.slow {
  color: var(--app-warning);
  background: rgba(var(--app-warning-rgb), 0.1);
}

.stat-info b {
  display: block;
  color: var(--app-ink);
  font-size: 17px;
  line-height: 1.15;
}

.stat-info b i {
  margin-left: 2px;
  font-style: normal;
  font-size: 10px;
}

.stat-info b.is-bad {
  color: var(--app-danger);
}

.stat-info span {
  display: block;
  margin-top: 2px;
  color: var(--app-muted);
  font-size: 9.5px;
}

/* 终态分段控件:选中即查,一步到位。 */
.seg {
  display: inline-flex;
  padding: 2px;
  border: 1px solid var(--app-line);
  border-radius: 9px;
  background: var(--app-soft);
}

.seg button {
  padding: 4px 13px;
  border: 0;
  border-radius: 7px;
  background: none;
  color: var(--app-muted);
  cursor: pointer;
  font-size: 11px;
  white-space: nowrap;
  transition:
    color 0.16s,
    background 0.16s;
}

.seg button:hover {
  color: var(--app-ink);
}

.seg button.on {
  background: var(--app-card);
  color: var(--app-ink);
  font-weight: 600;
  box-shadow: 0 1px 4px rgba(16, 61, 57, 0.1);
}

.seg button.s-ok.on {
  color: var(--app-success);
}

.seg button.s-bad.on {
  color: var(--app-danger);
}

/* 行可点:整行点击开抽屉。 */
.ext-table :deep(.el-table__row) {
  cursor: pointer;
}

/* 时间列:等宽单行,日期与时刻扫读同权重。 */
.time-mono {
  color: var(--app-ink);
  font-family: var(--app-font-mono);
  font-size: 11.5px;
  white-space: nowrap;
}

/* 厂商/动作单行横排:徽标 + 等宽码,一行读完"谁家的什么动作"。 */
.va-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  white-space: nowrap;
}

.vendor-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 9px;
  border-radius: 99px;
  font-size: 10.5px;
  font-weight: 600;
  white-space: nowrap;
}

.vendor-chip.tone-a {
  color: var(--app-primary);
  background: rgba(var(--app-primary-rgb), 0.12);
}

.vendor-chip.tone-b {
  color: var(--app-info);
  background: rgba(var(--app-info-rgb), 0.12);
}

.vendor-chip.tone-c {
  color: var(--app-warning);
  background: rgba(var(--app-warning-rgb), 0.12);
}

.vendor-chip.tone-d {
  color: var(--app-danger);
  background: rgba(var(--app-danger-rgb), 0.12);
}

.action-code {
  padding: 2px 7px;
  border: 1px solid var(--app-line);
  border-radius: 6px;
  background: var(--app-soft);
  color: var(--app-muted);
  font-family: var(--app-font-mono);
  font-size: 10px;
  white-space: nowrap;
}

/* 调用方:中文语义 + 触发人,单行横排。 */
.caller-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  white-space: nowrap;
}

.caller-cell b {
  color: var(--app-ink);
  font-size: 11.5px;
  font-weight: 600;
}

.caller-cell span {
  color: var(--app-muted);
  font-size: 10px;
}

/* 业务/租户等标识列:等宽 + 空值破折号弱化。 */
.biz-mono {
  color: var(--app-muted);
  font-family: var(--app-font-mono);
  font-size: 11px;
}

.cell-dash {
  color: color-mix(in srgb, var(--app-muted) 45%, transparent);
}

/* 耗时:数值分级着色 + 5s 封顶微条。 */
.cost-cell {
  display: inline-grid;
  gap: 4px;
  justify-items: end;
  vertical-align: middle;
}

.cost-num {
  color: var(--app-ink);
  font-family: var(--app-font-mono);
  font-size: 11px;
  font-weight: 600;
  white-space: nowrap;
}

.cost-num i {
  margin-left: 2px;
  color: var(--app-muted);
  font-style: normal;
  font-size: 9px;
  font-weight: 400;
}

.cost-num.warning {
  color: var(--app-warning);
}

.cost-num.danger {
  color: var(--app-danger);
}

.cost-bar {
  display: block;
  width: 64px;
  height: 3px;
  overflow: hidden;
  border-radius: 99px;
  background: var(--app-soft-2);
}

.cost-bar i {
  display: block;
  height: 100%;
  border-radius: 99px;
  background: var(--app-muted);
}

.cost-bar i.warning {
  background: var(--app-warning);
}

.cost-bar i.danger {
  background: var(--app-danger);
}

/* 失败原因弱高亮:只着色不加重,配合溢出 tooltip。 */
.reason-text {
  color: var(--app-danger);
}

/* 行尾"详情"入口:nowrap 防窄列挤成竖排。 */
.row-link {
  padding: 3px 10px;
  border: 1px solid transparent;
  border-radius: 7px;
  background: none;
  color: var(--app-primary);
  cursor: pointer;
  font-size: 10.5px;
  white-space: nowrap;
  transition:
    background 0.16s,
    border-color 0.16s;
}

.row-link:hover {
  border-color: rgba(var(--app-primary-rgb), 0.35);
  background: rgba(var(--app-primary-rgb), 0.08);
}

/* 抽屉头部:语义标题 + 时间副行,终态徽标右置。 */
.drawer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
}

.drawer-title b {
  display: block;
  color: var(--app-ink);
  font-size: 15px;
  letter-spacing: -0.01em;
}

.drawer-title span {
  display: block;
  margin-top: 4px;
  color: var(--app-muted);
  font-size: 10.5px;
}

.drawer-title .mono {
  font-family: var(--app-font-mono);
}

.status-pill.lg {
  padding: 5px 11px;
  font-size: 11px;
  flex: none;
}

/* 失败横幅:抽屉内置顶展示失败原因,左描边加重。 */
.fail-banner {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 16px;
  padding: 10px 13px;
  border: 1px solid rgba(var(--app-danger-rgb), 0.3);
  border-left-width: 3px;
  border-radius: 9px;
  background: rgba(var(--app-danger-rgb), 0.08);
  color: var(--app-danger);
  font-size: 12px;
  line-height: 1.55;
  word-break: break-all;
}

.fail-banner .app-icon {
  flex: none;
  margin-top: 2px;
}

/* 元信息网格:三列速查,浅底面板承托。 */
.meta-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px 12px;
  margin-bottom: 18px;
  padding: 14px 16px;
  border: 1px solid var(--app-line);
  border-radius: 12px;
  background: var(--app-soft);
}

.meta-item span {
  display: block;
  margin-bottom: 4px;
  color: var(--app-muted);
  font-size: 9.5px;
}

.meta-item b {
  color: var(--app-ink);
  font-size: 12px;
  font-weight: 600;
  word-break: break-all;
}

.meta-item .mono {
  font-family: var(--app-font-mono);
  font-weight: 500;
}

.meta-item .span-2 {
  grid-column: span 2;
}

/* 值行:值 + 复制钮同排,超长省略。 */
.meta-val {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.meta-val b {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.meta-val .copy-btn {
  flex: none;
}

/* 快照块:图标标题 + 复制钮右置,报文体可滚动。 */
.snap-block {
  overflow: hidden;
  margin-bottom: 14px;
  border: 1px solid var(--app-line);
  border-radius: 12px;
}

.snap-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 9px 13px;
  border-bottom: 1px solid var(--app-line);
  background: var(--app-soft);
}

.snap-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--app-ink);
  font-size: 11.5px;
  font-weight: 600;
}

.snap-title .app-icon {
  color: var(--app-primary);
}

.snap-title small {
  color: var(--app-muted);
  font-size: 9.5px;
  font-weight: 400;
}

.copy-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 9px;
  border: 1px solid var(--app-line);
  border-radius: 7px;
  background: var(--app-card);
  color: var(--app-muted);
  cursor: pointer;
  font-size: 10px;
  white-space: nowrap;
  transition:
    color 0.16s,
    border-color 0.16s;
}

.copy-btn:hover {
  border-color: rgba(var(--app-primary-rgb), 0.4);
  color: var(--app-primary);
}

.copy-btn.sm {
  padding: 3px 5px;
  border: 0;
  background: none;
}

.copy-btn.sm:hover {
  border: 0;
  background: rgba(var(--app-primary-rgb), 0.08);
}

.snap-body {
  max-height: 300px;
  margin: 0;
  padding: 12px 14px;
  overflow: auto;
  background: var(--app-card);
  color: var(--app-ink);
  font-family: var(--app-font-mono);
  font-size: 11px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-all;
}

@media (max-width: 1100px) {
  .ext-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
