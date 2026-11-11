<script setup lang="ts">
/** 组件名与路由 meta.viewName 对应,多页签 KeepAlive include 依此缓存。 */
defineOptions({ name: 'SystemUser' });
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import dayjs from 'dayjs';
import AppIcon from '@/components/AppIcon.vue';
import { fetchOrgTree, type OrgNode } from '@/api/org';
import { pageRoles, type RoleVO } from '@/api/role';
import {
  assignUserRoles,
  changeUserStatus,
  checkUsername,
  createUser,
  deleteUser,
  fetchUserIdentities,
  forceLogoutUser,
  getUser,
  pageUsers,
  resetUserPassword,
  updateUser,
  type UserIdentity,
  type UserQuery,
  type UserVO,
} from '@/api/user';
import { usePageList } from '@/composables/usePageList';
import { useAuthStore } from '@/stores/auth';
import { generatePassword, validatePassword } from '@/utils/password';
import PasswordHintInput from '@/components/PasswordHintInput.vue';
import RowActions, { type RowAction } from '@/components/RowActions.vue';

const auth = useAuthStore();

const query = reactive<UserQuery>({
  username: '',
  nickname: '',
  orgId: null,
  status: null,
});

const { rows, total, loading, pageNum, pageSize, refresh, search } = usePageList<UserVO>((page) =>
  pageUsers({ ...query, ...page }),
);

const orgTree = ref<OrgNode[]>([]);
const orgLabels = ref(new Map<number, string>());
const roleOptions = ref<RoleVO[]>([]);

/** 组织树只在持有 system:org:list 时取;拿不到就整列显示破折号,不猜名称。 */
async function loadOrgs(): Promise<void> {
  if (!auth.has('system:org:list')) return;
  orgTree.value = await fetchOrgTree();
  const map = new Map<number, string>();
  const walk = (nodes: OrgNode[], prefix: string): void => {
    for (const node of nodes) {
      const label = prefix ? `${prefix} / ${node.name}` : node.name;
      map.set(node.orgId, label);
      if (node.children.length) walk(node.children, label);
    }
  };
  walk(orgTree.value, '');
  orgLabels.value = map;
}

/** 角色下拉同样要 system:role:list;一页 200 条当全集用,角色数过 200 就得换成分页搜索。 */
async function loadRoles(): Promise<void> {
  if (!auth.has('system:role:list')) return;
  roleOptions.value = (await pageRoles({ pageNum: 1, pageSize: 200 })).rows;
}

onMounted(async () => {
  await Promise.all([refresh(), loadOrgs(), loadRoles()]);
});

function reset(): void {
  query.username = '';
  query.nickname = '';
  query.orgId = null;
  query.status = null;
  void search();
}

/* -------------------------------- 新增 / 修改 ------------------------------- */

interface UserForm {
  username: string;
  /** 初始口令(P2-3):管理员自输或点生成,仅在新增态提交 */
  password: string;
  nickname: string;
  email: string;
  phone: string;
  orgId: number | null;
  remark: string;
  roleIds: number[];
}

const emptyForm: UserForm = {
  username: '',
  password: '',
  nickname: '',
  email: '',
  phone: '',
  orgId: null,
  remark: '',
  roleIds: [],
};

const formVisible = ref(false);
const formRef = ref<FormInstance>();
const editingId = ref<number | null>(null);
const form = reactive<UserForm>({ ...emptyForm });
const saving = ref(false);

/** 与后端 UserCreateRequest 的 @Pattern 同口径;失焦占用查询前先过它,脏输入不打接口 */
const USERNAME_PATTERN = /^[A-Za-z0-9_.@-]+$/;

