<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import AppIcon from '@/components/AppIcon.vue';
import {
  changeTenantStatus,
  checkAdminUsername,
  createTenant,
  pageTenants,
  updateTenant,
  type TenantQuery,
  type TenantVO,
} from '@/api/tenant';
import { usePageList } from '@/composables/usePageList';
import { dateTime } from '@/utils/format';
import { generatePassword, validatePassword } from '@/utils/password';
import PasswordHintInput from '@/components/PasswordHintInput.vue';

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
  /** 首任管理员初始口令(P2-3):平台管理员自输或点生成,仅在开通态提交 */
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

/** 必填口径按需求收紧:开通态除备注外全部必填;修改态保持"留空即清空"的原语义不拦截 */
const rules = computed<FormRules<TenantForm>>(() => ({
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
  contactUser: [
    ...(editingId.value === null ? [{ required: true, message: '请输入联系人', trigger: 'blur' }] : []),
    { max: 30, message: '联系人过长', trigger: 'blur' },
  ],
  contactPhone: [
    ...(editingId.value === null ? [{ required: true, message: '请输入联系电话', trigger: 'blur' }] : []),
    // 与用户管理同口径:前端收紧为固定 11 位数字;后端仍 max 20 不动
    { pattern: /^\d{11}$/, message: '联系电话必须为 11 位数字', trigger: 'blur' },
  ],
  expireTime: editingId.value === null ? [{ required: true, message: '请选择到期时间', trigger: 'change' }] : [],
  accountLimit: editingId.value === null ? [{ required: true, message: '请输入账号上限', trigger: 'blur' }] : [],
  adminUsername: [
    { required: true, message: '请输入管理员登录名', trigger: 'blur' },
    { min: 3, max: 30, message: '登录名长度 3-30', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9_.@-]+$/, message: '登录名只允许字母、数字、下划线、点、@、连字符', trigger: 'blur' },
    {
      // 失焦即查占用(全站口径,含已删行);保存时后端仍会再拦一次,防两人同时提交同名。
      // 用回调式而非 async:EP 的 FormItemRule 校验器签名是同步 void,Promise 返回值过不了类型
      validator: (_rule: unknown, value: string, callback: (error?: Error) => void) => {
        if (!value || !/^[A-Za-z0-9_.@-]+$/.test(value)) {
          callback();
          return;
        }
        checkAdminUsername(value)
          .then((taken) => callback(taken ? new Error('管理员登录名已被占用') : undefined))
          .catch(() => callback());
      },
      trigger: 'blur',
    },
  ],
  adminPassword: [
    { required: true, message: '请输入管理员初始密码', trigger: 'blur' },
    {
      // 强度口径与后端 PasswordPolicy 同值,由 validatePassword 单点承载
      validator: (_rule: unknown, value: string, callback: (error?: Error) => void) => {
        const message = validatePassword(value);
        callback(message ? new Error(message) : undefined);
      },
      trigger: 'blur',
    },
  ],
  remark: [{ max: 500, message: '备注过长', trigger: 'blur' }],
}));

/** 联系电话输入即清洗:剔除非数字并截断到 11 位,粘贴超长内容也会被就地裁掉 */
function sanitizeContactPhone(): void {
  form.contactPhone = form.contactPhone.replace(/\D/g, '').slice(0, 11);
}

/** 复制成功的瞬时反馈:图标短暂变 ✓,1.6s 后还原 */
const copied = ref(false);
let copyTimer: ReturnType<typeof setTimeout> | undefined;

/** 口令敏感,只进剪贴板不落日志;非安全上下文(如 http 内网直连)时降级为手动复制 */
async function copyAdminPassword(): Promise<void> {
  if (!form.adminPassword) {
    ElMessage.warning('还没有可复制的密码,请先点刷新图标生成');
    return;
  }
  try {
    await navigator.clipboard.writeText(form.adminPassword);
    copied.value = true;
    window.clearTimeout(copyTimer);
    copyTimer = window.setTimeout(() => (copied.value = false), 1600);
    ElMessage.success('密码已复制到剪贴板');
  } catch {
    ElMessage.error('复制失败,请手动选中复制');
  }
}

function openCreate(): void {
  editingId.value = null;
  Object.assign(form, emptyForm);
  formVisible.value = true;
}

