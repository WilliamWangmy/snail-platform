<script setup lang="ts">
/** 组件名与路由 meta.viewName 对应,多页签 KeepAlive include 依此缓存。 */
defineOptions({ name: 'SystemDict' });
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
import { confirmAction } from '@/composables/useConfirmAction';
import {
  changeDictTypeStatus,
  createDictData,
  createDictType,
  deleteDictData,
  deleteDictType,
  pageDictData,
  pageDictTypes,
  updateDictData,
  updateDictType,
  type DictDataVO,
  type DictTypeQuery,
  type DictTypeVO,
} from '@/api/dict';
import { usePageList } from '@/composables/usePageList';
import { useAuthStore } from '@/stores/auth';

const auth = useAuthStore();

/* -------------------------------- 字典类型 -------------------------------- */

const typeQuery = reactive<DictTypeQuery>({ dictName: '', dictType: '', status: null });
const typeList = usePageList<DictTypeVO>((page) => pageDictTypes({ ...typeQuery, ...page }));

function resetTypeQuery(): void {
  Object.assign(typeQuery, { dictName: '', dictType: '', status: null });
  void typeList.search();
}

const typeFormVisible = ref(false);
const typeFormRef = ref<FormInstance>();
const typeEditingId = ref<number | null>(null);
const typeSaving = ref(false);
const typeForm = reactive({ dictName: '', dictType: '', remark: '' });

/** 与 DictTypeCreateRequest 同口径:标识建后不可改,格式限小写字母开头的下划线串。 */
const typeRules: FormRules<typeof typeForm> = {
  dictName: [
    { required: true, message: '请输入字典名称', trigger: 'blur' },
    { max: 100, message: '字典名称过长', trigger: 'blur' },
  ],
  dictType: [
    { required: true, message: '请输入字典类型标识', trigger: 'blur' },
    { min: 2, max: 100, message: '字典类型标识长度 2-100', trigger: 'blur' },
    { pattern: /^[a-z][a-z0-9_]*$/, message: '只允许小写字母、数字、下划线且以字母开头', trigger: 'blur' },
  ],
  remark: [{ max: 500, message: '备注过长', trigger: 'blur' }],
};

/** 新增与修改共用一张表单:标识列只在新增态可填,状态不在表单里——启停是行操作。 */
function openTypeCreate(): void {
  typeEditingId.value = null;
  Object.assign(typeForm, { dictName: '', dictType: '', remark: '' });
  typeFormVisible.value = true;
}

function openTypeEdit(row: DictTypeVO): void {
  typeEditingId.value = row.dictId;
  Object.assign(typeForm, {
    dictName: row.dictName,
    dictType: row.dictType,
    remark: row.remark ?? '',
  });
  typeFormVisible.value = true;
}

async function submitType(): Promise<void> {
  const valid = await typeFormRef.value?.validate().catch(() => false);
  if (!valid) return;
  typeSaving.value = true;
  try {
    if (typeEditingId.value === null) {
      // 新增不带 status:后端恒建为正常
      await createDictType({
        dictName: typeForm.dictName.trim(),
        dictType: typeForm.dictType.trim(),
        remark: typeForm.remark.trim() || null,
      });
      ElMessage.success('已新增字典类型');
    } else {
      // 修改不带 status:停用只能走行操作,和改名字各自留一条日志。
      // 空备注折成 null 再提交:后端 remark 是无条件 set,把清空表达成 null 而不是空串
      await updateDictType(typeEditingId.value, {
        dictName: typeForm.dictName.trim(),
        remark: typeForm.remark.trim() || null,
      });
      ElMessage.success('已保存修改');
    }
    typeFormVisible.value = false;
    await typeList.refresh();
  } finally {
    typeSaving.value = false;
  }
}

/**
 * 启停走独立端点,弹窗里没有第二条通路,停用必然过这道确认;确认后就地翻牌,不重拉整页。
 * 后端同状态会提前返回,重复点不会多刷一次 dictItem 缓存。
 */
async function toggleTypeStatus(row: DictTypeVO): Promise<void> {
  const next = row.status === 0 ? 1 : 0;
  if (next === 1) {
    const goOn = await confirmAction(
      `停用字典类型 ${row.dictName}(${row.dictType})?业务页面按该类型取的下拉会立即取不到并报错;类型下的字典项与已落库的历史值都不受影响。`,
      () => Promise.resolve(),
      { title: '停用确认', confirmText: '停用' },
    );
    if (!goOn) return;
  }
  await changeDictTypeStatus(row.dictId, next);
  row.status = next;
  ElMessage.success(next === 0 ? '已启用' : '已停用');
}