/** 与后端 UserCreateRequest 的注解同口径,超限在前端先拦住;必填口径按需求收紧:除备注外全部必填 */
const rules = computed<FormRules<UserForm>>(() => ({
  username:
    editingId.value === null
      ? [
          { required: true, message: '请输入登录名', trigger: 'blur' },
          { min: 3, max: 30, message: '登录名长度 3-30', trigger: 'blur' },
          { pattern: USERNAME_PATTERN, message: '登录名只允许字母、数字、下划线、点、@、连字符', trigger: 'blur' },
          {
            // 失焦即查占用(全表口径,含已删行);保存时后端仍会再拦一次,防两人同时提交同名。
            // 用回调式而非 async:EP 的 FormItemRule 校验器签名是同步 void,Promise 返回值过不了类型
            validator: (_rule: unknown, value: string, callback: (error?: Error) => void) => {
              if (!value || !USERNAME_PATTERN.test(value)) {
                callback();
                return;
              }
              checkUsername(value)
                .then((taken) => callback(taken ? new Error('登录名已被占用') : undefined))
                .catch(() => callback());
            },
            trigger: 'blur',
          },
        ]
      : [],
  password:
    editingId.value === null
      ? [
          { required: true, message: '请输入初始密码', trigger: 'blur' },
          {
            // 强度口径与后端 PasswordPolicy 同值,由 validatePassword 单点承载
            validator: (_rule: unknown, value: string, callback: (error?: Error) => void) => {
              const message = validatePassword(value);
              callback(message ? new Error(message) : undefined);
            },
            trigger: 'blur',
          },
        ]
      : [],
  nickname: [
    { required: true, message: '请输入显示名', trigger: 'blur' },
    { max: 30, message: '显示名过长', trigger: 'blur' },
  ],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
    { max: 50, message: '邮箱过长', trigger: 'blur' },
  ],
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    // 前端收紧为固定 11 位数字;后端仍是 max 20,松口径不改,防止其他入口复用契约被误伤
    { pattern: /^\d{11}$/, message: '手机号必须为 11 位数字', trigger: 'blur' },
  ],
  orgId: [{ required: true, message: '请选择所属组织', trigger: 'change' }],
  // 角色只在新增态展示;无 system:role:list 权限时拿不到角色列表、字段隐藏,此时不拦截
  roleIds:
    editingId.value === null && roleOptions.value.length
      ? [{ type: 'array', required: true, message: '请至少选择一个角色', trigger: 'change' }]
      : [],
  remark: [{ max: 500, message: '备注过长', trigger: 'blur' }],
}));

/** 手机号输入即清洗:剔除非数字并截断到 11 位,粘贴超长内容也会被就地裁掉 */
function sanitizePhone(): void {
  form.phone = form.phone.replace(/\D/g, '').slice(0, 11);
}

/** 复制成功的瞬时反馈:图标短暂变 ✓,1.6s 后还原 */
const copiedField = ref<'form' | 'reset' | null>(null);
let copyTimer: ReturnType<typeof setTimeout> | undefined;

