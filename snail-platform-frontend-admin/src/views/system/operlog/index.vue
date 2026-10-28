<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import AppIcon from '@/components/AppIcon.vue';
import {
  exportOperLogs,
  getOperLog,
  pageOperLogs,
  type OperLogDetailVO,
  type OperLogListVO,
  type OperLogQuery,
} from '@/api/log';
import { usePageList } from '@/composables/usePageList';
import { useAuthStore } from '@/stores/auth';
import { dateTime } from '@/utils/format';

const auth = useAuthStore();

/** 与后端 BusinessType 枚举同一份口径。 */
const BUSINESS_TYPES = [
  { value: 1, label: '新增' },
  { value: 2, label: '修改' },
  { value: 3, label: '删除' },
  { value: 4, label: '导出' },
  { value: 5, label: '导入' },
  { value: 9, label: '其他' },
];

function businessLabel(value: number): string {
  return BUSINESS_TYPES.find((item) => item.value === value)?.label ?? String(value);
}

const query = reactive<OperLogQuery>({ module: '', operator: '', businessType: null, status: null });
/** datetimerange 是一个数组,提交前才拆成 beginTime/endTime 两个 ISO 参数。 */
const range = ref<string[]>([]);
/** 租户 ID 输入框只对持 platform:operlog:all 者渲染(见模板 v-if),其余租户由后端钉死本租户。 */
const tenantInput = ref('');

function tenantIdOf(): number | null {
  const raw = tenantInput.value.trim();
  if (raw === '') return null;
  const value = Number(raw);
  // 非数字折成 null:NaN 进查询串就是 tenantId=NaN,后端按无效参数处理,列表与导出件一起空
  return Number.isFinite(value) ? value : null;
}

/** 条件汇总成一份:分页与导出共用,保证下载件与页面所见同集合。 */
function currentQuery(): OperLogQuery {
  return {
    ...query,
    tenantId: tenantIdOf(),
    beginTime: range.value[0],
    endTime: range.value[1],
  };
}

const { rows, total, loading, pageNum, pageSize, refresh, search } = usePageList<OperLogListVO>(
  (page) => pageOperLogs({ ...currentQuery(), ...page }),
  20,
);

onMounted(refresh);

function reset(): void {
  query.module = '';
  query.operator = '';
  query.businessType = null;
  query.status = null;
  range.value = [];
  tenantInput.value = '';
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
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="eyebrow">
          OPERATION LOG
        </div>
        <h1>操作日志</h1>
      </div>
      <div class="head-actions">
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
            style="width: 180px"
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
            style="width: 190px"
            @keyup.enter="search"
          />
        </ElFormItem>
        <ElFormItem label="类型">
          <ElSelect
            v-model="query.businessType"
            clearable
            placeholder="不限"
            style="width: 120px"
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
          <ElSelect
            v-model="query.status"
            clearable
            placeholder="不限"
            style="width: 120px"
          >
            <ElOption
              label="成功"
              :value="1"
            />
            <ElOption
              label="失败"
              :value="0"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem
          v-if="auth.has('platform:operlog:all')"
          label="租户 ID"
        >
          <ElInput
            v-model="tenantInput"
            clearable
            placeholder="留空跨租户"
            style="width: 150px"
          />
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
        </div>
      </template>

      <ElTable
        v-loading="loading"
        :data="rows"
        row-key="id"
      >
        <ElTableColumn
          label="操作时间"
          min-width="160"
        >
          <template #default="{ row }">
            {{ dateTime(row.operTime) }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          prop="module"
          label="模块"
          min-width="120"
        />
        <ElTableColumn
          label="类型"
          width="90"
          align="center"
        >
          <template #default="{ row }">
            {{ businessLabel(row.businessType) }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          prop="operator"
          label="操作人"
          min-width="110"
        />
        <ElTableColumn
          label="请求"
          min-width="220"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ row.requestMethod }} {{ row.url }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="IP"
          min-width="120"
        >
          <template #default="{ row }">
            {{ row.ip ?? '—' }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="耗时"
          width="90"
          align="right"
        >
          <template #default="{ row }">
            {{ row.costTime ?? 0 }} ms
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="结果"
          width="90"
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
      title="操作日志详情"
      size="640px"
    >
      <ElSkeleton v-if="detailLoading" />
      <template v-else-if="detail">
        <ElDescriptions
          :column="1"
          border
        >
          <ElDescriptionsItem label="模块">
            {{ detail.module }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="业务类型">
            {{ businessLabel(detail.businessType) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="操作人">
            {{ detail.operator }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="租户 ID">
            {{ detail.tenantId }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="请求">
            {{ detail.requestMethod }} {{ detail.url }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="方法">
            {{ detail.method }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="IP">
            {{ detail.ip ?? '—' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="耗时">
            {{ detail.costTime ?? 0 }} ms
          </ElDescriptionsItem>
          <ElDescriptionsItem label="结果">
            {{ detail.status === 1 ? '成功' : '失败' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem
            v-if="detail.errorMsg"
            label="异常信息"
          >
            {{ detail.errorMsg }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="TraceId">
            {{ detail.traceId ?? '—' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="操作时间">
            {{ dateTime(detail.operTime) }}
          </ElDescriptionsItem>
        </ElDescriptions>

        <!-- 入参/出参原样铺:敏感键在落库前已由后端 SensitiveDataMasker 替成 ******,前端不再二次处理 -->
        <div class="payload">
          <div class="payload-title">
            请求参数
          </div>
          <pre class="payload-body">{{ detail.param ?? '—' }}</pre>
        </div>
        <div class="payload">
          <div class="payload-title">
            返回结果
          </div>
          <pre class="payload-body">{{ detail.result ?? '—' }}</pre>
        </div>
      </template>
    </ElDrawer>
  </div>
</template>

<style scoped>
.payload {
  margin-top: 16px;
}

.payload-title {
  margin-bottom: 6px;
  color: var(--app-muted);
  font-size: 12px;
}

.payload-body {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--app-line);
  border-radius: 10px;
  background: var(--app-soft);
  color: var(--app-ink);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