async function removeType(row: DictTypeVO): Promise<void> {
  await confirmAction(
    `删除字典类型 ${row.dictName}(${row.dictType})?该类型下的字典项一并删除,类型标识此后终身占用、不能同码重建。`,
    () => deleteDictType(row.dictId),
    { title: '删除确认', confirmText: '删除', successText: '已删除' },
  );
  if (activeTypeId.value === row.dictId) activeTypeId.value = null;
  delete itemsMap.value[row.dictId];
  await typeList.refresh();
}

/* --------------------------- 选中类型下的字典项 --------------------------- */

/** 上方类型卡选中谁,下方就展示谁的字典项;缓存按 dictId 存,切回来不闪白 */
const activeTypeId = ref<number | null>(null);
const itemsMap = ref<Record<number, DictDataVO[]>>({});
const itemsLoading = ref<Record<number, boolean>>({});

const activeType = computed<DictTypeVO | null>(
  () => typeList.rows.value.find((row) => row.dictId === activeTypeId.value) ?? null,
);

function itemRows(dictId: number | null): DictDataVO[] {
  return dictId === null ? [] : itemsMap.value[dictId] ?? [];
}

function itemLoading(dictId: number | null): boolean {
  return dictId === null ? false : itemsLoading.value[dictId] ?? false;
}

/** 字典项单类型通常几十条,一页 100 已覆盖;增删改后直接再调一次即就地刷新 */
async function loadItems(dictId: number): Promise<void> {
  itemsLoading.value = { ...itemsLoading.value, [dictId]: true };
  try {
    const { rows } = await pageDictData({ dictId, pageNum: 1, pageSize: 100 });
    itemsMap.value = { ...itemsMap.value, [dictId]: rows };
  } finally {
    itemsLoading.value = { ...itemsLoading.value, [dictId]: false };
  }
}

/** 分页/查询换了一批类型后,自动落在第一行,保证下方永远有内容 */
watch(
  () => typeList.rows.value,
  (rows) => {
    if (rows.length === 0) {
      activeTypeId.value = null;
      return;
    }
    if (!rows.some((row) => row.dictId === activeTypeId.value)) {
      const first = rows[0]!;
      activeTypeId.value = first.dictId;
      void loadItems(first.dictId);
    }
  },
  { immediate: true },
);

function selectType(row: DictTypeVO): void {
  if (activeTypeId.value === row.dictId) return;
  activeTypeId.value = row.dictId;
  void loadItems(row.dictId);
}

const offTypeCount = computed(() => typeList.rows.value.filter((row) => row.status !== 0).length);

/** 字典值常被复制到代码或配置里,给个一键复制 */
async function copyValue(value: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(value);
    ElMessage.success('字典值已复制');
  } catch {
    ElMessage.warning('复制失败,请手动选中复制');
  }
}

const dataFormVisible = ref(false);
const dataFormRef = ref<FormInstance>();
const dataEditingId = ref<number | null>(null);
/** 编辑目标的归属类型:修改契约不带 dictId,刷新字典项时要用 */
const dataFormDictId = ref(0);
const dataSaving = ref(false);
const dataForm = reactive({ dictLabel: '', dictValue: '', sort: 0, status: 0, remark: '' });

const dataRules: FormRules<typeof dataForm> = {
  dictLabel: [
    { required: true, message: '请输入字典标签', trigger: 'blur' },
    { max: 100, message: '字典标签过长', trigger: 'blur' },
  ],
  dictValue: [
    { required: true, message: '请输入字典值', trigger: 'blur' },
    { max: 100, message: '字典值过长', trigger: 'blur' },
  ],
  remark: [{ max: 500, message: '备注过长', trigger: 'blur' }],
};

function openItemCreate(): void {
  if (activeTypeId.value === null) return;
  dataEditingId.value = null;
  dataFormDictId.value = activeTypeId.value;
  Object.assign(dataForm, { dictLabel: '', dictValue: '', sort: 0, status: 0, remark: '' });
  dataFormVisible.value = true;
}

function openDataEdit(row: DictDataVO): void {
  dataEditingId.value = row.dictDataId;
  dataFormDictId.value = row.dictId;
  Object.assign(dataForm, {
    dictLabel: row.dictLabel,
    dictValue: row.dictValue,
    sort: row.sort,
    status: row.status,
    remark: row.remark ?? '',
  });
  dataFormVisible.value = true;
}