/** 口令敏感,只进剪贴板不落日志;非安全上下文(如 http 内网直连)时降级为手动复制 */
async function copyPassword(value: string, field: 'form' | 'reset'): Promise<void> {
  if (!value) {
    ElMessage.warning('还没有可复制的密码,请先点刷新图标生成');
    return;
  }
  try {
    await navigator.clipboard.writeText(value);
    copiedField.value = field;
    window.clearTimeout(copyTimer);
    copyTimer = window.setTimeout(() => (copiedField.value = null), 1600);
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

/** 修改态只回填档案五列:username/password 后端契约里没有,清空是为了不让它们出现在提交里 */
function openEdit(row: UserVO): void {
  editingId.value = row.userId;
  Object.assign(form, {
    ...emptyForm,
    nickname: row.nickname,
    email: row.email ?? '',
    phone: row.phone ?? '',
    orgId: row.orgId,
    remark: row.remark ?? '',
  });
  formVisible.value = true;
}

/** 新增与修改共用弹窗,靠 editingId 分流;口令只在新增态提交 */
async function submitForm(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) return;
  saving.value = true;
  try {
    const text = (value: string): string | null => value.trim() || null;
    if (editingId.value === null) {
      await createUser({
        username: form.username.trim(),
        password: form.password,
        nickname: form.nickname.trim(),
        email: text(form.email),
        phone: text(form.phone),
        orgId: form.orgId,
        remark: text(form.remark),
        roleIds: form.roleIds,
      });
      // 初始口令由管理员自输(P2-3),线下转交本人;响应只回新用户ID
      ElMessage.success('已新增用户,请把初始口令线下告知本人');
    } else {
      await updateUser(editingId.value, {
        nickname: form.nickname.trim(),
        email: text(form.email),
        phone: text(form.phone),
        orgId: form.orgId,
        remark: text(form.remark),
      });
      ElMessage.success('已保存修改');
    }
    formVisible.value = false;
    await refresh();
  } finally {
    saving.value = false;
  }
}

/* --------------------------------- 启停 ----------------------------------- */

/** 就地翻牌不重取列表:后端同状态会提前返回,重复点不会多踢一次令牌版本。停自己由后端拒,按钮不隐藏。 */
async function toggleStatus(row: UserVO): Promise<void> {
  const next = row.status === 0 ? 1 : 0;
  await changeUserStatus(row.userId, next);
  row.status = next;
  ElMessage.success(next === 0 ? '已启用' : '已停用');
}

/* -------------------------------- 重置密码 -------------------------------- */

/** 重置口令弹窗(P2-3):新口令由管理员自输或点生成,提交后原密码与全部会话立即失效,线下转交本人 */
const resetVisible = ref(false);
const resetTarget = ref<UserVO | null>(null);
const resetPassword = ref('');
const resetting = ref(false);

function openResetPassword(row: UserVO): void {
  resetTarget.value = row;
  resetPassword.value = '';
  resetVisible.value = true;
}

function generateResetPassword(): void {
  resetPassword.value = generatePassword();
}

async function submitResetPassword(): Promise<void> {
  if (!resetTarget.value) return;
  const message = validatePassword(resetPassword.value);
  if (message) {
    ElMessage.warning(message);
    return;
  }
  resetting.value = true;
  try {
    await resetUserPassword(resetTarget.value.userId, resetPassword.value);
    ElMessage.success('密码已重置,原密码与全部登录会话已失效,请线下告知本人');
    resetVisible.value = false;
  } finally {
    resetting.value = false;
  }
}

/* -------------------------------- 分配角色 -------------------------------- */

const roleVisible = ref(false);
const roleTarget = ref<UserVO | null>(null);
const roleIds = ref<number[]>([]);

/** 列表行的 roleIds 是 null(后端不为列表回查授权),所以打开对话框前必须按详情再取一次 */
async function openAssignRoles(row: UserVO): Promise<void> {
  roleTarget.value = row;
  const detail = await getUser(row.userId);
  roleIds.value = detail.roleIds ?? [];
  roleVisible.value = true;
}

/** 全量覆盖:提交当前勾选集即最终态,空集就是清空授权 */
async function submitAssignRoles(): Promise<void> {
  const target = roleTarget.value;
  if (!target) return;
  await assignUserRoles(target.userId, roleIds.value);
  ElMessage.success(`已更新 ${target.username} 的角色`);
  roleVisible.value = false;
}

/* --------------------------------- 删除 ----------------------------------- */

async function remove(row: UserVO): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    `删除用户 ${row.nickname}(${row.username})?该操作不可恢复。`,
    '删除确认',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
  )
    .then(() => true)
    .catch(() => false);
  if (!confirmed) return;
  await deleteUser(row.userId);
  ElMessage.success('已删除');
  await refresh();
}

/** 行操作:权限不足的不占位,超过三个由 RowActions 折叠。 */
function actionsOf(row: UserVO): RowAction[] {
  return [
    { key: 'edit', label: '修改', show: auth.has('system:user:update'), handler: () => openEdit(row) },
    { key: 'reset', label: '重置密码', show: auth.has('system:user:reset-password'), handler: () => openResetPassword(row) },
    { key: 'roles', label: '分配角色', show: auth.has('system:user:assign-role'), handler: () => { void openAssignRoles(row); } },
    { key: 'identities', label: '第三方绑定', show: auth.has('system:user:list'), handler: () => { void openIdentities(row); } },
    { key: 'toggle', label: row.status === 0 ? '停用' : '启用', show: auth.has('system:user:update'), handler: () => { void toggleStatus(row); } },
    { key: 'delete', label: '删除', tone: 'danger', show: auth.has('system:user:delete'), handler: () => { void remove(row); } },
    // 踢自己后端(控制器与服务层两处)都拒,这里先不占位,免得点出一次报错
    { key: 'kick', label: '强制下线', tone: 'danger', show: auth.has('system:user:kick') && row.userId !== auth.user?.userId, handler: () => { void kickUser(row); } },
  ];
}

/* -------------------------------- 第三方绑定(只读) -------------------------------- */

const identityVisible = ref(false);
const identityTarget = ref<UserVO | null>(null);
const identityLoading = ref(false);
/** 空数组 = 未绑定任何第三方身份 */
const identities = ref<UserIdentity[]>([]);

/** 来源编码展示名,与后端 login_type 同口径 */
const OAUTH_SOURCE_LABELS: Record<string, string> = { dingtalk: '钉钉', feishu: '飞书', wecom: '企业微信' };

/** 绑定列表随开随取:错绑排查要看最近登录时间,openId 原样展示便于与厂商后台核对 */
async function openIdentities(row: UserVO): Promise<void> {
  identityTarget.value = row;
  identityVisible.value = true;
  identityLoading.value = true;
  identities.value = [];
  try {
    identities.value = await fetchUserIdentities(row.userId);
  } finally {
    identityLoading.value = false;
  }
}