/** 编辑回填:租户标识只读展示(后端修改载荷里没有它),管理员登录名清空——同一段模板只在开通态渲染。 */
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
      // 管理员初始口令由平台管理员自输(P2-3),线下转交租户;响应只回新租户ID
      ElMessage.success('租户已开通,请把管理员初始口令线下告知租户');
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
      width="640px"
    >
      <div
        v-if="editingId === null"
        class="form-banner"
      >
        <AppIcon
          name="alert"
          :size="14"
        />
        <span>除备注外均为必填项;管理员初始口令开通后请线下告知租户</span>
      </div>
      <ElForm
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
      >
        <div class="form-panel">
          <div class="panel-head">
            <span class="panel-ico">
              <AppIcon
                name="box"
                :size="13"
              />
            </span>
            <span class="panel-title">租户信息</span>
            <span class="panel-hint">标识开通后不可修改</span>
          </div>
          <div class="form-grid">
            <ElFormItem
              label="租户标识"
              prop="tenantCode"
            >
              <ElInput
                v-model="form.tenantCode"
                :disabled="editingId !== null"
                placeholder="2-64 位小写,字母、数字或 _ . -"
              />
            </ElFormItem>
            <ElFormItem
              label="租户名称"
              prop="name"
            >
              <ElInput
                v-model="form.name"
                placeholder="列表与授权页展示的名称"
              />
            </ElFormItem>
          </div>
        </div>

        <div
          v-if="editingId === null"
          class="form-panel"
        >
          <div class="panel-head">
            <span class="panel-ico">
              <AppIcon
                name="shield"
                :size="13"
              />
            </span>
            <span class="panel-title">管理员账号</span>
            <span class="panel-hint">初始口令请线下转交租户</span>
          </div>
          <div class="form-grid">
            <ElFormItem
              label="登录名"
              prop="adminUsername"
            >
              <ElInput
                v-model="form.adminUsername"
                placeholder="3-30 位,全站唯一"
              />
            </ElFormItem>
            <ElFormItem
              label="初始密码"
              prop="adminPassword"
            >
              <PasswordHintInput
                v-model="form.adminPassword"
                class="mono-pwd"
              >
                <template #suffix>
                  <button
                    class="pwd-action"
                    type="button"
                    title="重新生成"
                    @click="form.adminPassword = generatePassword()"
                  >
                    <AppIcon
                      name="refresh"
                      :size="15"
                    />
                  </button>
                  <button
                    class="pwd-action"
                    :class="{ 'is-done': copied }"
                    type="button"
                    :title="copied ? '已复制' : '复制密码'"
                    @click="copyAdminPassword"
                  >
                    <AppIcon
                      :name="copied ? 'check' : 'copy'"
                      :size="15"
                    />
                  </button>
                </template>
              </PasswordHintInput>
            </ElFormItem>
          </div>
        </div>

        <div class="form-panel">
          <div class="panel-head">
            <span class="panel-ico">
              <AppIcon
                name="phone"
                :size="13"
              />
            </span>
            <span class="panel-title">联系方式</span>
          </div>
          <div class="form-grid">
            <ElFormItem
              label="联系人"
              prop="contactUser"
            >
              <ElInput
                v-model="form.contactUser"
                placeholder="租户方对接人姓名"
              />
            </ElFormItem>
            <ElFormItem
              label="联系电话"
              prop="contactPhone"
            >
              <ElInput
                v-model="form.contactPhone"
                maxlength="11"
                show-word-limit
                placeholder="11 位手机号"
                @input="sanitizeContactPhone"
              />
            </ElFormItem>
          </div>
        </div>

        <div class="form-panel">
          <div class="panel-head">
            <span class="panel-ico">
              <AppIcon
                name="clock"
                :size="13"
              />
            </span>
            <span class="panel-title">使用限制</span>
            <span class="panel-hint">
              {{ editingId === null ? '开通后可在修改中随时调整' : '留空即恢复不限' }}
            </span>
          </div>
          <div class="form-grid">
            <ElFormItem
              label="到期时间"
              prop="expireTime"
            >
              <!-- value-format 给的是 ISO 本地时间串,后端 LocalDateTime 直接绑定;修改态清空即提交 null=不限期 -->
              <ElDatePicker
                v-model="form.expireTime"
                type="datetime"
                value-format="YYYY-MM-DDTHH:mm:ss"
                :disabled-date="editingId === null ? isPastDay : undefined"
                :placeholder="editingId === null ? '请选择到期时间' : '留空不限'"
                class="full"
              />
            </ElFormItem>
            <ElFormItem
              label="账号上限"
              prop="accountLimit"
            >
              <!-- min=1 是有意的:后端只认 null 为不限,0 会被当成"一个账号都不许建";修改态留空仍是不限 -->
              <ElInputNumber
                v-model="form.accountLimit"
                :min="1"
                :precision="0"
                controls-position="right"
                :placeholder="editingId === null ? '请输入上限数量' : '留空不限'"
                class="full"
              />
            </ElFormItem>
          </div>
        </div>

        <ElFormItem
          label="备注(选填)"
          prop="remark"
          class="remark-item"
        >
          <ElInput
            v-model="form.remark"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            placeholder="选填,最多 500 字"
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

/* 弹窗面板/横幅/口令动作等公共样式在 styles/index.css 的「弹窗表单套件」一节,全局单点维护 */
</style>