async function submitData(): Promise<void> {
  const valid = await dataFormRef.value?.validate().catch(() => false);
  if (!valid) return;
  dataSaving.value = true;
  try {
    if (dataEditingId.value === null) {
      await createDictData({
        dictId: dataFormDictId.value,
        dictLabel: dataForm.dictLabel.trim(),
        dictValue: dataForm.dictValue.trim(),
        sort: dataForm.sort,
        remark: dataForm.remark.trim() || null,
      });
      ElMessage.success('已新增字典项');
    } else {
      // 修改载荷不带 dictValue 与 dictId:表单里那格只读,值和归属一样不给改的路径
      await updateDictData(dataEditingId.value, {
        dictLabel: dataForm.dictLabel.trim(),
        sort: dataForm.sort,
        status: dataForm.status,
        remark: dataForm.remark.trim() || null,
      });
      ElMessage.success('已保存修改');
    }
    dataFormVisible.value = false;
    await loadItems(dataFormDictId.value);
  } finally {
    dataSaving.value = false;
  }
}

async function removeData(row: DictDataVO): Promise<void> {
  await confirmAction(
    `删除字典项 ${row.dictLabel}(${row.dictValue})?`,
    () => deleteDictData(row.dictDataId),
    { title: '删除确认', confirmText: '删除', successText: '已删除' },
  );
  await loadItems(row.dictId);
}

/**
 * 字典项启停:没有独立 status 端点,走修改契约只翻 status,其余字段按行上现值原样带回。
 * 停用必须过确认(业务侧下拉立即少一项);确认后就地翻牌,row 是 itemsMap 缓存里的同一引用,不重拉。
 */
async function toggleItemStatus(row: DictDataVO): Promise<void> {
  const next = row.status === 0 ? 1 : 0;
  if (next === 1) {
    const goOn = await confirmAction(
      `停用字典项 ${row.dictLabel}(${row.dictValue})?业务页面按该类型取的下拉会立即少这一项;已落库的历史值不受影响。`,
      () => Promise.resolve(),
      { title: '停用确认', confirmText: '停用' },
    );
    if (!goOn) return;
  }
  await updateDictData(row.dictDataId, {
    dictLabel: row.dictLabel,
    sort: row.sort,
    status: next,
    remark: row.remark,
  });
  row.status = next;
  ElMessage.success(next === 0 ? '已启用' : '已停用');
}

onMounted(typeList.refresh);
</script>

