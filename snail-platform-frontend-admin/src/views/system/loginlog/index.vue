<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import AppIcon from '@/components/AppIcon.vue';
import {
  exportLoginLogs,
  pageLoginLogs,
  type LoginLogQuery,
  type LoginLogVO,
} from '@/api/log';
import { usePageList } from '@/composables/usePageList';
import { useAuthStore } from '@/stores/auth';
import { dateTime } from '@/utils/format';

const auth = useAuthStore();

const query = reactive<LoginLogQuery>({
  username: '',
  ip: '',
  status: null,
});

/** 时间区间只作 UI 状态,提交前拆成 beginTime/endTime 两个 ISO 局部日期时间参数。 */
const range = ref<string[]>([]);

/** 租户 ID 只有持 platform:loginlog:all 才生效,输入框用字符串承接、按需转数。 */
const tenantInput = ref('');

function tenantIdOf(): number | null {
  const raw = tenantInput.value.trim();
  return raw === '' ? null : Number(raw);
}

const { rows, total, loading, pageNum, pageSize, refresh, search } = usePageList<LoginLogVO>(
  (page) =>
    pageLoginLogs({
      ...query,
      tenantId: tenantIdOf(),
      beginTime: range.value[0],
      endTime: range.value[1],
      ...page,
    }),
  20,
);

onMounted(refresh);

function reset(): void {
  query.username = '';
  query.ip = '';
  query.status = null;
  tenantInput.value = '';
  range.value = [];
  void search();
}

const exporting = ref(false);

async function exportLogs(): Promise<void> {
  exporting.value = true;
  try {
    await exportLoginLogs({
      ...query,
      tenantId: tenantIdOf(),
      beginTime: range.value[0],
      endTime: range.value[1],
    });
    ElMessage.success('已开始下载');
  } finally {
    exporting.value = false;
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
      </div>
      <div class="head-actions">
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
            style="width: 200px"
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
            style="width: 180px"
            @keyup.enter="search"
          />
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
          v-if="auth.has('platform:loginlog:all')"
          label="租户 ID"
        >
          <ElInput
            v-model="tenantInput"
            clearable
            placeholder="留空跨租户"
            style="width: 150px"
          />
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
        </div>
      </template>

      <ElTable
        v-loading="loading"
        :data="rows"
        row-key="id"
      >
        <ElTableColumn
          label="登录时间"
          min-width="160"
        >
          <template #default="{ row }">
            {{ dateTime(row.loginTime) }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          prop="username"
          label="登录名"
          min-width="120"
        />
        <ElTableColumn
          label="租户 ID"
          width="90"
          align="center"
        >
          <template #default="{ row }">
            {{ row.tenantId }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="IP"
          min-width="130"
        >
          <template #default="{ row }">
            {{ row.ip ?? '—' }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="浏览器"
          min-width="120"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ row.browser ?? '—' }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="系统"
          min-width="110"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ row.os ?? '—' }}
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
          label="说明"
          min-width="160"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ row.msg ?? '—' }}
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
