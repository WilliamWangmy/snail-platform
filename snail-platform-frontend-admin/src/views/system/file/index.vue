<script setup lang="ts">
/** 组件名与路由 meta.viewName 对应,多页签 KeepAlive include 依此缓存。 */
defineOptions({ name: 'SystemFile' });
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import AppIcon from '@/components/AppIcon.vue';
import FilePreviewDialog from '@/components/FilePreviewDialog.vue';
import RowActions, { type RowAction } from '@/components/RowActions.vue';
import {
  deleteFile,
  downloadFile,
  fetchFileBlob,
  pageFiles,
  uploadFile,
  type FileQuery,
  type FileVO,
} from '@/api/file';
import { fetchDictItems, type DictItem } from '@/api/dict';
import { usePageList } from '@/composables/usePageList';
import { useAuthStore } from '@/stores/auth';
import { dateTime, fileSize } from '@/utils/format';

const auth = useAuthStore();

const query = reactive<FileQuery>({ fileName: '', bizType: '', fileType: '' });

const { rows, total, loading, pageNum, pageSize, refresh, search } = usePageList<FileVO>(
  (page) => pageFiles({ ...query, ...page }),
  20,
);

/* ------------------------------ 文件类型字典 ------------------------------- */

/** 文件类型由内置字典 sys_file_type 维护:可增字典值,内置类型与数据项不可删除 */
const fileTypeItems = ref<DictItem[]>([]);

const fileTypeLabel = computed<Record<string, string>>(() => {
  const map: Record<string, string> = {};
  for (const item of fileTypeItems.value) map[item.value] = item.label;
  return map;
});

async function loadFileTypeItems(): Promise<void> {
  fileTypeItems.value = await fetchDictItems('sys_file_type');
}

onMounted(() => {
  void refresh();
  void loadFileTypeItems();
});

function fileTypeText(value: string): string {
  return fileTypeLabel.value[value] ?? value;
}

function reset(): void {
  query.fileName = '';
  query.bizType = '';
  query.fileType = '';
  void search();
}

/* --------------------------------- 上传 ----------------------------------- */

/** 上传先选文件类型再选文件:类型取内置字典下拉,缺省 平台文件(platform) */
const uploadDialogVisible = ref(false);
const uploadForm = reactive<{ fileType: string }>({ fileType: 'platform' });
const pendingFile = ref<File | null>(null);
const uploading = ref(false);

const fileInput = ref<HTMLInputElement | null>(null);

function openUploadDialog(): void {
  // 字典值建后不可改,platform(平台文件)是内置默认项;若被停用则落到第一个可用项
  const hasDefault = fileTypeItems.value.some((item) => item.value === uploadForm.fileType);
  if (!hasDefault && fileTypeItems.value.length > 0) {
    uploadForm.fileType = fileTypeItems.value[0]!.value;
  }
  pendingFile.value = null;
  uploadDialogVisible.value = true;
}

function pickFile(): void {
  fileInput.value?.click();
}

function onFileChange(event: Event): void {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (file) pendingFile.value = file;
}

function clearPendingFile(): void {
  pendingFile.value = null;
}

async function confirmUpload(): Promise<void> {
  if (!pendingFile.value) {
    ElMessage.warning('请先选择要上传的文件');
    return;
  }
  uploading.value = true;
  try {
    await uploadFile(pendingFile.value, uploadForm.fileType);
    ElMessage.success('已上传');
    uploadDialogVisible.value = false;
    await refresh();
  } finally {
    uploading.value = false;
  }
}

/* --------------------------------- 预览 ----------------------------------- */

const previewVisible = ref(false);
const previewLoading = ref(false);
const previewName = ref('');
const previewFile = ref<File | null>(null);

/** 先开窗再取字节:大文件取回期间弹窗内有 loading,不是点了没反应。 */
async function handlePreview(row: FileVO): Promise<void> {
  previewName.value = row.fileName;
  previewFile.value = null;
  previewLoading.value = true;
  previewVisible.value = true;
  try {
    const blob = await fetchFileBlob(row.fileId);
    previewFile.value = new File([blob], row.fileName, { type: blob.type });
  } catch {
    // 拒绝理由 http 层已提示,这里只收窗
    previewVisible.value = false;
  } finally {
    previewLoading.value = false;
  }
}

