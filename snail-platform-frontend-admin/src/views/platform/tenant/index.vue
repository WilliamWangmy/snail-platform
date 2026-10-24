<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
import AppIcon from '@/components/AppIcon.vue';
import {
  changeTenantStatus,
  createTenant,
  pageTenants,
  updateTenant,
  type TenantQuery,
  type TenantVO,
} from '@/api/tenant';
import { usePageList } from '@/composables/usePageList';
import { dateTime } from '@/utils/format';

const query = reactive<TenantQuery>({ name: '', status: null });
const { rows, total, loading, pageNum, pageSize, refresh, search } = usePageList<TenantVO>((page) =>
  pageTenants({ ...query, ...page }),
);

onMounted(refresh);

function reset(): void {
  query.name = '';
  query.status = null;
  void search();
}

/* -------------------------------- 开通 / 修改 ------------------------------- */

interface TenantForm {
  tenantCode: string;
  name: string;
  contactUser: string;
  contactPhone: string;
  expireTime: string;
  accountLimit: number | null;
  status: number;
  remark: string;
  adminUsername: string;
  adminPassword: string;
}

const emptyForm: TenantForm = {
  tenantCode: '',
  name: '',
  contactUser: '',
  contactPhone: '',
  expireTime: '',
  accountLimit: null,
  status: 0,
  remark: '',
  adminUsername: '',
  adminPassword: '',
};

const formVisible = ref(false);
const formRef = ref<FormInstance>();
const editingId = ref<number | null>(null);
const form = reactive<TenantForm>({ ...emptyForm });
const saving = ref(false);

const rules: FormRules<TenantForm> = {
  tenantCode: [
    { required: true, message: '请输入租户标识', trigger: 'blur' },
    { min: 2, max: 64, message: '租户标识长度 2-64', trigger: 'blur' },
  ],
  name: [
    { required: true, message: '请输入租户名称', trigger: 'blur' },
    { max: 50, message: '租户名称过长', trigger: 'blur' },
  ],
  contactUser: [{ max: 30, message: '联系人过长', trigger: 'blur' }],
  contactPhone: [{ max: 20, message: '联系电话过长', trigger: 'blur' }],
  adminUsername: [
    { required: true, message: '请输入管理员登录名', trigger: 'blur' },
    { min: 3, max: 30, message: '登录名长度 3-30', trigger: 'blur' },
  ],
  adminPassword: [
    { required: true, message: '请输入管理员初始密码', trigger: 'blur' },
    { min: 8, max: 30, message: '密码长度 8-30', trigger: 'blur' },
  ],
  remark: [{ max: 500, message: '备注过长', trigger: 'blur' }],
};

function openCreate(): void {
  editingId.value = null;
  Object.assign(form, emptyForm);
  formVisible.value = true;
}

function openEdit(row: TenantVO): void {
  editingId.value = row.tenantId;
  Object.assign(form, {
    tenantCode: row.tenantCode,
    name: row.name,
    contactUser: row.contactUser ?? '',
    contactPhone: row.contactPhone ?? '',
    expireTime: row.expireTime ?? '',
    accountLimit: row.accountLimit,
    status: row.status,
    remark: row.remark ?? '',
    adminUsername: '',
    adminPassword: '',
  });
  formVisible.value = true;
}

async function submitForm(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) return;
  saving.value = true;
  try {
    const text = (value: string): string | null => value.trim() || null;
    const common = {
      name: form.name.trim(),
      contactUser: text(form.contactUser),
      contactPhone: text(form.contactPhone),
      expireTime: form.expireTime || null,
      accountLimit: form.accountLimit,
      remark: text(form.remark),
    };
    if (editingId.value === null) {
      await createTenant({
        tenantCode: form.tenantCode.trim(),
        adminUsername: form.adminUsername.trim(),
        adminPassword: form.adminPassword,
        ...common,
      });
      ElMessage.success('租户已开通');
    } else {
      await updateTenant(editingId.value, { status: form.status, ...common });
      ElMessage.success('已保存修改');
    }
    formVisible.value = false;
    await refresh();
  } finally {
    saving.value = false;
  }
}

/* --------------------------------- 启停 ----------------------------------- */

