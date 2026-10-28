<script setup lang="ts">
import { nextTick, onMounted, reactive, ref } from 'vue';
import {
  ElMessage,
  ElMessageBox,
  ElTree,
  type FormInstance,
  type FormRules,
} from 'element-plus';
import AppIcon from '@/components/AppIcon.vue';
import {
  assignRoleMenus,
  changeRoleStatus,
  createRole,
  deleteRole,
  fetchRoleMenus,
  pageRoles,
  updateRole,
  type RoleMenuNode,
  type RoleQuery,
  type RoleVO,
} from '@/api/role';
import { usePageList } from '@/composables/usePageList';
import { useAuthStore } from '@/stores/auth';
import RowActions, { type RowAction } from '@/components/RowActions.vue';

const auth = useAuthStore();

const query = reactive<RoleQuery>({ keyword: '', status: null });
const { rows, total, loading, pageNum, pageSize, refresh, search } = usePageList<RoleVO>((page) =>
  pageRoles({ ...query, ...page }),
);

/**
 * 与后端 RoleCreateRequest 的 dataScope 注释同一份口径。
 * 不按操作者自身档位过滤选项:登录快照不带 data_scope,前端拿不到"最宽档"这个输入,
 * 选了宽的一律由 create/update 在落库前拒成 403。
 */
const DATA_SCOPES = [
  { value: 1, label: '全部数据' },
  { value: 2, label: '本组织' },
  { value: 3, label: '本组织及以下' },
  { value: 4, label: '本人' },
];

onMounted(refresh);

function reset(): void {
  query.keyword = '';
  query.status = null;
  void search();
}

/* -------------------------------- 新增 / 修改 ------------------------------- */

interface RoleForm {
  roleName: string;
  roleCode: string;
  dataScope: number;
  sort: number;
  status: number;
  remark: string;
}

const emptyForm: RoleForm = { roleName: '', roleCode: '', dataScope: 2, sort: 0, status: 0, remark: '' };

const formVisible = ref(false);
const formRef = ref<FormInstance>();
const editingId = ref<number | null>(null);
const form = reactive<RoleForm>({ ...emptyForm });
const saving = ref(false);

const rules: FormRules<RoleForm> = {
  roleName: [
    { required: true, message: '请输入角色名称', trigger: 'blur' },
    { max: 30, message: '角色名称过长', trigger: 'blur' },
  ],
  roleCode: [
    { required: true, message: '请输入角色标识', trigger: 'blur' },
    { min: 2, max: 64, message: '角色标识长度 2-64', trigger: 'blur' },
    { pattern: /^[a-z0-9_]+$/, message: '角色标识只允许小写字母、数字、下划线', trigger: 'blur' },
  ],
  dataScope: [{ required: true, message: '请选择数据权限', trigger: 'change' }],
  remark: [{ max: 500, message: '备注过长', trigger: 'blur' }],
};

function openCreate(): void {
  editingId.value = null;
  Object.assign(form, emptyForm);
  formVisible.value = true;
}

/** 回填整行:roleCode 只为让禁用态显示得出来,提交时不进 payload;status 取当前值给开关 */
function openEdit(row: RoleVO): void {
  editingId.value = row.roleId;
  Object.assign(form, {
    roleName: row.roleName,
    roleCode: row.roleCode,
    dataScope: row.dataScope,
    sort: row.sort,
    status: row.status,
    remark: row.remark ?? '',
  });
  formVisible.value = true;
}

