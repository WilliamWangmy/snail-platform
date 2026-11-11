<script setup lang="ts">
/** 组件名与路由 meta.viewName 对应,多页签 KeepAlive include 依此缓存。 */
defineOptions({ name: 'PlatformExternal' });
import { onMounted, reactive, ref } from 'vue';
import {
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

/** 调用方自报标识的常用取值;同样 allow-create,兼容后续新调用方。 */
const CALLER_OPTIONS = ['notify', 'auth', 'system'];

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

/* --------------------------------- 展示辅助 --------------------------------- */

/** 终态口径:0 成功 1 失败(与操作日志相反,页面注释与列头都标清,避免误读)。 */
function statusOf(status: number): { text: string; cls: string } {
  return status === 0 ? { text: '成功', cls: 'success' } : { text: '失败', cls: 'danger' };
}

/** 展开行的空值兜底:快照字段允许为空,统一显示占位符。 */
function orDash(value: string | number | null): string | number {
  return value ?? '—';
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
        <p>邮件/短信/IP 定位/三方登录等所有出站调用的留痕台账,只读,便于对账与排障</p>
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
              :key="opt"
              :label="opt"
              :value="opt"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="终态">
          <ElSelect
            v-model="query.status"
            clearable
            placeholder="不限"
            style="width: 110px"
          >
            <ElOption
              label="成功"
              :value="0"
            />
            <ElOption
              label="失败"
              :value="1"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="调用时间">
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
          <h3>调用流水 <span>共 {{ total }} 条</span></h3>
        </div>
      </template>

      <ElTable
        v-loading="loading"
        :data="rows"
        row-key="callId"
      >
        <!-- 展开行承载大字段:请求/响应快照与失败原因在列宽里放不下,点开看全文 -->
        <ElTableColumn type="expand">
          <template #default="{ row }">
            <div class="call-detail">
              <div class="detail-block">
                <div class="detail-label">
                  请求快照(已脱敏截断)
                </div>
                <pre class="detail-body">{{ orDash(row.request) }}</pre>
              </div>
              <div class="detail-block">
                <div class="detail-label">
                  响应摘要(已脱敏截断)
                </div>
                <pre class="detail-body">{{ orDash(row.response) }}</pre>
              </div>
              <div class="detail-meta">
                <span>失败原因:<b>{{ orDash(row.error) }}</b></span>
                <span>链路 ID:<b class="mono">{{ orDash(row.traceId) }}</b></span>
                <span>触发人 ID:<b class="mono">{{ orDash(row.operatorId) }}</b></span>
                <span>业务 ID:<b class="mono">{{ orDash(row.bizId) }}</b></span>
              </div>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="调用时间"
          min-width="160"
        >
          <template #default="{ row }">
            {{ dateTime(row.createTime) }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="厂商"
          min-width="110"
        >
          <template #default="{ row }">
            {{ row.vendor }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="动作"
          min-width="120"
        >
          <template #default="{ row }">
            {{ row.action }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="调用方"
          width="90"
        >
          <template #default="{ row }">
            {{ row.caller }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="业务域"
          min-width="110"
        >
          <template #default="{ row }">
            {{ row.bizType ?? '—' }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="终态"
          width="80"
          align="center"
        >
          <template #default="{ row }">
            <span
              class="status-pill"
              :class="statusOf(row.status).cls"
            >
              {{ statusOf(row.status).text }}
            </span>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="耗时"
          width="90"
          align="right"
        >
          <template #default="{ row }">
            {{ row.costMs }} ms
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="租户 ID"
          width="90"
          align="center"
        >
          <template #default="{ row }">
            {{ row.tenantId ?? '—' }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="失败原因"
          min-width="150"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ row.error ?? '—' }}
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
/* 展开行详情:快照等宽字形 + 保留换行,还原落库原文便于比对厂商报文 */
.call-detail {
  padding: 4px 12px 8px 48px;
}

.detail-block + .detail-block {
  margin-top: 10px;
}

.detail-label {
  margin-bottom: 4px;
  color: var(--app-muted);
  font-size: 10.5px;
}

.detail-body {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--app-line);
  border-radius: 10px;
  background: var(--app-soft);
  color: var(--app-ink);
  font-family: var(--app-font-mono);
  font-size: 11.5px;
  white-space: pre-wrap;
  word-break: break-all;
}

.detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 18px;
  margin-top: 10px;
  color: var(--app-muted);
  font-size: 11px;
}

.detail-meta b {
  color: var(--app-ink);
  font-weight: 500;
}

.detail-meta .mono {
  font-family: var(--app-font-mono);
}
</style>
