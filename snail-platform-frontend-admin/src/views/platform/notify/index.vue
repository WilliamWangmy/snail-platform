<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import AppIcon from '@/components/AppIcon.vue';
import { pageAnnouncements, sendAnnouncement, type AnnouncementItem, type AnnouncementQuery } from '@/api/notify';
import { pageTenants, type TenantVO } from '@/api/tenant';
import { usePageList } from '@/composables/usePageList';
import { dateTime } from '@/utils/format';


const query = reactive<AnnouncementQuery>({ title: '' });

const { rows, total, loading, pageNum, pageSize, refresh, search } = usePageList<AnnouncementItem>(
  (page) => pageAnnouncements({ ...query, ...page }),
  20,
);

onMounted(refresh);

function reset(): void {
  query.title = '';
  void search();
}

/* --------------------------------- 发送公告 ---------------------------------- */

const sendVisible = ref(false);
const sending = ref(false);
const sendFormRef = ref();
const sendForm = reactive<{ tenantId: number | null; title: string; content: string }>({
  tenantId: null,
  title: '',
  content: '',
});

const sendRules = {
  tenantId: [{ required: true, message: '请选择目标租户', trigger: 'change' }],
  title: [
    { required: true, message: '请输入公告标题', trigger: 'blur' },
    { max: 100, message: '标题至多 100 字', trigger: 'blur' },
  ],
  content: [
    { required: true, message: '请输入公告正文', trigger: 'blur' },
    { max: 5000, message: '正文至多 5000 字', trigger: 'blur' },
  ],
};

/** 目标租户下拉:一次拉全量启用租户,量级有限不分页 */
const tenantOptions = ref<TenantVO[]>([]);

async function openSend(): Promise<void> {
  sendForm.tenantId = null;
  sendForm.title = '';
  sendForm.content = '';
  const page = await pageTenants({ pageNum: 1, pageSize: 200, status: 0 });
  tenantOptions.value = page.rows;
  sendVisible.value = true;
}

async function submitSend(): Promise<void> {
  await sendFormRef.value?.validate();
  await ElMessageBox.confirm('公告将发送给目标租户全部启用用户且不可撤回,确认发送?', '发送公告', {
    type: 'warning',
  });
  sending.value = true;
  try {
    await sendAnnouncement({
      tenantId: sendForm.tenantId as number,
      title: sendForm.title.trim(),
      content: sendForm.content.trim(),
    });
    ElMessage.success('公告已发送');
    sendVisible.value = false;
    await refresh();
  } finally {
    sending.value = false;
  }
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="eyebrow">
          ANNOUNCEMENTS
        </div>
        <h1>公告管理</h1>
      </div>
      <div class="head-actions">
        <ElButton
          v-permission="'platform:notify:send'"
          type="primary"
          @click="openSend"
        >
          <AppIcon
            name="plus"
            :size="15"
          />
          发送公告
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
        <ElFormItem label="标题">
          <ElInput
            v-model="query.title"
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
          <h3>公告记录 <span>共 {{ total }} 条</span></h3>
        </div>
      </template>

      <ElTable
        v-loading="loading"
        :data="rows"
        row-key="messageId"
      >
        <ElTableColumn
          prop="title"
          label="标题"
          min-width="240"
          show-overflow-tooltip
        />
        <ElTableColumn
          prop="tenantId"
          label="目标租户"
          width="110"
        />
        <ElTableColumn
          prop="recipientCount"
          label="扇出人数"
          width="100"
        />
        <ElTableColumn
          prop="senderName"
          label="发送人"
          width="140"
          show-overflow-tooltip
        />
        <ElTableColumn
          prop="sendTime"
          label="发送时间"
          width="180"
        >
          <template #default="{ row }">
            {{ dateTime(row.sendTime) }}
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

    <ElDialog
      v-model="sendVisible"
      title="发送公告"
      width="560px"
    >
      <ElForm
        ref="sendFormRef"
        :model="sendForm"
        :rules="sendRules"
        label-width="90px"
      >
        <ElFormItem label="目标租户">
          <ElSelect
            v-model="sendForm.tenantId"
            filterable
            placeholder="选择启用中的租户"
            style="width: 100%"
          >
            <ElOption
              v-for="tenant in tenantOptions"
              :key="tenant.tenantId"
              :label="`${tenant.name} (${tenant.tenantCode})`"
              :value="tenant.tenantId"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="标题">
          <ElInput
            v-model="sendForm.title"
            maxlength="100"
            show-word-limit
          />
        </ElFormItem>
        <ElFormItem label="正文">
          <ElInput
            v-model="sendForm.content"
            type="textarea"
            :rows="6"
            maxlength="5000"
            show-word-limit
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="sendVisible = false">
          取消
        </ElButton>
        <ElButton
          type="primary"
          :loading="sending"
          @click="submitSend"
        >
          发送
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>