async function submitForm(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) return;
  saving.value = true;
  try {
    // 两条契约不同形:新增带 roleCode、不带 status(建出来恒为正常);修改反过来
    if (editingId.value === null) {
      await createRole({
        roleName: form.roleName.trim(),
        roleCode: form.roleCode.trim(),
        dataScope: form.dataScope,
        sort: form.sort,
        remark: form.remark.trim() || null,
      });
      ElMessage.success('已新增角色');
    } else {
      await updateRole(editingId.value, {
        roleName: form.roleName.trim(),
        dataScope: form.dataScope,
        sort: form.sort,
        status: form.status,
        remark: form.remark.trim() || null,
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

/** 行内启停走独立的 status 接口,不顺带提交整份表单;成功后就地翻牌,省一次列表回查 */
async function toggleStatus(row: RoleVO): Promise<void> {
  const next = row.status === 0 ? 1 : 0;
  await changeRoleStatus(row.roleId, next);
  row.status = next;
  ElMessage.success(next === 0 ? '已启用' : '已停用');
}

/* ------------------------------- 菜单授权 --------------------------------- */

const menuVisible = ref(false);
const menuTreeRef = ref<InstanceType<typeof ElTree>>();
const menuTarget = ref<RoleVO | null>(null);
const menuTree = ref<RoleMenuNode[]>([]);
const menuSaving = ref(false);

/** 只回填叶子:非叶子勾选状态由子节点推导,直接 set 会把整棵子树连带勾上。 */
function leafCheckedKeys(nodes: RoleMenuNode[]): number[] {
  const keys: number[] = [];
  const walk = (list: RoleMenuNode[]): void => {
    for (const node of list) {
      if (node.children.length) walk(node.children);
      else if (node.checked) keys.push(node.menuId);
    }
  };
  walk(nodes);
  return keys;
}

async function openAssignMenus(row: RoleVO): Promise<void> {
  menuTarget.value = row;
  menuTree.value = await fetchRoleMenus(row.roleId);
  menuVisible.value = true;
  // 弹窗是 destroy-on-close:树实例要等打开后才存在,这步挪到 await 之前会拿到 undefined
  await nextTick();
  menuTreeRef.value?.setCheckedKeys(leafCheckedKeys(menuTree.value));
}

async function submitAssignMenus(): Promise<void> {
  const target = menuTarget.value;
  const tree = menuTreeRef.value;
  if (!target || !tree) return;
  // getCheckedKeys 不含半选的父目录,要单独取:授权是全量替换,漏了它们等于把容器行从授权表里删掉
  const checked = tree.getCheckedKeys();
  const half = tree.getHalfCheckedKeys();
  menuSaving.value = true;
  try {
    await assignRoleMenus(target.roleId, [...checked, ...half].map((key) => Number(key)));
    ElMessage.success(`已更新 ${target.roleName} 的菜单授权`);
    menuVisible.value = false;
  } finally {
    menuSaving.value = false;
  }
}

/* --------------------------------- 删除 ----------------------------------- */

async function remove(row: RoleVO): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    `删除角色 ${row.roleName}(${row.roleCode})?内置角色与已被用户使用的角色会被拒绝。`,
    '删除确认',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
  )
    .then(() => true)
    .catch(() => false);
  if (!confirmed) return;
  await deleteRole(row.roleId);
  ElMessage.success('已删除');
  await refresh();
}

/** 行操作:权限不足的不占位,超过三个由 RowActions 折叠;内置角色只留菜单授权。 */
function actionsOf(row: RoleVO): RowAction[] {
  const mutable = row.isSystem !== 1;
  return [
    { key: 'edit', label: '修改', show: mutable && auth.has('system:role:update'), handler: () => openEdit(row) },
    { key: 'menus', label: '菜单授权', show: auth.has('system:role:assign-menu'), handler: () => { void openAssignMenus(row); } },
    { key: 'toggle', label: row.status === 0 ? '停用' : '启用', show: mutable && auth.has('system:role:update'), handler: () => { void toggleStatus(row); } },
    { key: 'delete', label: '删除', tone: 'danger', show: mutable && auth.has('system:role:delete'), handler: () => { void remove(row); } },
  ];
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="eyebrow">
          ACCESS CONTROL
        </div>
        <h1>角色管理</h1>
      </div>
      <div class="head-actions">
        <ElButton
          v-permission="'system:role:create'"
          type="primary"
          @click="openCreate"
        >
          <AppIcon
            name="plus"
            :size="15"
          />
          新增角色
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
        <ElFormItem label="关键字">
          <ElInput
            v-model="query.keyword"
            clearable
            placeholder="角色名称或标识"
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
          <h3>角色列表 <span>共 {{ total }} 个</span></h3>
        </div>
      </template>

      <ElTable
        v-loading="loading"
        :data="rows"
        row-key="roleId"
      >
        <ElTableColumn
          prop="roleName"
          label="角色名称"
          min-width="150"
        />
        <ElTableColumn
          prop="roleCode"
          label="角色标识"
          min-width="140"
        />
        <ElTableColumn
          label="数据权限"
          min-width="140"
        >
          <template #default="{ row }">
            <ElTag
              size="small"
              type="info"
              effect="plain"
            >
              {{ DATA_SCOPES.find((item) => item.value === row.dataScope)?.label ?? row.dataScope }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="内置"
          width="80"
          align="center"
        >
          <template #default="{ row }">
            <ElTag
              v-if="row.isSystem === 1"
              size="small"
              type="warning"
              effect="plain"
            >
              内置
            </ElTag>
            <span v-else>—</span>
          </template>
        </ElTableColumn>
        <ElTableColumn
          prop="sort"
          label="排序"
          width="80"
          align="center"
        />
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
          width="180"
          align="center"
          fixed="right"
        >
          <template #default="{ row }">
            <RowActions :actions="actionsOf(row as RoleVO)" />
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
      :title="editingId === null ? '新增角色' : '修改角色'"
      width="480px"
    >
      <ElForm
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="88px"
      >
        <ElFormItem
          label="角色名称"
          prop="roleName"
        >
          <ElInput v-model="form.roleName" />
        </ElFormItem>
        <ElFormItem
          label="角色标识"
          prop="roleCode"
        >
          <ElInput
            v-model="form.roleCode"
            :disabled="editingId !== null"
            :placeholder="editingId === null ? '小写字母、数字、下划线' : '创建后不可修改'"
          />
        </ElFormItem>
        <ElFormItem
          label="数据权限"
          prop="dataScope"
        >
          <ElSelect
            v-model="form.dataScope"
            class="full"
          >
            <ElOption
              v-for="item in DATA_SCOPES"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem
          label="排序"
          prop="sort"
        >
          <ElInputNumber
            v-model="form.sort"
            :min="0"
            :precision="0"
            controls-position="right"
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

    <ElDialog
      v-model="menuVisible"
      :title="`菜单授权 · ${menuTarget?.roleName ?? ''}`"
      width="520px"
      destroy-on-close
    >
      <ElAlert
        v-if="menuTarget?.isSystem === 1"
        type="warning"
        :closable="false"
        show-icon
        class="grant-note"
        title="内置角色的租户面菜单为隐式全量授权,树内勾选只反映显式授权行。"
      />
      <ElScrollbar height="420px">
        <ElTree
          ref="menuTreeRef"
          :data="menuTree"
          node-key="menuId"
          :props="{ label: 'menuName', children: 'children' }"
          show-checkbox
          default-expand-all
        />
      </ElScrollbar>
      <template #footer>
        <ElButton @click="menuVisible = false">
          取消
        </ElButton>
        <ElButton
          type="primary"
          :loading="menuSaving"
          @click="submitAssignMenus"
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

.grant-note {
  margin-bottom: 10px;
}
</style>
