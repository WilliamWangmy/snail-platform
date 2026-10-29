<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import AppIcon from '@/components/AppIcon.vue';
import { fetchOrgTree, type OrgNode } from '@/api/org';
import { pageRoles, type RoleVO } from '@/api/role';
import {
  assignUserRoles,
  changeUserStatus,
  createUser,
  deleteUser,
  forceLogoutUser,
  getUser,
  pageUsers,
  resetUserPassword,
  updateUser,
  type UserQuery,
  type UserVO,
} from '@/api/user';
import { usePageList } from '@/composables/usePageList';
import { useAuthStore } from '@/stores/auth';
import { copyText, generatePassword, validatePassword } from '@/utils/password';
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

/** 密码强度口径与后端 PasswordPolicy 同值,由 validatePassword 单点承载 */
function passwordValidator(_rule: unknown, value: string, callback: (error?: Error) => void): void {
  const message = validatePassword(value);
  callback(message ? new Error(message) : undefined);
}

/** 随机生成填入目标表单的 password 字段 */
function generateInto(target: { password: string }): void {
  target.password = generatePassword();
}

async function copyPassword(value: string): Promise<void> {
  if (!value) return;
  if (await copyText(value)) {
    ElMessage.success('已复制到剪贴板');
  } else {
    ElMessage.error('复制失败,请手动复制');
  }
}

/** 与后端 UserCreateRequest / UserUpdateRequest 的注解同口径,超限在前端先拦住。 */
const rules = computed<FormRules<UserForm>>(() => ({
  username:
    editingId.value === null
      ? [
          { required: true, message: '请输入登录名', trigger: 'blur' },
          { min: 3, max: 30, message: '登录名长度 3-30', trigger: 'blur' },
          { pattern: /^[A-Za-z0-9_.@-]+$/, message: '登录名只允许字母、数字、下划线、点、@、连字符', trigger: 'blur' },
        ]
      : [],
  password:
    editingId.value === null
      ? [
          { required: true, message: '请输入初始密码', trigger: 'blur' },
          { validator: passwordValidator, trigger: 'blur' },
        ]
      : [],
  nickname: [
    { required: true, message: '请输入显示名', trigger: 'blur' },
    { max: 30, message: '显示名过长', trigger: 'blur' },
  ],
  email: [
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
    { max: 50, message: '邮箱过长', trigger: 'blur' },
  ],
  phone: [{ max: 20, message: '手机号过长', trigger: 'blur' }],
  orgId: [{ required: true, message: '请选择所属组织', trigger: 'change' }],
  remark: [{ max: 500, message: '备注过长', trigger: 'blur' }],
}));

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
      ElMessage.success('已新增用户');
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

const pwdVisible = ref(false);
const pwdRef = ref<FormInstance>();
const pwdTarget = ref<UserVO | null>(null);
const pwdForm = reactive({ password: '', confirm: '' });
const pwdRules: FormRules<typeof pwdForm> = {
  password: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { validator: passwordValidator, trigger: 'blur' },
  ],
  confirm: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback: (error?: Error) => void) =>
        value === pwdForm.password ? callback() : callback(new Error('两次输入的密码不一致')),
      trigger: 'blur',
    },
  ],
};

function openResetPassword(row: UserVO): void {
  pwdTarget.value = row;
  pwdForm.password = '';
  pwdForm.confirm = '';
  pwdVisible.value = true;
}

/** 重置会前推令牌版本,该用户全部设备下一次请求即 401;列表字段没变,不重取 */
async function submitResetPassword(): Promise<void> {
  const target = pwdTarget.value;
  if (!target) return;
  const valid = await pwdRef.value?.validate().catch(() => false);
  if (!valid) return;
  await resetUserPassword(target.userId, pwdForm.password);
  ElMessage.success(`已重置 ${target.username} 的密码`);
  pwdVisible.value = false;
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
    { key: 'toggle', label: row.status === 0 ? '停用' : '启用', show: auth.has('system:user:update'), handler: () => { void toggleStatus(row); } },
    { key: 'delete', label: '删除', tone: 'danger', show: auth.has('system:user:delete'), handler: () => { void remove(row); } },
    // 踢自己后端(控制器与服务层两处)都拒,这里先不占位,免得点出一次报错
    { key: 'kick', label: '强制下线', tone: 'danger', show: auth.has('system:user:kick') && row.userId !== auth.user?.userId, handler: () => { void kickUser(row); } },
  ];
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
      width="520px"
    >
      <ElForm
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="88px"
      >
        <ElFormItem
          v-if="editingId === null"
          label="登录名"
          prop="username"
        >
          <ElInput
            v-model="form.username"
            placeholder="登录后不可修改"
          />
        </ElFormItem>
        <ElFormItem
          v-if="editingId === null"
          label="初始密码"
          prop="password"
        >
          <ElInput
            v-model="form.password"
            type="password"
            show-password
            placeholder="8-20 位,四类字符全含"
          >
            <template #append>
              <ElButton @click="generateInto(form)">
                生成
              </ElButton>
              <ElButton @click="copyPassword(form.password)">
                复制
              </ElButton>
            </template>
          </ElInput>
        </ElFormItem>
        <ElFormItem
          label="显示名"
          prop="nickname"
        >
          <ElInput v-model="form.nickname" />
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
        >
          <ElSelect
            v-model="form.roleIds"
            multiple
            collapse-tags
            placeholder="可留空,稍后分配"
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
        <ElFormItem
          label="手机号"
          prop="phone"
        >
          <ElInput v-model="form.phone" />
        </ElFormItem>
        <ElFormItem
          label="邮箱"
          prop="email"
        >
          <ElInput v-model="form.email" />
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

    <ElDialog
      v-model="pwdVisible"
      :title="`重置密码 · ${pwdTarget?.nickname ?? ''}`"
      width="420px"
    >
      <ElForm
        ref="pwdRef"
        :model="pwdForm"
        :rules="pwdRules"
        label-width="88px"
      >
        <ElFormItem
          label="新密码"
          prop="password"
        >
          <ElInput
            v-model="pwdForm.password"
            type="password"
            show-password
            placeholder="8-20 位,四类字符全含"
          >
            <template #append>
              <ElButton @click="generateInto(pwdForm)">
                生成
              </ElButton>
              <ElButton @click="copyPassword(pwdForm.password)">
                复制
              </ElButton>
            </template>
          </ElInput>
        </ElFormItem>
        <ElFormItem
          label="确认密码"
          prop="confirm"
        >
          <ElInput
            v-model="pwdForm.confirm"
            type="password"
            show-password
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="pwdVisible = false">
          取消
        </ElButton>
        <ElButton
          type="primary"
          @click="submitResetPassword"
        >
          确定
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
  </div>
</template>

<style scoped>
.full {
  width: 100%;
}
</style>
