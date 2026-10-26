<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import AppIcon from '@/components/AppIcon.vue';
import RowActions, { type RowAction } from '@/components/RowActions.vue';
import {
  deleteFile,
  downloadFile,
  pageFiles,
  uploadFile,
  type FileQuery,
  type FileVO,
} from '@/api/file';
import { usePageList } from '@/composables/usePageList';
import { useAuthStore } from '@/stores/auth';
import { dateTime, fileSize } from '@/utils/format';

const auth = useAuthStore();

const query = reactive<FileQuery>({ fileName: '', bizType: '' });

const { rows, total, loading, pageNum, pageSize, refresh, search } = usePageList<FileVO>(
  (page) => pageFiles({ ...query, ...page }),
  20,
);

onMounted(refresh);

function reset(): void {
  query.fileName = '';
  query.bizType = '';
  void search();
}

/* --------------------------------- 上传 ----------------------------------- */

/** 上传走文件管理页内按钮 + 隐藏 input,通用上传组件等首个业务消费方出现再做。 */
const fileInput = ref<HTMLInputElement | null>(null);
const uploading = ref(false);

function pickFile(): void {
  fileInput.value?.click();
}

async function onFileChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;
  uploading.value = true;
  try {
    await uploadFile(file);
    ElMessage.success('已上传');
    await refresh();
  } finally {
    uploading.value = false;
  }
}

/* ----------------------------- 下载与删除 --------------------------------- */

async function handleDownload(row: FileVO): Promise<void> {
  await downloadFile(row);
  ElMessage.success('已开始下载');
}

async function handleRemove(row: FileVO): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    `删除文件 ${row.fileName}?逻辑删除后对象内容尽力清理,不可恢复。`,
    '删除确认',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
  )
    .then(() => true)
    .catch(() => false);
  if (!confirmed) return;
  await deleteFile(row.fileId);
  ElMessage.success('已删除');
  await refresh();
}

function rowActions(row: FileVO): RowAction[] {
  return [
    { key: 'download', label: '下载', handler: () => void handleDownload(row) },
    {
      key: 'delete',
      label: '删除',
      tone: 'danger',
      show: auth.has('system:file:delete'),
      handler: () => void handleRemove(row),
    },
  ];
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="eyebrow">
          FILES
        </div>
        <h1>文件管理</h1>
      </div>
      <div class="head-actions">
        <ElButton
          v-permission="'system:file:upload'"
          type="primary"
          :loading="uploading"
          @click="pickFile"
        >
          <AppIcon
            name="plus"
            :size="15"
          />
          上传文件
        </ElButton>
      </div>
    </div>

    <input
      ref="fileInput"
      type="file"
      hidden
      @change="onFileChange"
    >

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
          <h3>文件记录 <span>共 {{ total }} 条</span></h3>
        </div>
      </template>

      <ElTable
        v-loading="loading"
        :data="rows"
        row-key="fileId"
      >
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
        <ElTableColumn
          label="操作"
          width="140"
          align="center"
          fixed="right"
        >
          <template #default="{ row }">
            <RowActions :actions="rowActions(row as FileVO)" />
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
