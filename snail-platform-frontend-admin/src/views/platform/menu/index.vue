<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import AppIcon from '@/components/AppIcon.vue';
import {
  createMenu,
  deleteMenu,
  fetchMenuTree,
  updateMenu,
  type MenuNode,
} from '@/api/menu';
import { useAuthStore } from '@/stores/auth';
import RowActions, { type RowAction } from '@/components/RowActions.vue';

const auth = useAuthStore();

/** 三张表的 value 与 sys_menu 的列注释同码(界面文案可不同);表单可选项由它们彼此推导,不是自由输入。 */
const MENU_TYPES = [
  { value: 1, label: '目录' },
  { value: 2, label: '菜单' },
  { value: 3, label: '按钮' },
];
const TARGETS = [
  { value: 1, label: '管理端' },
  { value: 2, label: '数据大屏' },
  { value: 3, label: '外链' },
];
const SCOPES = [
  { value: 1, label: '平台' },
  { value: 2, label: '租户' },
];

function labelOf(list: { value: number; label: string }[], value: number): string {
  return list.find((item) => item.value === value)?.label ?? String(value);
}

const loading = ref(false);
const tree = ref<MenuNode[]>([]);
const names = ref(new Map<number, string>());

/** 菜单树一次取全(含按钮);父节点名称由同一次遍历铺平,供表单只读展示。 */
async function load(): Promise<void> {
  loading.value = true;
  try {
    tree.value = await fetchMenuTree();
    const map = new Map<number, string>();
    const walk = (nodes: MenuNode[]): void => {
      for (const node of nodes) {
        map.set(node.menuId, node.menuName);
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

/* ------------------------------ 新增 / 修改 ------------------------------- */

interface MenuForm {
  menuName: string;
  menuType: number;
  target: number;
  scope: number;
  path: string;
  component: string;
  permCode: string;
  icon: string;
  sort: number;
  visible: number;
  status: number;
  remark: string;
}

const emptyForm: MenuForm = {
  menuName: '',
  menuType: 1,
  target: 1,
  scope: 2,
  path: '',
  component: '',
  permCode: '',
  icon: '',
  sort: 0,
  visible: 0,
  status: 0,
  remark: '',
};

const formVisible = ref(false);
const formRef = ref<FormInstance>();
const editingId = ref<number | null>(null);
const parentId = ref(0);
const parent = ref<MenuNode | null>(null);
/** 建后不可改的四项在修改态只读展示,不给用户改坏的机会。 */
const immutable = ref<{ menuType: number; target: number; scope: number; permCode: string }>({
  menuType: 1,
  target: 1,
  scope: 2,
  permCode: '',
});
const editingSystem = ref(false);
const saving = ref(false);
const form = reactive<MenuForm>({ ...emptyForm });

/** 类型/展示端/归属面的可选项由父节点决定,与 MenuService.validateParent 同口径。 */
const typeOptions = computed(() => {
  const p = parent.value;
  if (!p) return MENU_TYPES.filter((item) => item.value === 1);
  if (p.menuType === 1) return MENU_TYPES.filter((item) => item.value === 1 || item.value === 2);
  return MENU_TYPES.filter((item) => item.value === 2 || item.value === 3);
});

/**
 * 展示端可选项:按钮恒站内(后端只收站内按钮,且必须带权限码);父在大屏树里就只能继续大屏,
 * 父在站内树里可选站内或外链;根层只给站内与大屏——外链不做根,它不能挂下级,而空目录会被导航剪掉。
 */
const targetOptions = computed(() => {
  const p = parent.value;
  if (form.menuType === 3) return TARGETS.filter((item) => item.value === 1);
  if (p && p.target === 2) return TARGETS.filter((item) => item.value === 2);
  if (p) return TARGETS.filter((item) => item.value === 1 || item.value === 3);
  return TARGETS.filter((item) => item.value === 1 || item.value === 2);
});

const scopeOptions = computed(() => {
  const p = parent.value;
  if (!p) return SCOPES;
  return SCOPES.filter((item) => item.value === p.scope);
});

const parentLabel = computed(() =>
  parent.value ? parent.value.menuName : (parentId.value === 0 ? '根节点' : (names.value.get(parentId.value) ?? '—')),
);

/** 字段显隐与必填完全由三元组推出来,口径抄 MenuService.validateShape:目录不配码与组件,菜单两者都配,外链两者都不配。 */
const isDir = computed(() => currentType.value === 1);
const isButton = computed(() => currentType.value === 3);
const isExternal = computed(() => currentTarget.value === 3);
const needComponent = computed(() => currentType.value === 2 && !isExternal.value);
const needPermCode = computed(() => !isDir.value && !isExternal.value);

/** 表单里可能正在改类型,取值统一从这里出,免得模板重复三元。 */
const currentType = computed(() => (editingId.value === null ? form.menuType : immutable.value.menuType));
const currentTarget = computed(() =>
  editingId.value === null ? form.target : immutable.value.target,
);

const rules = computed<FormRules<MenuForm>>(() => ({
  menuName: [
    { required: true, message: '请输入菜单名称', trigger: 'blur' },
    { max: 50, message: '菜单名称过长', trigger: 'blur' },
  ],
  path: needComponent.value || isExternal.value
    ? [{ required: true, message: '菜单必须配置路由/地址', trigger: 'blur' }, { max: 500, message: '路由/地址过长', trigger: 'blur' }]
    : [{ max: 500, message: '路由/地址过长', trigger: 'blur' }],
  component: needComponent.value
    ? [
        { required: true, message: '菜单必须配置前端组件', trigger: 'blur' },
        { max: 255, message: '组件路径过长', trigger: 'blur' },
      ]
    : [],
  permCode: needPermCode.value
    ? [
        { required: true, message: '必须配置权限码', trigger: 'blur' },
        {
          pattern: /^(platform|system):[a-z0-9]+(?:-[a-z0-9]+)*:[a-z0-9]+(?:-[a-z0-9]+)*$/,
          message: '权限码须为 面:模块:动作 三段式,动作用小写字母/数字/连字符',
          trigger: 'blur',
        },
      ]
    : [],
  icon: [{ max: 100, message: '图标名过长', trigger: 'blur' }],
  remark: [{ max: 500, message: '备注过长', trigger: 'blur' }],
}));

/** 从行内"新增下级"进来时带上父:面直接继承,展示端与类型也按父给一个合法默认(页面下最常见的下一步是按钮)。 */
function openCreate(root?: MenuNode): void {
  editingId.value = null;
  editingSystem.value = false;
  parent.value = root ?? null;
  parentId.value = root?.menuId ?? 0;
  Object.assign(form, emptyForm);
  if (root) {
    form.scope = root.scope;
    form.target = root.target === 2 ? 2 : 1;
    form.menuType = root.menuType === 1 ? 1 : 3;
  }
  formVisible.value = true;
}

/**
 * 修改态不换父,所以 parent 留空、只留 parentId 供"上级"一栏按 names 反查名称;
 * 四项不可改的值另存 immutable,供只读展示与表单推导用(按钮的 visible 直接压回 0)。
 */
function openEdit(row: MenuNode): void {
  editingId.value = row.menuId;
  editingSystem.value = row.isSystem === 1;
  parent.value = null;
  parentId.value = row.parentId;
  immutable.value = { menuType: row.menuType, target: row.target, scope: row.scope, permCode: row.permCode ?? '' };
  Object.assign(form, {
    menuName: row.menuName,
    menuType: row.menuType,
    target: row.target,
    scope: row.scope,
    path: row.path ?? '',
    component: row.component ?? '',
    permCode: row.permCode ?? '',
    icon: row.icon ?? '',
    sort: row.sort,
    visible: row.menuType === 3 ? 0 : row.visible,
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
    // 空串转 null:后端把 null 当"未配置",清空字段就是这么传出去的(整表单提交,不存在"不传即不改")
    const text = (value: string): string | null => value.trim() || null;
    const shape = {
      menuName: form.menuName.trim(),
      path: text(form.path),
      component: text(form.component),
      icon: text(form.icon),
      sort: form.sort,
      remark: text(form.remark),
    };
    if (editingId.value === null) {
      await createMenu({
        ...shape,
        parentId: parentId.value,
        menuType: form.menuType,
        target: form.target,
        scope: form.scope,
        permCode: text(form.permCode),
        // 按钮表单里不显示"侧栏显示",这一项可能留着上一次的 1,提交前压回 0 免得撞后端校验
        visible: form.menuType === 3 ? 0 : form.visible,
      });
      ElMessage.success('已新增菜单');
    } else {
      await updateMenu(editingId.value, {
        ...shape,
        visible: immutable.value.menuType === 3 ? 0 : form.visible,
        status: form.status,
      });
      ElMessage.success('已保存修改');
    }
    formVisible.value = false;
    await load();
  } finally {
    saving.value = false;
  }
}

/** 按钮与外链不给"新增下级":后端 validateParent 直接拒,这里提前收掉入口 */
function canHaveChild(node: MenuNode): boolean {
  return node.menuType !== 3 && node.target !== 3;
}

async function remove(row: MenuNode): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    `删除菜单 ${row.menuName}?内置菜单或存在下级时会被拒绝,该菜单的角色授权记录一并清除。`,
    '删除确认',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
  )
    .then(() => true)
    .catch(() => false);
  if (!confirmed) return;
  await deleteMenu(row.menuId);
  ElMessage.success('已删除');
  await load();
}

/** 行操作:权限不足或当前行不可操作的不占位,超过三个由 RowActions 折叠。 */
function actionsOf(row: MenuNode): RowAction[] {
  return [
    { key: 'child', label: '新增下级', show: auth.has('platform:menu:create') && canHaveChild(row), handler: () => openCreate(row) },
    { key: 'edit', label: '修改', show: auth.has('platform:menu:update'), handler: () => openEdit(row) },
    { key: 'delete', label: '删除', tone: 'danger', show: auth.has('platform:menu:delete') && row.isSystem !== 1, handler: () => { void remove(row); } },
  ];
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="eyebrow">
          NAVIGATION
        </div>
        <h1>菜单管理</h1>
      </div>
      <div class="head-actions">
        <ElButton
          v-permission="'platform:menu:create'"
          type="primary"
          @click="openCreate()"
        >
          <AppIcon
            name="plus"
            :size="15"
          />
          新增根目录
        </ElButton>
      </div>
    </div>

    <ElCard
      shadow="never"
      class="table-card"
    >
      <template #header>
        <div class="table-card-head">
          <h3>菜单结构 <span>共 {{ names.size }} 项</span></h3>
        </div>
      </template>

      <ElTable
        v-loading="loading"
        :data="tree"
        row-key="menuId"
        :tree-props="{ children: 'children' }"
        :default-expand-all="true"
      >
        <ElTableColumn
          prop="menuName"
          label="菜单名称"
          min-width="150"
        />
        <ElTableColumn
          label="类型"
          width="72"
          align="center"
        >
          <template #default="{ row }">
            <ElTag
              size="small"
              effect="plain"
              :type="row.menuType === 3 ? 'info' : row.menuType === 1 ? 'warning' : 'success'"
            >
              {{ labelOf(MENU_TYPES, row.menuType) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="归属"
          width="120"
          align="center"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ labelOf(SCOPES, row.scope) }} · {{ labelOf(TARGETS, row.target) }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="路由/地址"
          min-width="130"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ row.path ?? '—' }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="组件"
          min-width="130"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ row.component ?? '—' }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="权限码"
          min-width="150"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ row.permCode ?? '—' }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="内置"
          width="56"
          align="center"
        >
          <template #default="{ row }">
            <span v-if="row.isSystem === 1">是</span>
            <span v-else>—</span>
          </template>
        </ElTableColumn>
        <ElTableColumn
          prop="sort"
          label="排序"
          width="56"
          align="center"
        />
        <ElTableColumn
          label="状态"
          width="80"
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
            <RowActions :actions="actionsOf(row as MenuNode)" />
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <ElDialog
      v-model="formVisible"
      :title="editingId === null ? '新增菜单' : '修改菜单'"
      width="560px"
    >
      <ElForm
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="100px"
      >
        <ElFormItem label="上级">
          <ElInput
            :model-value="parentLabel"
            disabled
          />
        </ElFormItem>
        <ElFormItem
          v-if="editingId === null"
          label="类型"
          prop="menuType"
        >
          <ElSelect
            v-model="form.menuType"
            class="full"
          >
            <ElOption
              v-for="item in typeOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem
          v-else
          label="类型"
        >
          <ElInput
            :model-value="labelOf(MENU_TYPES, immutable.menuType)"
            disabled
          />
        </ElFormItem>

        <ElFormItem
          v-if="editingId === null"
          label="展示端"
          prop="target"
        >
          <ElSelect
            v-model="form.target"
            class="full"
          >
            <ElOption
              v-for="item in targetOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem
          v-else
          label="展示端"
        >
          <ElInput
            :model-value="labelOf(TARGETS, immutable.target)"
            disabled
          />
        </ElFormItem>

        <ElFormItem
          v-if="editingId === null"
          label="归属面"
          prop="scope"
        >
          <ElSelect
            v-model="form.scope"
            class="full"
          >
            <ElOption
              v-for="item in scopeOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem
          v-else
          label="归属面"
        >
          <ElInput
            :model-value="labelOf(SCOPES, immutable.scope)"
            disabled
          />
        </ElFormItem>

        <ElFormItem
          label="菜单名称"
          prop="menuName"
        >
          <ElInput v-model="form.menuName" />
        </ElFormItem>

        <ElFormItem
          v-if="!isButton"
          label="路由/地址"
          prop="path"
        >
          <ElInput
            v-model="form.path"
            :placeholder="isExternal ? 'https:// 开头的完整地址' : '/system/user'"
          />
        </ElFormItem>

        <ElFormItem
          v-if="needComponent"
          label="前端组件"
          prop="component"
        >
          <ElInput
            v-model="form.component"
            placeholder="src/views 下的相对路径,如 system/user/index"
          />
        </ElFormItem>

        <ElFormItem
          v-if="needPermCode"
          label="权限码"
          prop="permCode"
        >
          <ElInput
            v-model="form.permCode"
            :disabled="editingId !== null"
            :placeholder="editingId === null ? `${form.scope === 1 ? 'platform' : 'system'}:模块:动作` : '建后不可修改'"
          />
        </ElFormItem>
        <ElFormItem
          v-else-if="editingId !== null && immutable.permCode"
          label="权限码"
        >
          <ElInput
            :model-value="immutable.permCode"
            disabled
          />
        </ElFormItem>

        <ElFormItem
          v-if="!isButton"
          label="图标"
          prop="icon"
        >
          <ElInput
            v-model="form.icon"
            placeholder="Element Plus 图标名,如 User"
          />
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
          v-if="!isButton"
          label="侧栏显示"
        >
          <ElSwitch
            v-model="form.visible"
            :active-value="0"
            :inactive-value="1"
            inline-prompt
            active-text="显示"
            inactive-text="隐藏"
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
            :disabled="editingSystem"
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