<template>
  <div class="page dict-page">
    <div class="page-head">
      <div>
        <div class="eyebrow">
          DATA DICTIONARY
        </div>
        <h1>字典管理</h1>
        <p>集中维护系统下拉与枚举值,按类型归组,业务侧以标识取值。</p>
      </div>
      <div class="head-actions">
        <div class="stat-chips">
          <span class="stat-chip"><b>{{ typeList.total.value }}</b> 类型</span>
          <span class="stat-chip"><b>{{ itemRows(activeTypeId).length }}</b> 当前项</span>
          <span class="stat-chip"><b>{{ offTypeCount }}</b> 停用</span>
        </div>
        <ElButton
          v-permission="'system:dict:create'"
          type="primary"
          @click="openTypeCreate"
        >
          <AppIcon
            name="plus"
            :size="14"
          />
          新增类型
        </ElButton>
      </div>
    </div>

    <section class="type-section">
      <div class="section-cap">
        <div>
          <h2>字典类型</h2>
          <p>选中一个类型,下方展示它的字典项</p>
        </div>
        <div class="cap-tools">
          <ElInput
            v-model="typeQuery.dictName"
            clearable
            placeholder="名称"
            class="cap-input"
            @keyup.enter="typeList.search"
          >
            <template #prefix>
              <AppIcon
                name="search"
                :size="13"
              />
            </template>
          </ElInput>
          <ElInput
            v-model="typeQuery.dictType"
            clearable
            placeholder="标识"
            class="cap-input mono-input"
            @keyup.enter="typeList.search"
          />
          <ElButton @click="typeList.search">
            查询
          </ElButton>
          <ElButton
            plain
            @click="resetTypeQuery"
          >
            重置
          </ElButton>
        </div>
      </div>

      <div
        v-loading="typeList.loading.value"
        class="type-grid"
      >
        <button
          v-for="row in typeList.rows.value"
          :key="row.dictId"
          class="type-tile"
          :class="{ active: activeTypeId === row.dictId, off: row.status !== 0 }"
          type="button"
          @click="selectType(row)"
        >
          <span class="tile-ico">
            <AppIcon
              name="box"
              :size="16"
            />
          </span>
          <span class="tile-body">
            <span class="tile-name">{{ row.dictName }}</span>
            <code class="tile-code">{{ row.dictType }}</code>
          </span>
          <span
            class="tile-state"
            :class="{ off: row.status !== 0 }"
          >
            {{ row.status === 0 ? '正常' : '停用' }}
          </span>
        </button>
        <button
          v-if="auth.has('system:dict:create')"
          class="type-tile add-tile"
          type="button"
          @click="openTypeCreate"
        >
          <AppIcon
            name="plus"
            :size="18"
          />
          <span>新增类型</span>
        </button>
      </div>

      <div class="type-foot">
        <span>共 {{ typeList.total.value }} 个类型</span>
        <ElPagination
          v-model:current-page="typeList.pageNum.value"
          v-model:page-size="typeList.pageSize.value"
          :total="typeList.total.value"
          layout="prev, pager, next"
          small
          @change="typeList.refresh"
        />
      </div>
    </section>

    <section
      v-if="activeType"
      class="detail-section"
    >
      <div class="detail-hero">
        <span class="hero-ico">
          <AppIcon
            name="box"
            :size="22"
          />
        </span>
        <div class="hero-main">
          <div class="hero-line">
            <h2>{{ activeType.dictName }}</h2>
            <code class="hero-code">{{ activeType.dictType }}</code>
            <span
              class="status-pill"
              :class="activeType.status === 0 ? 'success' : 'info'"
            >
              {{ activeType.status === 0 ? '正常' : '停用' }}
            </span>
          </div>
          <p>该类型下的字典项用于业务侧下拉选项,标签展示给用户,值存进业务数据。</p>
        </div>
        <div class="hero-acts">
          <button
            v-if="auth.has('system:dict:update')"
            class="hero-btn"
            type="button"
            @click="openTypeEdit(activeType)"
          >
            <AppIcon
              name="edit"
              :size="13"
            />
            修改
          </button>
          <button
            v-if="auth.has('system:dict:update')"
            class="hero-btn"
            type="button"
            @click="toggleTypeStatus(activeType)"
          >
            <AppIcon
              :name="activeType.status === 0 ? 'eye-off' : 'check'"
              :size="13"
            />
            {{ activeType.status === 0 ? '停用' : '启用' }}
          </button>
          <button
            v-if="auth.has('system:dict:delete')"
            class="hero-btn danger"
            type="button"
            @click="removeType(activeType)"
          >
            <AppIcon
              name="trash"
              :size="13"
            />
            删除
          </button>
        </div>
      </div>

      <div class="item-cap">
        <div>
          <h3>字典项</h3>
          <span class="item-count">{{ itemRows(activeTypeId).length }} 项</span>
        </div>
        <ElButton
          v-if="auth.has('system:dict:create')"
          size="small"
          type="primary"
          plain
          @click="openItemCreate"
        >
          <AppIcon
            name="plus"
            :size="13"
          />
          新增字典项
        </ElButton>
      </div>

      <div
        v-loading="itemLoading(activeTypeId)"
        class="item-grid"
      >
        <article
          v-for="item in itemRows(activeTypeId)"
          :key="item.dictDataId"
          class="item-tile"
          :class="{ off: item.status !== 0 }"
        >
          <div class="tile-top">
            <span
              class="tile-state"
              :class="{ off: item.status !== 0 }"
            >
              <i class="state-dot" />
              {{ item.status === 0 ? '正常' : '停用' }}
            </span>
            <span class="tile-sort">排序 {{ item.sort }}</span>
          </div>
          <div class="tile-body">
            <span class="tile-cap">标签</span>
            <div class="tile-label">
              {{ item.dictLabel }}
            </div>
          </div>
          <div class="tile-value">
            <span class="value-cap">值</span>
            <code>{{ item.dictValue }}</code>
            <button
              class="copy-btn"
              type="button"
              title="复制字典值"
              @click="copyValue(item.dictValue)"
            >
              <AppIcon
                name="copy"
                :size="13"
              />
            </button>
          </div>
          <div class="tile-acts">
            <button
              v-if="auth.has('system:dict:update')"
              class="mini-btn"
              type="button"
              title="修改标签、排序与备注"
              @click="openDataEdit(item)"
            >
              <AppIcon
                name="edit"
                :size="12"
              />
              修改
            </button>
            <button
              v-if="auth.has('system:dict:update')"
              class="mini-btn toggle"
              :class="{ 'is-off': item.status !== 0 }"
              type="button"
              :title="item.status === 0 ? '停用后业务下拉不再出现' : '重新启用'"
              @click="toggleItemStatus(item)"
            >
              <AppIcon
                :name="item.status === 0 ? 'eye-off' : 'check'"
                :size="12"
              />
              {{ item.status === 0 ? '停用' : '启用' }}
            </button>
            <button
              v-if="auth.has('system:dict:delete')"
              class="mini-btn danger"
              type="button"
              title="删除字典项"
              @click="removeData(item)"
            >
              <AppIcon
                name="trash"
                :size="12"
              />
              删除
            </button>
          </div>
        </article>

        <button
          v-if="auth.has('system:dict:create')"
          class="item-tile add-item-tile"
          type="button"
          @click="openItemCreate"
        >
          <span class="add-circle">
            <AppIcon
              name="plus"
              :size="18"
            />
          </span>
          <b>新增字典项</b>
          <span>为当前类型添加一个可选项</span>
        </button>

        <div
          v-if="!itemLoading(activeTypeId) && itemRows(activeTypeId).length === 0"
          class="item-empty"
        >
          <AppIcon
            name="box"
            :size="24"
          />
          <b>该类型下暂无字典项</b>
          <span>点击右侧卡片添加第一个</span>
        </div>
      </div>
    </section>

    <section
      v-else
      class="blank-state"
    >
      <AppIcon
        name="box"
        :size="32"
      />
      <b>还没有字典类型</b>
      <span>调整上方筛选条件,或新增一个类型开始配置</span>
    </section>

    <ElDialog
      v-model="typeFormVisible"
      width="520px"
    >
      <template #header>
        <div class="dlg-head">
          <span class="dlg-ico">
            <AppIcon
              name="box"
              :size="16"
            />
          </span>
          <div class="dlg-titles">
            <strong>{{ typeEditingId === null ? '新增字典类型' : '修改字典类型' }}</strong>
            <small>类型标识是业务侧取参的 key,创建后不可修改</small>
          </div>
        </div>
      </template>
      <div
        v-if="typeEditingId === null"
        class="form-banner"
      >
        <AppIcon
          name="alert"
          :size="14"
        />
        <span>除备注外均为必填项;类型标识创建后终身占用,不可同码重建</span>
      </div>
      <ElForm
        ref="typeFormRef"
        :model="typeForm"
        :rules="typeRules"
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
            <span class="panel-title">基本信息</span>
            <span class="panel-hint">标识即业务侧取参的 key</span>
          </div>
          <div class="form-grid">
            <ElFormItem
              label="字典名称"
              prop="dictName"
            >
              <ElInput
                v-model="typeForm.dictName"
                placeholder="如:通知类型"
              />
            </ElFormItem>
            <ElFormItem
              label="类型标识"
              prop="dictType"
            >
              <ElInput
                v-model="typeForm.dictType"
                :disabled="typeEditingId !== null"
                :placeholder="typeEditingId === null ? '如 sys_notice_type' : '建后不可修改'"
                class="mono-input"
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
            v-model="typeForm.remark"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            placeholder="选填,最多 500 字"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="typeFormVisible = false">
          取消
        </ElButton>
        <ElButton
          type="primary"
          :loading="typeSaving"
          @click="submitType"
        >
          保存
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="dataFormVisible"
      width="520px"
    >
      <template #header>
        <div class="dlg-head">
          <span class="dlg-ico">
            <AppIcon
              name="receipt"
              :size="16"
            />
          </span>
          <div class="dlg-titles">
            <strong>{{ dataEditingId === null ? '新增字典项' : '修改字典项' }}</strong>
            <small>标签展示给用户,值存进业务数据</small>
          </div>
        </div>
      </template>
      <div
        v-if="dataEditingId === null"
        class="form-banner"
      >
        <AppIcon
          name="alert"
          :size="14"
        />
        <span>除备注外均为必填项;字典值建后不可修改,业务侧按它存储</span>
      </div>
      <ElForm
        ref="dataFormRef"
        :model="dataForm"
        :rules="dataRules"
        label-position="top"
      >
        <div class="form-panel">
          <div class="panel-head">
            <span class="panel-ico">
              <AppIcon
                name="receipt"
                :size="13"
              />
            </span>
            <span class="panel-title">基本信息</span>
            <span class="panel-hint">标签展示给用户,值存进业务数据</span>
          </div>
          <div class="form-grid">
            <ElFormItem
              label="字典标签"
              prop="dictLabel"
            >
              <ElInput
                v-model="dataForm.dictLabel"
                placeholder="如:站内公告"
              />
            </ElFormItem>
            <ElFormItem
              label="字典值"
              prop="dictValue"
            >
              <ElInput
                v-model="dataForm.dictValue"
                :disabled="dataEditingId !== null"
                :placeholder="dataEditingId === null ? '业务侧存储的值' : '建后不可修改'"
                class="mono-input"
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
                v-model="dataForm.sort"
                :min="0"
                :precision="0"
                controls-position="right"
                class="full"
              />
            </ElFormItem>
            <ElFormItem
              v-if="dataEditingId !== null"
              label="状态"
            >
              <ElSwitch
                v-model="dataForm.status"
                :active-value="0"
                :inactive-value="1"
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
            v-model="dataForm.remark"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            placeholder="选填,最多 500 字"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="dataFormVisible = false">
          取消
        </ElButton>
        <ElButton
          type="primary"
          :loading="dataSaving"
          @click="submitData"
        >
          保存
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<style scoped>
/* 上下两段式:上方选类型,下方看字典项;内容收窄居中留出呼吸感 */
.dict-page {
  width: 100%;
  max-width: 1240px;
  margin: 0 auto;
}

