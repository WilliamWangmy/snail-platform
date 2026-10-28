<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import {
  createOrg,
  deleteOrg,
  fetchOrgTree,
  moveOrg,
  updateOrg,
  type OrgNode,
} from '@/api/org';
import { useAuthStore } from '@/stores/auth';
import RowActions, { type RowAction } from '@/components/RowActions.vue';

const auth = useAuthStore();

const loading = ref(false);
const tree = ref<OrgNode[]>([]);
const names = ref(new Map<number, string>());

/** 组织树一次取全,后端不分页;父节点标签用同一次遍历顺手铺平。 */
async function load(): Promise<void> {
  loading.value = true;
  try {
    tree.value = await fetchOrgTree();
    const map = new Map<number, string>();
    const walk = (nodes: OrgNode[]): void => {
      for (const node of nodes) {
        map.set(node.orgId, node.name);
        if (node.children.length) walk(node.children);
      }
    };
    walk(tree.value);
    names.value = map;
  } finally {
    loading.value = false;
  }
}

onMounted(load);

/* -------------------------------- 新增 / 修改 ------------------------------- */

interface OrgForm {
  name: string;
  sort: number;
  status: number;
  remark: string;
}

const emptyForm: OrgForm = { name: '', sort: 0, status: 0, remark: '' };

const formVisible = ref(false);
const formRef = ref<FormInstance>();
const editingId = ref<number | null>(null);
const parentId = ref<number | null>(null);
const form = reactive<OrgForm>({ ...emptyForm });
const saving = ref(false);

const rules: FormRules<OrgForm> = {
  name: [
    { required: true, message: '请输入组织名称', trigger: 'blur' },
    { max: 50, message: '组织名称过长', trigger: 'blur' },
  ],
  remark: [{ max: 500, message: '备注过长', trigger: 'blur' }],
};

const parentLabel = computed(() =>
  parentId.value === null ? '' : (names.value.get(parentId.value) ?? '未知组织'),
);

function openCreate(parent: OrgNode): void {
  editingId.value = null;
  parentId.value = parent.orgId;
  Object.assign(form, emptyForm);
  formVisible.value = true;
}

/** 上级只回填展示:改父级走移动,不在这个表单里提交 */
function openEdit(row: OrgNode): void {
  editingId.value = row.orgId;
  parentId.value = row.parentId;
  Object.assign(form, { name: row.name, sort: row.sort, status: row.status, remark: row.remark ?? '' });
  formVisible.value = true;
}

/** 新增与修改共用弹窗,靠 editingId 分流;提交后整棵重取,不做本地增量更新 */
async function submitForm(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) return;
  saving.value = true;
  try {
    if (editingId.value === null) {
      if (parentId.value === null) return; // 新增态必有父,缺了就是不可能的状态,宁可不提交
      await createOrg({
        parentId: parentId.value,
        name: form.name.trim(),
        sort: form.sort,
        remark: form.remark.trim() || null,
      });
      ElMessage.success('已新增组织');
    } else {
      await updateOrg(editingId.value, {
        name: form.name.trim(),
        sort: form.sort,
        status: form.status,
        remark: form.remark.trim() || null,
      });
      ElMessage.success('已保存修改');
    }
    formVisible.value = false;
    await load();
  } finally {
    saving.value = false;
  }
}

/* --------------------------------- 移动 ----------------------------------- */

const moveVisible = ref(false);
const moveTarget = ref<OrgNode | null>(null);
const moveParentId = ref<number | null>(null);

function openMove(row: OrgNode): void {
  moveTarget.value = row;
  moveParentId.value = row.parentId;
  moveVisible.value = true;
}

/** 树选择器 check-strictly 允许选到子孙,环校验交给后端,前端不重复判 */
async function submitMove(): Promise<void> {
  const target = moveTarget.value;
  if (!target || moveParentId.value === null) return;
  await moveOrg(target.orgId, moveParentId.value);
  ElMessage.success(`已把 ${target.name} 移到 ${names.value.get(moveParentId.value) ?? '新父节点'}`);
  moveVisible.value = false;
  await load();
}

/* --------------------------------- 删除 ----------------------------------- */

async function remove(row: OrgNode): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    `删除组织 ${row.name}?有下级或有在编用户时会被拒绝。`,
    '删除确认',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
  )
    .then(() => true)
    .catch(() => false);
  if (!confirmed) return;
  await deleteOrg(row.orgId);
  ElMessage.success('已删除');
  await load();
}

/** 行操作:权限不足的不占位,超过三个由 RowActions 折叠。 */
function actionsOf(row: OrgNode): RowAction[] {
  return [
    { key: 'create', label: '新增下级', show: auth.has('system:org:create'), handler: () => openCreate(row) },
    { key: 'edit', label: '修改', show: auth.has('system:org:update'), handler: () => openEdit(row) },
    // 根不提供移动,免得点了才被后端拒
    { key: 'move', label: '移动', show: auth.has('system:org:update') && row.parentId !== 0, handler: () => openMove(row) },
    { key: 'delete', label: '删除', tone: 'danger', show: auth.has('system:org:delete'), handler: () => { void remove(row); } },
  ];
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="eyebrow">
          ORGANIZATION
        </div>
        <h1>组织管理</h1>
      </div>
    </div>

    <ElCard
      shadow="never"
      class="table-card"
    >
      <template #header>
        <div class="table-card-head">
          <h3>组织架构 <span>共 {{ names.size }} 个</span></h3>
        </div>
      </template>

      <ElTable
        v-loading="loading"
        :data="tree"
        row-key="orgId"
        :tree-props="{ children: 'children' }"
        :default-expand-all="true"
      >
        <ElTableColumn
          prop="name"
          label="组织名称"
          min-width="240"
        />
        <ElTableColumn
          prop="sort"
          label="排序"
          width="70"
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
          label="备注"
          min-width="180"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ row.remark ?? '—' }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="操作"
          width="180"
          align="center"
          fixed="right"
        >
          <template #default="{ row }">
            <RowActions :actions="actionsOf(row as OrgNode)" />
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <ElDialog
      v-model="formVisible"
      :title="editingId === null ? '新增下级组织' : '修改组织'"
      width="460px"
    >
      <ElForm
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="88px"
      >
        <ElFormItem label="上级组织">
          <ElInput
            :model-value="parentLabel"
            disabled
          />
        </ElFormItem>
        <ElFormItem
          label="组织名称"
          prop="name"
        >
          <ElInput v-model="form.name" />
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
        <!-- 状态只在修改时给:新节点后端一律置正常。0/1 沿用后端取值,0=正常 映射开关的激活态 -->
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
      v-model="moveVisible"
      :title="`移动组织 · ${moveTarget?.name ?? ''}`"
      width="420px"
    >
      <ElTreeSelect
        v-model="moveParentId"
        :data="tree"
        node-key="orgId"
        :props="{ label: 'name', children: 'children' }"
        check-strictly
        placeholder="选择新的上级组织"
        class="full"
      />
      <template #footer>
        <ElButton @click="moveVisible = false">
          取消
        </ElButton>
        <ElButton
          type="primary"
          @click="submitMove"
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