function formatTime(value: string | null): string {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '—';
}

/** 强制下线:令牌版本自增,该用户全部设备下一次请求即 401。 */
async function kickUser(row: UserVO): Promise<void> {
  await ElMessageBox.confirm(`将强制下线 ${row.username} 的全部会话,确定执行?`, '强制下线', { type: 'warning' });
  await forceLogoutUser(row.userId);
  ElMessage.success('已强制下线');
  await refresh();
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="eyebrow">
          USER DIRECTORY
        </div>
        <h1>用户管理</h1>
      </div>
      <div class="head-actions">
        <ElButton
          v-permission="'system:user:create'"
          type="primary"
          @click="openCreate"
        >
          <AppIcon
            name="plus"
            :size="15"
          />
          新增用户
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
        <ElFormItem label="显示名">
          <ElInput
            v-model="query.nickname"
            clearable
            placeholder="模糊匹配"
            style="width: 180px"
            @keyup.enter="search"
          />
        </ElFormItem>
        <ElFormItem
          v-if="orgTree.length"
          label="所属组织"
        >
          <ElTreeSelect
            v-model="query.orgId"
            :data="orgTree"
            node-key="orgId"
            :props="{ label: 'name', children: 'children' }"
            check-strictly
            clearable
            placeholder="精确到本级"
            style="width: 200px"
          />
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
          <h3>用户列表 <span>共 {{ total }} 人</span></h3>
        </div>
      </template>

      <ElTable
        v-loading="loading"
        :data="rows"
        row-key="userId"
      >
        <ElTableColumn
          prop="username"
          label="登录名"
          min-width="130"
        />
        <ElTableColumn
          prop="nickname"
          label="显示名"
          min-width="120"
        />
        <ElTableColumn
          label="所属组织"
          min-width="180"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ orgLabels.get(row.orgId ?? -1) ?? '—' }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          prop="phone"
          label="手机号"
          min-width="120"
        >
          <template #default="{ row }">
            {{ row.phone ?? '—' }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          prop="email"
          label="邮箱"
          min-width="144"
        >
          <template #default="{ row }">
            {{ row.email ?? '—' }}
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
          width="168"
          align="center"
          fixed="right"
        >
          <template #default="{ row }">
            <RowActions :actions="actionsOf(row as UserVO)" />
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
      :title="editingId === null ? '新增用户' : '修改用户'"
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
        <span>除备注外均为必填项;初始口令创建成功后请线下告知本人</span>
      </div>
      <ElForm
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
      >
        <div
          v-if="editingId === null"
          class="form-panel"
        >
          <div class="panel-head">
            <span class="panel-ico">
              <AppIcon
                name="lock"
                :size="13"
              />
            </span>
            <span class="panel-title">账号信息</span>
            <span class="panel-hint">登录名与初始密码创建后不可修改</span>
          </div>
          <div class="form-grid">
            <ElFormItem
              label="登录名"
              prop="username"
            >
              <ElInput
                v-model="form.username"
                placeholder="3-30 位,字母、数字或 _ . @ -"
              />
            </ElFormItem>
            <ElFormItem
              label="初始密码"
              prop="password"
            >
              <PasswordHintInput
                v-model="form.password"
                class="mono-pwd"
              >
                <template #suffix>
                  <button
                    class="pwd-action"
                    type="button"
                    title="重新生成"
                    @click="form.password = generatePassword()"
                  >
                    <AppIcon
                      name="refresh"
                      :size="15"
                    />
                  </button>
                  <button
                    class="pwd-action"
                    :class="{ 'is-done': copiedField === 'form' }"
                    type="button"
                    :title="copiedField === 'form' ? '已复制' : '复制密码'"
                    @click="copyPassword(form.password, 'form')"
                  >
                    <AppIcon
                      :name="copiedField === 'form' ? 'check' : 'copy'"
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
                name="user"
                :size="13"
              />
            </span>
            <span class="panel-title">基本信息</span>
          </div>
          <div class="form-grid">
            <ElFormItem
              label="显示名"
              prop="nickname"
            >
              <ElInput
                v-model="form.nickname"
                placeholder="列表与通知中展示的名称"
              />
            </ElFormItem>
            <ElFormItem
              label="所属组织"
              prop="orgId"
            >
              <ElTreeSelect
                v-model="form.orgId"
                :data="orgTree"
                node-key="orgId"
                :props="{ label: 'name', children: 'children' }"
                check-strictly
                placeholder="请选择组织"
                class="full"
              />
            </ElFormItem>
            <ElFormItem
              v-if="roleOptions.length && editingId === null"
              label="角色"
              prop="roleIds"
              class="span-2"
            >
              <ElSelect
                v-model="form.roleIds"
                multiple
                collapse-tags
                placeholder="请至少选择一个角色"
                class="full"
              >
                <ElOption
                  v-for="role in roleOptions"
                  :key="role.roleId"
                  :label="role.roleName"
                  :value="role.roleId"
                />
              </ElSelect>
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
              label="手机号"
              prop="phone"
            >
              <ElInput
                v-model="form.phone"
                maxlength="11"
                show-word-limit
                placeholder="11 位手机号"
                @input="sanitizePhone"
              />
            </ElFormItem>
            <ElFormItem
              label="邮箱"
              prop="email"
            >
              <ElInput
                v-model="form.email"
                placeholder="name@example.com"
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

    <ElDialog
      v-model="resetVisible"
      :title="`重置密码 · ${resetTarget?.nickname ?? ''}`"
      width="420px"
    >
      <ElAlert
        type="warning"
        :closable="false"
        show-icon
        title="提交后原密码与全部登录会话立即失效,新口令请线下告知本人。"
      />
      <PasswordHintInput
        v-model="resetPassword"
        class="pwd-reset mono-pwd"
        placeholder="输入新口令或点刷新图标生成"
      >
        <template #suffix>
          <button
            class="pwd-action"
            type="button"
            title="重新生成"
            @click="generateResetPassword"
          >
            <AppIcon
              name="refresh"
              :size="15"
            />
          </button>
          <button
            class="pwd-action"
            :class="{ 'is-done': copiedField === 'reset' }"
            type="button"
            :title="copiedField === 'reset' ? '已复制' : '复制密码'"
            @click="copyPassword(resetPassword, 'reset')"
          >
            <AppIcon
              :name="copiedField === 'reset' ? 'check' : 'copy'"
              :size="15"
            />
          </button>
        </template>
      </PasswordHintInput>
      <template #footer>
        <ElButton @click="resetVisible = false">
          取消
        </ElButton>
        <ElButton
          type="primary"
          :loading="resetting"
          @click="submitResetPassword"
        >
          确认重置
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="roleVisible"
      :title="`分配角色 · ${roleTarget?.nickname ?? ''}`"
      width="420px"
    >
      <ElSelect
        v-model="roleIds"
        multiple
        placeholder="请选择角色"
        class="full"
      >
        <ElOption
          v-for="role in roleOptions"
          :key="role.roleId"
          :label="`${role.roleName}(${role.roleCode})`"
          :value="role.roleId"
        />
      </ElSelect>
      <template #footer>
        <ElButton @click="roleVisible = false">
          取消
        </ElButton>
        <ElButton
          type="primary"
          @click="submitAssignRoles"
        >
          保存
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="identityVisible"
      :title="`第三方绑定 · ${identityTarget?.nickname ?? ''}`"
      width="520px"
    >
      <ElAlert
        v-if="!identityLoading && identities.length === 0"
        :closable="false"
        show-icon
        title="该用户未绑定任何第三方身份;绑定在首次扫码登录成功后自动建立。"
      />
      <div
        v-else
        v-loading="identityLoading"
        class="identity-list"
      >
        <div
          v-for="identity in identities"
          :key="identity.id"
          class="identity-item"
        >
          <div class="identity-head">
            <b>{{ OAUTH_SOURCE_LABELS[identity.source] ?? identity.source }}</b>
            <span>{{ identity.nickname ?? '—' }}</span>
          </div>
          <div class="identity-line">
            {{ identity.openId }}
          </div>
          <div class="identity-line">
            绑定于 {{ formatTime(identity.createTime) }}
            <template v-if="identity.lastLoginTime">
              · 最近登录 {{ formatTime(identity.lastLoginTime) }}
            </template>
          </div>
        </div>
      </div>
      <template #footer>
        <ElButton @click="identityVisible = false">
          关闭
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
.pwd-reset {
  margin-top: 12px;
}

.identity-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 60px;
}

.identity-item {
  padding: 10px 14px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
}

.identity-head {
  display: flex;
  justify-content: space-between;
  font-size: 14px;
}

.identity-head span {
  color: var(--el-text-color-secondary);
}

.identity-line {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  word-break: break-all;
}
</style>