.stat-chips {
  display: flex;
  gap: 7px;
}

.stat-chip {
  padding: 5px 10px;
  border: 1px solid var(--app-line);
  border-radius: 99px;
  background: var(--app-card);
  color: var(--app-muted);
  font-size: 11px;
  white-space: nowrap;
}

.stat-chip b {
  margin-right: 3px;
  color: var(--app-ink);
  font-family: var(--app-font-mono);
  font-size: 12px;
}

.type-section,
.detail-section {
  padding: 18px 20px;
  border: 1px solid var(--app-line);
  border-radius: 16px;
  background: var(--app-card);
  box-shadow: var(--app-shadow);
}

.detail-section {
  margin-top: 16px;
}

.section-cap,
.item-cap {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}

.section-cap h2,
.item-cap h3 {
  margin: 0;
  color: var(--app-ink);
  font-size: 14px;
  font-weight: 650;
}

.section-cap p {
  margin: 4px 0 0;
  color: var(--app-muted);
  font-size: 11px;
}

.cap-tools {
  display: flex;
  align-items: center;
  gap: 8px;
}

.cap-input {
  width: 148px;
}

.cap-tools :deep(.el-input__wrapper) {
  border-radius: 9px;
  box-shadow: 0 0 0 1px var(--app-line) inset;
}

