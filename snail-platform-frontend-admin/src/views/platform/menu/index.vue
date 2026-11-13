<script setup lang="ts">
/** 组件名与路由 meta.viewName 对应,多页签 KeepAlive include 依此缓存。 */
defineOptions({ name: 'PlatformMenu' });
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules, type TableInstance } from 'element-plus';
import AppIcon from '@/components/AppIcon.vue';
import {
  createMenu,
  deleteMenu,
  fetchMenuTree,
  updateMenu,
  type MenuNode,
} from '@/api/menu';
import { useAuthStore } from '@/stores/auth';
import { ICON_OPTIONS, menuIcon } from '@/utils/icons';
import RowActions, { type RowAction } from '@/components/RowActions.vue';

const auth = useAuthStore();

/** 三张表的 value 与 sys_menu 的列注释同码(界面文案可不同);表单可选项由它们彼此推导,不是自由输入。 */
const MENU_TYPES = [
  { value: 1, label: '目录' },
  { value: 2, label: '菜单' },
  { value: 3, label: '按钮' },
];
/** 全量枚举供表格/只读展示;数据大屏(2)为内置固定菜单,新增表单不提供该选项。 */
const TARGETS = [
  { value: 1, label: '管理端' },
  { value: 2, label: '数据大屏' },
  { value: 3, label: '外链' },
  { value: 4, label: '流程端' },
  { value: 5, label: '移动端' },
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
/** 节点深度(根=0),供名称列按层级区分字重。 */
const depths = ref(new Map<number, number>());
const tableRef = ref<TableInstance>();

/** 树表默认收起,头部按钮一键切换全部层级;重取数据后回到收起态。 */
const allExpanded = ref(false);

function toggleAll(): void {
  const next = !allExpanded.value;
  allExpanded.value = next;
  const walk = (nodes: MenuNode[]): void => {
    for (const node of nodes) {
      tableRef.value?.toggleRowExpansion(node, next);
      if (node.children.length) walk(node.children);
    }
  };
  walk(tree.value);
}

/** 菜单树一次取全(含按钮);父节点名称由同一次遍历铺平,供表单只读展示。 */
async function load(): Promise<void> {
  loading.value = true;
  try {
    tree.value = await fetchMenuTree();
    const map = new Map<number, string>();
    const depthMap = new Map<number, number>();
    const walk = (nodes: MenuNode[], depth: number): void => {
      for (const node of nodes) {
        map.set(node.menuId, node.menuName);
        depthMap.set(node.menuId, depth);
        if (node.children.length) walk(node.children, depth + 1);
      }
    };
    walk(tree.value, 0);
    names.value = map;
    depths.value = depthMap;
  } finally {
    loading.value = false;
    allExpanded.value = false;
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
 * 展示端可选项(与 MenuService.validateParent/validateShape 同口径):
 * 按钮跟随父节点的展示端(后端要求子承父端);父在管理端树里可选管理端或外链;
 * 流程端/移动端各自成树,子节点只能承父端;根层只给 管理端/流程端/移动端——
 * 数据大屏树是内置固定菜单不做新增入口(见 canHaveChild),外链不做根,它不能挂下级。
 */
const targetOptions = computed(() => {
  const p = parent.value;
  if (form.menuType === 3) return TARGETS.filter((item) => item.value === (p?.target ?? 1));
  if (p?.target === 1) return TARGETS.filter((item) => item.value === 1 || item.value === 3);
  if (p) return TARGETS.filter((item) => item.value === p.target);
  return TARGETS.filter((item) => item.value === 1 || item.value === 4 || item.value === 5);
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

/** 从行内"新增下级"进来时带上父:面与展示端都承父(各端各成树);能进来的父只可能是管理端/流程端/移动端节点(大屏与外链已收掉入口)。 */
function openCreate(root?: MenuNode): void {
  editingId.value = null;
  editingSystem.value = false;
  parent.value = root ?? null;
  parentId.value = root?.menuId ?? 0;
  Object.assign(form, emptyForm);
  if (root) {
    form.scope = root.scope;
    form.target = root.target;
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

/** 按钮与外链不给"新增下级":后端 validateParent 直接拒;数据大屏树固定内置,也不给挂下级 */
function canHaveChild(node: MenuNode): boolean {
  return node.menuType !== 3 && node.target !== 3 && node.target !== 2;
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
          <ElButton
            size="small"
            @click="toggleAll"
          >
            <AppIcon
              :name="allExpanded ? 'arrow-up' : 'arrow-down'"
              :size="13"
            />
            {{ allExpanded ? '收起全部' : '展开全部' }}
          </ElButton>
        </div>
      </template>

      <ElTable
        ref="tableRef"
        v-loading="loading"
        :data="tree"
        row-key="menuId"
        :tree-props="{ children: 'children' }"
        :indent="22"
      >
        <ElTableColumn
          label="菜单名称"
          min-width="220"
        >
          <template #default="{ row }">
            <div
              class="name-cell"
              :class="{ leaf: row.menuType === 3 }"
            >
              <span
                class="name-ico"
                :class="{ btn: row.menuType === 3 }"
              >
                <AppIcon
                  :name="row.menuType === 3 ? 'zap' : menuIcon(row.icon)"
                  :size="13"
                />
              </span>
              <span
                class="name-text"
                :class="{ root: (depths.get(row.menuId) ?? 0) === 0 }"
              >{{ row.menuName }}</span>
              <ElTag
                v-if="row.isSystem === 1"
                size="small"
                type="warning"
                effect="plain"
              >
                内置
              </ElTag>
            </div>
          </template>
        </ElTableColumn>
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
            <span
              v-if="row.path"
              class="mono-cell"
            >{{ row.path }}</span>
            <span v-else>—</span>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="组件"
          min-width="130"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <span
              v-if="row.component"
              class="mono-cell"
            >{{ row.component }}</span>
            <span v-else>—</span>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="权限码"
          min-width="150"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <span
              v-if="row.permCode"
              class="mono-cell"
            >{{ row.permCode }}</span>
            <span v-else>—</span>
          </template>
        </ElTableColumn>
        <ElTableColumn
          prop="sort"
          label="排序"
          width="60"
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
        <span>类型、展示端、归属面与权限码创建后不可修改;可选项范围由上级节点决定</span>
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
                name="flow"
                :size="13"
              />
            </span>
            <span class="panel-title">归属与类型</span>
            <span
              v-if="editingId === null"
              class="panel-hint"
            >挂在不同上级,可选的类型与展示端不同</span>
          </div>
          <div class="form-grid">
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
          </div>
        </div>

        <div class="form-panel">
          <div class="panel-head">
            <span class="panel-ico">
              <AppIcon
                name="receipt"
                :size="13"
              />
            </span>
            <span class="panel-title">基本信息</span>
            <span
              v-if="editingId === null"
              class="panel-hint"
            >路由与组件仅菜单类型需要配置</span>
          </div>
          <div class="form-grid">
            <ElFormItem
              label="菜单名称"
              prop="menuName"
            >
              <ElInput
                v-model="form.menuName"
                placeholder="侧栏与面包屑展示的名称"
              />
            </ElFormItem>
            <ElFormItem
              v-if="!isButton"
              label="图标"
              prop="icon"
            >
              <ElSelect
                v-model="form.icon"
                clearable
                filterable
                placeholder="选择侧栏图标"
                class="full"
              >
                <template #prefix>
                  <AppIcon
                    :name="menuIcon(form.icon)"
                    :size="14"
                  />
                </template>
                <ElOption
                  v-for="item in ICON_OPTIONS"
                  :key="item.ep"
                  :label="item.ep"
                  :value="item.ep"
                >
                  <div class="icon-option">
                    <AppIcon
                      :name="item.icon"
                      :size="14"
                    />
                    <span>{{ item.ep }}</span>
                  </div>
                </ElOption>
              </ElSelect>
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
                class="mono-input"
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
          </div>
        </div>

        <div class="form-panel">
          <div class="panel-head">
            <span class="panel-ico">
              <AppIcon
                name="sliders"
                :size="13"
              />
            </span>
            <span class="panel-title">展示设置</span>
            <span class="panel-hint">数值越小越靠前</span>
          </div>
          <div class="form-grid">
            <ElFormItem
              label="排序"
              prop="sort"
            >
              <ElInputNumber
                v-model="form.sort"
                class="full"
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

/* 名称列:图标徽章 + 名称 + 内置徽标。
   必须是 inline-flex 而非 flex:EP 树表的层级缩进是插在 .cell 里的 inline span,
   块级 flex 会让缩进与展开箭头被挤到上一行,内容顶格,层级看起来失效。 */
.name-cell {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: calc(100% - 36px);
  vertical-align: middle;
}

.name-ico {
  display: grid;
  width: 24px;
  height: 24px;
  flex: none;
  place-items: center;
  border-radius: 6px;
  background: rgba(var(--app-primary-rgb), 0.1);
  color: var(--app-primary);
}

.name-ico.btn {
  background: var(--app-soft-2);
  color: var(--app-muted);
}

.name-text {
  min-width: 0;
  overflow: hidden;
  color: var(--app-muted);
  font-size: 12.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 根级(顶级目录)名称加重提亮,子级保持弱化,展开后层级一眼可辨 */
.name-text.root {
  color: var(--app-ink);
  font-size: 13px;
  font-weight: 650;
}

/* 展开箭头:跟随主题色,悬停浮出 */
.table-card :deep(.el-table__expand-icon) {
  border-radius: 6px;
  color: var(--app-muted);
  transition:
    color 0.16s,
    background-color 0.16s,
    transform 0.16s;
}

.table-card :deep(.el-table__expand-icon:hover) {
  background: var(--app-soft-2);
  color: var(--app-primary);
}

/* 路由/组件/权限码:等宽字体弱化展示,与名称列拉开层次 */
.mono-cell {
  color: var(--app-muted);
  font-family: var(--app-font-mono);
  font-size: 12px;
}

.icon-option {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
