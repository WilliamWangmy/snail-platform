<script setup lang="ts">
import { onMounted, reactive } from 'vue';
import AppIcon from '@/components/AppIcon.vue';
import { pagePlatformFiles, type FileQuery, type FileVO } from '@/api/file';
import { usePageList } from '@/composables/usePageList';
import { dateTime, fileSize } from '@/utils/format';

const query = reactive<FileQuery>({ fileName: '', bizType: '' });

const { rows, total, loading, pageNum, pageSize, refresh, search } = usePageList<FileVO>(
  (page) => pagePlatformFiles({ ...query, ...page }),
  20,
);

onMounted(refresh);

function reset(): void {
  query.fileName = '';
  query.bizType = '';
  void search();
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="eyebrow">
          PLATFORM FILES
        </div>
        <h1>平台文件</h1>
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
        <ElFormItem label="文件名">
          <ElInput
            v-model="query.fileName"
            clearable
            placeholder="模糊匹配"
            style="width: 220px"
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
        <ElFormItem label="业务域">
          <ElInput
            v-model="query.bizType"
            clearable
            placeholder="精确匹配,如 contract"
            style="width: 180px"
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
          <h3>跨租户文件检索 <span>共 {{ total }} 条</span></h3>
        </div>
      </template>

      <ElTable
        v-loading="loading"
        :data="rows"
        row-key="fileId"
      >
        <ElTableColumn
          prop="tenantId"
          label="租户 ID"
          width="100"
        />
        <ElTableColumn
          prop="fileName"
          label="文件名"
          min-width="240"
          show-overflow-tooltip
        />
        <ElTableColumn
          label="类型"
          width="90"
          align="center"
        >
          <template #default="{ row }">
            {{ (row as FileVO).ext ?? '—' }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="大小"
          width="110"
          align="right"
        >
          <template #default="{ row }">
            {{ fileSize((row as FileVO).size) }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="业务域"
          width="120"
        >
          <template #default="{ row }">
            {{ (row as FileVO).bizType ?? '—' }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="上传人"
          min-width="110"
        >
          <template #default="{ row }">
            {{ (row as FileVO).uploadByName ?? '—' }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="上传时间"
          width="180"
        >
          <template #default="{ row }">
            {{ dateTime((row as FileVO).createTime) }}
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