/* 类型瓦片:选中态实心主题色,形成明确视觉焦点 */
.type-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(206px, 1fr));
  gap: 11px;
  margin-top: 16px;
}

.type-tile {
  display: flex;
  align-items: center;
  gap: 11px;
  min-width: 0;
  padding: 12px 13px;
  border: 1px solid var(--app-line);
  border-radius: 12px;
  background: var(--app-soft);
  color: var(--app-ink);
  cursor: pointer;
  text-align: left;
  transition:
    border-color 0.18s,
    background-color 0.18s,
    box-shadow 0.18s;
}

.type-tile:hover {
  border-color: rgba(var(--app-primary-rgb), 0.4);
  box-shadow: var(--app-shadow);
}

.type-tile.active {
  border-color: transparent;
  background: var(--app-primary);
  box-shadow: 0 8px 20px rgba(var(--app-primary-rgb), 0.22);
  color: var(--app-on-primary);
}

.tile-ico {
  display: grid;
  width: 34px;
  height: 34px;
  flex: none;
  place-items: center;
  border-radius: 10px;
  background: rgba(var(--app-primary-rgb), 0.1);
  color: var(--app-primary);
}

.type-tile.active .tile-ico {
  background: rgba(255, 255, 255, 0.18);
  color: #fff;
}

.type-tile.off:not(.active) .tile-ico {
  background: var(--app-soft-2);
  color: var(--app-muted);
}

.tile-body {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.tile-name {
  overflow: hidden;
  font-size: 13px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tile-code {
  overflow: hidden;
  color: var(--app-muted);
  font-family: var(--app-font-mono);
  font-size: 10.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.type-tile.active .tile-code {
  color: rgba(255, 255, 255, 0.72);
}

.tile-state {
  flex: none;
  padding: 2px 7px;
  border-radius: 99px;
  background: rgba(var(--app-success-rgb), 0.12);
  color: var(--app-success);
  font-size: 10px;
}

.tile-state.off {
  background: var(--app-soft-2);
  color: var(--app-muted);
}

.type-tile.active .tile-state,
.type-tile.active .tile-state.off {
  background: rgba(255, 255, 255, 0.18);
  color: #fff;
}

.add-tile {
  align-items: center;
  justify-content: center;
  gap: 7px;
  border: 1px dashed rgba(var(--app-primary-rgb), 0.4);
  background: rgba(var(--app-primary-rgb), 0.03);
  color: var(--app-primary);
  font-size: 12px;
}

.add-tile:hover {
  background: rgba(var(--app-primary-rgb), 0.09);
}

.type-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--app-line);
  color: var(--app-muted);
  font-size: 11px;
}

/* 详情横幅:选中类型的身份区,操作集中在右侧 */
.detail-hero {
  display: flex;
  align-items: center;
  gap: 15px;
  padding: 16px 18px;
  border: 1px solid rgba(var(--app-primary-rgb), 0.18);
  border-radius: 13px;
  background: rgba(var(--app-primary-rgb), 0.06);
}

.hero-ico {
  display: grid;
  width: 46px;
  height: 46px;
  flex: none;
  place-items: center;
  border-radius: 13px;
  background: var(--app-primary);
  color: var(--app-on-primary);
}

.hero-main {
  flex: 1;
  min-width: 0;
}

.hero-line {
  display: flex;
  align-items: center;
  gap: 9px;
  flex-wrap: wrap;
}

.hero-line h2 {
  margin: 0;
  color: var(--app-ink);
  font-size: 19px;
  font-weight: 700;
  letter-spacing: -0.02em;
}

.hero-code {
  padding: 3px 8px;
  border: 1px solid var(--app-line);
  border-radius: 7px;
  background: var(--app-card);
  color: var(--app-muted);
  font-family: var(--app-font-mono);
  font-size: 11px;
}

.hero-main p {
  margin: 5px 0 0;
  color: var(--app-muted);
  font-size: 11.5px;
}

.hero-acts {
  display: flex;
  flex: none;
  gap: 5px;
}

.hero-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 6px 10px;
  border: 1px solid var(--app-line);
  border-radius: 8px;
  background: var(--app-card);
  color: var(--app-muted);
  cursor: pointer;
  font-size: 11.5px;
  white-space: nowrap;
  transition:
    color 0.16s,
    border-color 0.16s,
    background-color 0.16s;
}

