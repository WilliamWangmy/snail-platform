<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
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
import { copyText, generatePassword, validatePassword } from '@/utils/password';

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
  remark: '',
  adminUsername: '',
  adminPassword: '',
};

const formVisible = ref(false);
const formRef = ref<FormInstance>();
const editingId = ref<number | null>(null);
const form = reactive<TenantForm>({ ...emptyForm });
const saving = ref(false);

/** 密码强度口径与后端 PasswordPolicy 同值,由 validatePassword 单点承载 */
function passwordValidator(_rule: unknown, value: string, callback: (error?: Error) => void): void {
  const message = validatePassword(value);
  callback(message ? new Error(message) : undefined);
}

function generateAdminPassword(): void {
  form.adminPassword = generatePassword();
}

async function copyAdminPassword(): Promise<void> {
  if (!form.adminPassword) return;
  if (await copyText(form.adminPassword)) {
    ElMessage.success('已复制到剪贴板');
  } else {
    ElMessage.error('复制失败,请手动复制');
  }
}

const rules: FormRules<TenantForm> = {
  tenantCode: [
    { required: true, message: '请输入租户标识', trigger: 'blur' },
    { min: 2, max: 64, message: '租户标识长度 2-64', trigger: 'blur' },
    {
      pattern: /^[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?$/,
      message: '租户标识只允许小写字母、数字、点、下划线与连字符,首尾须为字母或数字',
      trigger: 'blur',
    },
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
    { pattern: /^[A-Za-z0-9_.@-]+$/, message: '登录名只允许字母、数字、下划线、点、@、连字符', trigger: 'blur' },
  ],
  adminPassword: [
    { required: true, message: '请输入管理员初始密码', trigger: 'blur' },
    { validator: passwordValidator, trigger: 'blur' },
  ],
  remark: [{ max: 500, message: '备注过长', trigger: 'blur' }],
};

function openCreate(): void {
  editingId.value = null;
  Object.assign(form, emptyForm);
  formVisible.value = true;
}

/** 编辑回填:租户标识只读展示(后端修改载荷里没有它),管理员两栏清空——同一段模板只在开通态渲染。 */
function openEdit(row: TenantVO): void {
  editingId.value = row.tenantId;
  Object.assign(form, {
    tenantCode: row.tenantCode,
    name: row.name,
    contactUser: row.contactUser ?? '',
    contactPhone: row.contactPhone ?? '',
    expireTime: row.expireTime ?? '',
    accountLimit: row.accountLimit,
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
    // 空输入折成 null:后端把 null 读成"清空/不限",空串会被原样存进联系人这类可空列
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
      // 修改是整份提交:表单回填了现值,所以"留空即清空"在这里只在用户真删了内容时发生
      // status 不在载荷里:启停只走独立端点,弹窗里放开关等于给高危动作开第二条无确认的通路
      await updateTenant(editingId.value, common);
      ElMessage.success('已保存修改');
    }
    formVisible.value = false;
    await refresh();
  } finally {
    saving.value = false;
  }
}

/** 开通态只许选未来到期时间:整天过去的日期在这里就点不亮,分钟级仍由后端 @Future 兜住。
 *  修改态不限制——把到期时间改成过去是让租户即刻失效的正路。 */
function isPastDay(date: Date): boolean {
  const startOfToday = new Date();
  startOfToday.setHours(0, 0, 0, 0);
  return date.getTime() < startOfToday.getTime();
}

/* --------------------------------- 启停 ----------------------------------- */

/**
 * 启停走独立端点,弹窗里没有第二条通路,停用必然过这道确认。
 * 成功后就地翻牌,不重拉整页:列表按租户 ID 倒序,一次启停不改排序,省掉一次请求与表格闪动。
 */
async function toggleStatus(row: TenantVO): Promise<void> {
  const next = row.status === 0 ? 1 : 0;
  if (next === 1) {
    const confirmed = await ElMessageBox.confirm(
      `停用租户 ${row.name}?该租户下全部账号立即下线;重新启用也不会复活旧令牌,各自需重新登录。`,
      '停用确认',
      { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' },
    )
      .then(() => true)
      .catch(() => false);
    if (!confirmed) return;
  }
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
            placeholder="8-20 位,四类字符全含"
          >
            <template #append>
              <ElButton @click="generateAdminPassword">
                生成
              </ElButton>
              <ElButton @click="copyAdminPassword">
                复制
              </ElButton>
            </template>
          </ElInput>
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
          <!-- value-format 给的是 ISO 本地时间串,后端 LocalDateTime 直接绑定;清空即提交 null=不限期 -->
          <ElDatePicker
            v-model="form.expireTime"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            :disabled-date="editingId === null ? isPastDay : undefined"
            placeholder="留空不限"
            class="full"
          />
        </ElFormItem>
        <ElFormItem
          label="账号上限"
          prop="accountLimit"
        >
          <!-- min=1 是有意的:后端只认 null 为不限,0 会被当成"一个账号都不许建" -->
          <ElInputNumber
            v-model="form.accountLimit"
            :min="1"
            :precision="0"
            controls-position="right"
            placeholder="留空不限"
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