function closePreview(): void {
  previewVisible.value = false;
  previewFile.value = null;
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
    {
      key: 'preview',
      label: '预览',
      // 预览要取原始字节,与下载同一权限口径
      show: auth.has('system:file:download'),
      handler: () => void handlePreview(row),
    },
    {
      key: 'download',
      label: '下载',
      show: auth.has('system:file:download'),
      handler: () => void handleDownload(row),
    },
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
          @click="openUploadDialog"
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
    />

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
        <ElFormItem label="文件类型">
          <ElSelect
            v-model="query.fileType"
            clearable
            placeholder="全部类型"
            style="width: 160px"
          >
            <ElOption
              v-for="item in fileTypeItems"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </ElSelect>
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
          label="文件类型"
          width="110"
        >
          <template #default="{ row }">
            {{ fileTypeText((row as FileVO).fileType) }}
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
          width="170"
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

    <FilePreviewDialog
      :visible="previewVisible"
      :file-name="previewName"
      :file="previewFile"
      :loading="previewLoading"
      @closed="closePreview"
    />

    <ElDialog
      v-model="uploadDialogVisible"
      title="上传文件"
      width="640px"
    >
      <div class="form-banner">
        <AppIcon
          name="alert"
          :size="14"
        />
        <span>文件类型由内置字典维护,选项可在字典管理页扩充;不选择文件类型时默认归入 平台文件</span>
      </div>
      <ElForm
        :model="uploadForm"
        label-position="top"
      >
        <div class="form-panel">
          <div class="panel-head">
            <span class="panel-ico">
              <AppIcon
                name="receipt"
                :size="13"
              />
            </span>
            <span class="panel-title">上传内容</span>
            <span class="panel-hint">扩展名须在白名单内,超限会被后端拒绝</span>
          </div>
          <ElFormItem label="文件类型">
            <ElSelect v-model="uploadForm.fileType">
              <ElOption
                v-for="item in fileTypeItems"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="文件">
            <button
              class="upload-zone"
              :class="{ 'has-file': pendingFile }"
              type="button"
              @click="pickFile"
            >
              <template v-if="pendingFile">
                <span class="zone-ico">
                  <AppIcon
                    name="box"
                    :size="18"
                  />
                </span>
                <span class="zone-body">
                  <span class="zone-name">{{ pendingFile.name }}</span>
                  <span class="zone-meta">{{ fileSize(pendingFile.size) }} · 点击可重新选择</span>
                </span>
                <span
                  class="zone-clear"
                  title="移除已选文件"
                  @click.stop="clearPendingFile"
                >
                  <AppIcon
                    name="close"
                    :size="13"
                  />
                </span>
              </template>
              <template v-else>
                <span class="zone-ico">
                  <AppIcon
                    name="plus"
                    :size="18"
                  />
                </span>
                <span class="zone-body">
                  <span class="zone-name">点击选择文件</span>
                  <span class="zone-meta">文件名保持原样,仅做安全清洗</span>
                </span>
              </template>
            </button>
          </ElFormItem>
        </div>
      </ElForm>
      <template #footer>
        <ElButton @click="uploadDialogVisible = false">
          取消
        </ElButton>
        <ElButton
          type="primary"
          :loading="uploading"
          @click="confirmUpload"
        >
          上传
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<style scoped>
/* 文件选区:与系统的虚线新增瓦片同款,选中后转为实心态并亮出移除按钮 */
.upload-zone {
  display: flex;
  align-items: center;
  gap: 11px;
  width: 100%;
  padding: 13px 14px;
  border: 1px dashed rgba(var(--app-primary-rgb), 0.4);
  border-radius: 10px;
  background: rgba(var(--app-primary-rgb), 0.03);
  cursor: pointer;
  text-align: left;
  transition:
    border-color 0.18s,
    background-color 0.18s;
}

.upload-zone:hover {
  border-color: var(--app-primary);
  background: rgba(var(--app-primary-rgb), 0.08);
}

.upload-zone.has-file {
  border-style: solid;
  border-color: var(--app-line);
  background: var(--app-soft, var(--app-bg));
}

.zone-ico {
  display: grid;
  width: 36px;
  height: 36px;
  flex: none;
  place-items: center;
  border-radius: 10px;
  background: rgba(var(--app-primary-rgb), 0.12);
  color: var(--app-primary);
}

.zone-body {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.zone-name {
  overflow: hidden;
  color: var(--app-ink);
  font-size: 13px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.zone-meta {
  color: var(--app-muted);
  font-size: 11px;
}

.zone-clear {
  display: grid;
  width: 26px;
  height: 26px;
  flex: none;
  place-items: center;
  border: 1px solid var(--app-line);
  border-radius: 7px;
  background: var(--app-card);
  color: var(--app-muted);
  transition:
    color 0.16s,
    border-color 0.16s;
}

.zone-clear:hover {
  border-color: rgba(var(--app-danger-rgb), 0.45);
  color: var(--app-danger);
}
</style>