.hero-btn:hover {
  border-color: rgba(var(--app-primary-rgb), 0.4);
  background: rgba(var(--app-primary-rgb), 0.08);
  color: var(--app-primary);
}

.hero-btn.danger:hover {
  border-color: rgba(var(--app-danger-rgb), 0.4);
  background: rgba(var(--app-danger-rgb), 0.08);
  color: var(--app-danger);
}

.item-cap {
  align-items: center;
  margin: 20px 0 12px;
}

.item-cap > div {
  display: flex;
  align-items: center;
  gap: 9px;
}

.item-count {
  padding: 2px 9px;
  border-radius: 99px;
  background: rgba(var(--app-primary-rgb), 0.1);
  color: var(--app-primary);
  font-family: var(--app-font-mono);
  font-size: 10.5px;
}

/* 字典项卡片网格:不再是表格行,每张卡自带值、状态与操作 */
.item-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(232px, 1fr));
  gap: 12px;
}

.item-tile {
  position: relative;
  display: flex;
  min-width: 0;
  flex-direction: column;
  padding: 14px 15px 11px;
  overflow: hidden;
  border: 1px solid var(--app-line);
  border-radius: 13px;
  background: var(--app-card);
  transition:
    border-color 0.18s,
    box-shadow 0.18s;
}

.item-tile::before {
  content: '';
  position: absolute;
  top: 0;
  left: 15px;
  width: 30px;
  height: 3px;
  border-radius: 0 0 3px 3px;
  background: var(--app-primary);
}

.item-tile.off::before {
  background: var(--app-line);
}

.item-tile:hover {
  border-color: rgba(var(--app-primary-rgb), 0.35);
  box-shadow: var(--app-shadow);
}

.tile-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

/* 状态呼吸点:绿点=正常,灰点=停用,比胶囊徽章更轻 */
.tile-state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--app-success);
  font-size: 10.5px;
}

.state-dot {
  width: 6px;
  height: 6px;
  flex: none;
  border-radius: 50%;
  background: var(--app-success);
  box-shadow: 0 0 0 3px rgba(var(--app-success-rgb), 0.14);
}

.tile-state.off {
  color: var(--app-muted);
}

.tile-state.off .state-dot {
  background: var(--app-muted);
  box-shadow: 0 0 0 3px var(--app-soft-2);
}

.tile-sort {
  color: var(--app-muted);
  font-family: var(--app-font-mono);
  font-size: 10.5px;
  letter-spacing: 0.05em;
}

/* 字段小标注:标明哪行是标签、哪条是值 */
.tile-cap {
  display: block;
  margin-top: 11px;
  color: var(--app-muted);
  font-size: 10px;
  letter-spacing: 0.14em;
}

.tile-label {
  margin-top: 2px;
  overflow: hidden;
  color: var(--app-ink);
  font-size: 15px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.item-tile.off .tile-label {
  color: var(--app-muted);
}

.tile-value {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 7px;
  padding: 5px 6px 5px 5px;
  border-radius: 8px;
  background: var(--app-soft-2);
}

.value-cap {
  flex: none;
  padding: 2px 6px;
  border-radius: 5px;
  background: rgba(var(--app-primary-rgb), 0.12);
  color: var(--app-primary);
  font-size: 10px;
}

.tile-value code {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--app-muted);
  font-family: var(--app-font-mono);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.copy-btn {
  display: grid;
  width: 22px;
  height: 22px;
  flex: none;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--app-muted);
  cursor: pointer;
  transition:
    color 0.16s,
    background-color 0.16s;
}

