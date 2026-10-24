<script setup lang="ts">
import { onMounted, reactive } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import AppIcon from '@/components/AppIcon.vue';
import { kickOnline, pageOnline, type OnlineQuery, type OnlineSession } from '@/api/online';
import { usePageList } from '@/composables/usePageList';
import { useAuthStore } from '@/stores/auth';
import { dateTime } from '@/utils/format';

const auth = useAuthStore();

const query = reactive<OnlineQuery>({ keyword: '' });

const { rows, total, loading, pageNum, pageSize, refresh, search } = usePageList<OnlineSession>(
  (page) => pageOnline({ ...query, ...page }),
  20,
);

onMounted(refresh);

function reset(): void {
  query.keyword = '';
  void search();
}

async function kick(row: OnlineSession): Promise<void> {
  await ElMessageBox.confirm(
    `将强制下线 ${row.username ?? row.userId} 的全部会话,确定执行?`,
    '强制下线',
    { type: 'warning' },
  );
  await kickOnline(row.userId);
  ElMessage.success('已强制下线');
  await refresh();
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="eyebrow">
          ONLINE USERS
        </div>
        <h1>在线用户</h1>
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
            v-model="query.keyword"
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
          <h3>在线会话 <span>共 {{ total }} 条(会话 30 分钟无活动自动下线)</span></h3>
        </div>
      </template>

      <ElTable
        v-loading="loading"
        :data="rows"
        row-key="userId"
      >
        <ElTableColumn
          prop="userId"
          label="用户 ID"
          width="100"
        />
        <ElTableColumn
          prop="username"
          label="登录名"
          min-width="150"
          show-overflow-tooltip
        />
        <ElTableColumn
          prop="ip"
          label="最近活跃 IP"
          width="150"
          show-overflow-tooltip
        />
        <ElTableColumn
          prop="userAgent"
          label="终端"
          min-width="240"
          show-overflow-tooltip
        />
        <ElTableColumn
          prop="firstSeen"
          label="在线起点"
          width="180"
        >
          <template #default="{ row }">
            {{ dateTime(row.firstSeen) }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          prop="lastActive"
          label="最近活跃"
          width="180"
        >
          <template #default="{ row }">
            {{ dateTime(row.lastActive) }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="操作"
          width="120"
          fixed="right"
        >
          <template #default="{ row }">
            <ElButton
              v-if="row.userId !== auth.user?.userId"
              v-permission="'system:online:kick'"
              link
              type="danger"
              @click="kick(row as OnlineSession)"
            >
              强制下线
            </ElButton>
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
  </div>
</template>