async function toggleStatus(row: TenantVO): Promise<void> {
  const next = row.status === 0 ? 1 : 0;
  await changeTenantStatus(row.tenantId, next);
  row.status = next;
  ElMessage.success(next === 0 ? '已启用' : '已停用');
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="eyebrow">
          TENANTS
        </div>
        <h1>租户管理</h1>
      </div>
      <div class="head-actions">
        <ElButton
          v-permission="'platform:tenant:create'"
          type="primary"
          @click="openCreate"
        >
          <AppIcon
            name="plus"
            :size="15"
          />
          开通租户
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
        <ElFormItem label="租户名称">
          <ElInput
            v-model="query.name"
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
        <ElFormItem label="状态">
          <ElSelect
            v-model="query.status"
            clearable
            placeholder="不限"
            style="width: 120px"
          >
            <ElOption
              label="正常"
              :value="0"
            />
            <ElOption
              label="停用"
              :value="1"
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
          <h3>租户列表 <span>共 {{ total }} 个</span></h3>
        </div>
      </template>

      <ElTable
        v-loading="loading"
        :data="rows"
        row-key="tenantId"
      >
        <ElTableColumn
          prop="tenantCode"
          label="租户标识"
          min-width="120"
        />
        <ElTableColumn
          prop="name"
          label="租户名称"
          min-width="160"
        />
        <ElTableColumn
          label="联系人"
          min-width="110"
        >
          <template #default="{ row }">
            {{ row.contactUser ?? '—' }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="联系电话"
          min-width="130"
        >
          <template #default="{ row }">
            {{ row.contactPhone ?? '—' }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="到期时间"
          min-width="160"
        >
          <template #default="{ row }">
            {{ row.expireTime ? dateTime(row.expireTime) : '不限' }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="账号上限"
          width="100"
          align="right"
        >
          <template #default="{ row }">
            {{ row.accountLimit ?? '不限' }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="状态"
          width="96"
          align="center"
        >
          <template #default="{ row }">
            <span
              class="status-pill"
              :class="row.status === 0 ? 'success' : 'info'"
            >
              {{ row.status === 0 ? '正常' : '停用' }}
            </span>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="操作"
          width="150"
          align="center"
          fixed="right"
        >
          <template #default="{ row }">
            <ElButton
              v-permission="'platform:tenant:update'"
              link
              type="primary"
              @click="openEdit(row as TenantVO)"
            >
              修改
            </ElButton>
            <ElButton
              v-permission="'platform:tenant:update'"
              link
              type="primary"
              @click="toggleStatus(row as TenantVO)"
            >
              {{ row.status === 0 ? '停用' : '启用' }}
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
          :page-sizes="[10, 20, 50, 100]"
          layout="sizes, prev, pager, next"
          @change="refresh"
        />
      </div>
    </ElCard>

    <ElDialog
      v-model="formVisible"
      :title="editingId === null ? '开通租户' : '修改租户'"
      width="560px"
    >
      <ElForm
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="108px"
      >
        <ElFormItem
          label="租户标识"
          prop="tenantCode"
        >
          <ElInput
            v-model="form.tenantCode"
            :disabled="editingId !== null"
            placeholder="建后不可修改"
          />
        </ElFormItem>
        <ElFormItem
          label="租户名称"
          prop="name"
        >
          <ElInput v-model="form.name" />
        </ElFormItem>
        <ElFormItem
          v-if="editingId === null"
          label="管理员登录名"
          prop="adminUsername"
        >
          <ElInput
            v-model="form.adminUsername"
            placeholder="全站唯一"
          />
        </ElFormItem>
        <ElFormItem
          v-if="editingId === null"
          label="管理员密码"
          prop="adminPassword"
        >
          <ElInput
            v-model="form.adminPassword"
            type="password"
            show-password
            placeholder="8-30 位"
          />
        </ElFormItem>
        <ElFormItem
          label="联系人"
          prop="contactUser"
        >
          <ElInput v-model="form.contactUser" />
        </ElFormItem>
        <ElFormItem
          label="联系电话"
          prop="contactPhone"
        >
          <ElInput v-model="form.contactPhone" />
        </ElFormItem>
        <ElFormItem
          label="到期时间"
          prop="expireTime"
        >
          <ElDatePicker
            v-model="form.expireTime"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="留空不限"
            class="full"
          />
        </ElFormItem>
        <ElFormItem
          label="账号上限"
          prop="accountLimit"
        >
          <ElInputNumber
            v-model="form.accountLimit"
            :min="1"
            :precision="0"
            controls-position="right"
            placeholder="留空不限"
          />
        </ElFormItem>
        <ElFormItem
          v-if="editingId !== null"
          label="状态"
        >
          <ElSwitch
            v-model="form.status"
            :active-value="0"
            :inactive-value="1"
            inline-prompt
            active-text="正常"
            inactive-text="停用"
          />
        </ElFormItem>
        <ElFormItem
          label="备注"
          prop="remark"
        >
          <ElInput
            v-model="form.remark"
            type="textarea"
            :rows="2"
            maxlength="500"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="formVisible = false">
          取消
        </ElButton>
        <ElButton
          type="primary"
          :loading="saving"
          @click="submitForm"
        >
          保存
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<style scoped>
.full {
  width: 100%;
}
</style>