.copy-btn:hover {
  background: rgba(var(--app-primary-rgb), 0.13);
  color: var(--app-primary);
}

/* 操作行:独占卡片底部,虚线分隔;三个按钮等宽铺满,观感整齐 */
.tile-acts {
  display: flex;
  gap: 6px;
  margin-top: auto;
  padding-top: 10px;
  border-top: 1px dashed var(--app-line);
}

.mini-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 1;
  gap: 5px;
  height: 27px;
  min-width: 0;
  padding: 0 6px;
  border: 1px solid var(--app-line);
  border-radius: 8px;
  background: var(--app-card);
  color: var(--app-muted);
  cursor: pointer;
  font-size: 11px;
  white-space: nowrap;
  transition:
    color 0.16s,
    border-color 0.16s,
    background-color 0.16s;
}

.mini-btn:hover {
  border-color: rgba(var(--app-primary-rgb), 0.45);
  background: rgba(var(--app-primary-rgb), 0.07);
  color: var(--app-primary);
}

/* 停用悬停给警示橙,启用悬停给正常绿:动作方向一眼可辨 */
.mini-btn.toggle:not(.is-off):hover {
  border-color: rgba(var(--app-warning-rgb), 0.45);
  background: rgba(var(--app-warning-rgb), 0.08);
  color: var(--app-warning);
}

.mini-btn.toggle.is-off:hover {
  border-color: rgba(var(--app-success-rgb), 0.45);
  background: rgba(var(--app-success-rgb), 0.07);
  color: var(--app-success);
}

.mini-btn.danger:hover {
  border-color: rgba(var(--app-danger-rgb), 0.45);
  background: rgba(var(--app-danger-rgb), 0.07);
  color: var(--app-danger);
}

/* 停用卡片:值条降透明度,弱化但不藏信息 */
.item-tile.off .tile-value {
  opacity: 0.72;
}

.add-item-tile {
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 148px;
  border: 1px dashed rgba(var(--app-primary-rgb), 0.4);
  background: rgba(var(--app-primary-rgb), 0.03);
  color: var(--app-primary);
  cursor: pointer;
  font-size: 12px;
  transition:
    background-color 0.18s,
    border-color 0.18s;
}

.add-item-tile::before {
  display: none;
}

.add-item-tile:hover {
  border-color: var(--app-primary);
  background: rgba(var(--app-primary-rgb), 0.1);
}

.add-circle {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 50%;
  background: rgba(var(--app-primary-rgb), 0.12);
}

.add-item-tile b {
  font-size: 13px;
}

.add-item-tile > span:last-child {
  color: var(--app-muted);
  font-size: 11px;
}

.item-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 5px;
  grid-column: 1 / -1;
  padding: 30px 0;
  border: 1px dashed var(--app-line);
  border-radius: 13px;
  color: var(--app-muted);
  font-size: 11.5px;
}

.item-empty b {
  color: var(--app-ink);
  font-size: 13px;
}

.blank-state {
  display: flex;
  align-items: center;
  flex-direction: column;
  gap: 7px;
  margin-top: 16px;
  padding: 64px 0;
  border: 1px dashed var(--app-line);
  border-radius: 16px;
  background: var(--app-card);
  color: var(--app-muted);
  font-size: 12px;
}

.blank-state b {
  color: var(--app-ink);
  font-size: 14px;
}

/* 弹窗标题栏:与类型瓦片徽章同款 */
.dlg-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.dlg-ico {
  display: grid;
  width: 32px;
  height: 32px;
  flex: none;
  place-items: center;
  border-radius: 9px;
  background: rgba(var(--app-primary-rgb), 0.1);
  color: var(--app-primary);
}

.dlg-titles strong {
  display: block;
  color: var(--app-ink);
  font-size: 15px;
  font-weight: 650;
}

.dlg-titles small {
  display: block;
  margin-top: 2px;
  color: var(--app-muted);
  font-size: 11px;
}

@media (max-width: 760px) {
  .head-actions {
    align-items: flex-start;
    flex-direction: column;
  }

  .section-cap,
  .item-cap {
    align-items: flex-start;
    flex-direction: column;
  }

  .cap-tools {
    flex-wrap: wrap;
    width: 100%;
  }

  .cap-input {
    width: calc(50% - 4px);
  }

  .detail-hero {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .hero-acts {
    width: 100%;
  }
}
</style>
