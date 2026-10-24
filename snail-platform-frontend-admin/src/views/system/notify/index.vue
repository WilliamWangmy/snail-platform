<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import AppIcon from '@/components/AppIcon.vue';
import RowActions, { type RowAction } from '@/components/RowActions.vue';
import {
  fetchUnreadCount,
  markAllRead,
  markRead,
  pageInbox,
  removeInboxItem,
  type NotifyInboxItem,
  type NotifyInboxQuery,
} from '@/api/notify';
import { usePageList } from '@/composables/usePageList';
import { dateTime } from '@/utils/format';


const query = reactive<NotifyInboxQuery>({ readStatus: null, bizType: '' });

const { rows, total, loading, pageNum, pageSize, refresh, search } = usePageList<NotifyInboxItem>(
  (page) => pageInbox({ ...query, ...page }),
  20,
);

/** 本人未读数,进页与动作后刷新 */
const unread = ref(0);

async function reloadUnread(): Promise<void> {
  unread.value = await fetchUnreadCount();
}

onMounted(() => {
  void refresh();
  void reloadUnread();
});

function reset(): void {
  query.readStatus = null;
  query.bizType = '';
  void search();
}

const detailVisible = ref(false);
const detail = ref<NotifyInboxItem | null>(null);

/** 看详情即视为已读:未读行先标已读再展开 */
async function openDetail(row: NotifyInboxItem): Promise<void> {
  detail.value = row;
  detailVisible.value = true;
  if (row.readStatus === 0) {
    await markRead(row.recipientId);
    row.readStatus = 1;
    await reloadUnread();
  }
}

/** 行操作:未读才出"标为已读",移出恒在。 */
function actionsOf(row: NotifyInboxItem): RowAction[] {
  return [
    {
      key: 'read',
      label: '标为已读',
      show: row.readStatus === 0,
      handler: () => void handleRead(row),
    },
    {
      key: 'remove',
      label: '移出',
      tone: 'danger',
      handler: () => void handleRemove(row),
    },
  ];
}

async function handleRead(row: NotifyInboxItem): Promise<void> {
  await markRead(row.recipientId);
  row.readStatus = 1;
  await reloadUnread();
}

async function handleRemove(row: NotifyInboxItem): Promise<void> {
  await ElMessageBox.confirm('移出后将从收件箱消失,确定移出该条通知?', '移出通知', { type: 'warning' });
  await removeInboxItem(row.recipientId);
  ElMessage.success('已移出');
  await refresh();
  await reloadUnread();
}

const markingAll = ref(false);

async function handleMarkAll(): Promise<void> {
  markingAll.value = true;
  try {
    await markAllRead();
    ElMessage.success('已全部标记为已读');
    await refresh();
    await reloadUnread();
  } finally {
    markingAll.value = false;
  }
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="eyebrow">
          NOTIFICATIONS
        </div>
        <h1>通知中心</h1>
      </div>
      <div class="head-actions">
        <ElButton
          v-permission="'system:notify:list'"
          plain
          :disabled="unread === 0"
          :loading="markingAll"
          @click="handleMarkAll"
        >
          <AppIcon
            name="check"
            :size="15"
          />
          全部已读{{ unread > 0 ? `(${unread})` : '' }}
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
        <ElFormItem label="已读状态">
          <ElSelect
            v-model="query.readStatus"
            clearable
            placeholder="不限"
            style="width: 120px"
          >
            <ElOption
              label="未读"
              :value="0"
            />
            <ElOption
              label="已读"
              :value="1"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="业务标签">
          <ElInput
            v-model="query.bizType"
            clearable
            placeholder="如 announcement"
            style="width: 170px"
            @keyup.enter="search"
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
          <h3>收件箱 <span>共 {{ total }} 条</span></h3>
        </div>
      </template>

      <ElTable
        v-loading="loading"
        :data="rows"
        row-key="recipientId"
        @row-click="openDetail"
      >
        <ElTableColumn width="46">
          <template #default="{ row }">
            <span
              v-if="row.readStatus === 0"
              class="unread-dot"
            ></span>
          </template>
        </ElTableColumn>
        <ElTableColumn
          prop="title"
          label="标题"
          min-width="260"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <strong>{{ row.title }}</strong>
          </template>
        </ElTableColumn>
        <ElTableColumn
          prop="senderName"
          label="发送人"
          width="140"
          show-overflow-tooltip
        />
        <ElTableColumn
          prop="bizType"
          label="业务标签"
          width="130"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ row.bizType ?? '—' }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          prop="sendTime"
          label="发送时间"
          width="180"
        >
          <template #default="{ row }">
            {{ dateTime(row.sendTime) }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="状态"
          width="100"
        >
          <template #default="{ row }">
            <span
              class="status-pill"
              :class="row.readStatus === 1 ? 'info' : 'warning'"
            >{{ row.readStatus === 1 ? '已读' : '未读' }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="操作"
          width="130"
          fixed="right"
        >
          <template #default="{ row }">
            <RowActions :actions="actionsOf(row as NotifyInboxItem)" />
          </template>
        </ElTableColumn>
      </ElTable>

      <div class="table-footer">
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
      v-model="detailVisible"
      :title="detail?.title"
      size="480px"
    >
      <div
        v-if="detail"
        class="notify-detail"
      >
        <p class="notify-meta">
          <span>{{ detail.senderName ?? '系统通知' }}</span>
          <span>{{ dateTime(detail.sendTime) }}</span>
        </p>
        <p class="notify-content">
          {{ detail.content }}
        </p>
      </div>
    </ElDrawer>
  </div>
</template>

<style scoped>
.unread-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--el-color-danger);
}

.notify-detail {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.notify-meta {
  display: flex;
  justify-content: space-between;
  margin: 0;
  color: var(--app-text-secondary, #909399);
  font-size: 13px;
}

.notify-content {
  margin: 0;
  white-space: pre-wrap;
  line-height: 1.7;
}
</style>
