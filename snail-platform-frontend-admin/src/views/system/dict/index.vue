<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import {
  createDictData,
  createDictType,
  deleteDictData,
  deleteDictType,
  pageDictData,
  pageDictTypes,
  updateDictData,
  updateDictType,
  type DictDataQuery,
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
const activeType = ref<DictTypeVO | null>(null);

const typeFormVisible = ref(false);
const typeFormRef = ref<FormInstance>();
const typeEditingId = ref<number | null>(null);
const typeSaving = ref(false);
const typeForm = reactive({ dictName: '', dictType: '', status: 0, remark: '' });

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

/** 新增与修改共用一张表单:标识列只在新增态可填,状态开关只在修改态渲染。 */
function openTypeCreate(): void {
  typeEditingId.value = null;
  Object.assign(typeForm, { dictName: '', dictType: '', status: 0, remark: '' });
  typeFormVisible.value = true;
}

function openTypeEdit(row: DictTypeVO): void {
  typeEditingId.value = row.dictId;
  Object.assign(typeForm, {
    dictName: row.dictName,
    dictType: row.dictType,
    status: row.status,
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
      // 新增载荷不带 status:后端恒建为正常,开关本来也只在修改态渲染
      await createDictType({
        dictName: typeForm.dictName.trim(),
        dictType: typeForm.dictType.trim(),
        remark: typeForm.remark.trim() || null,
      });
      ElMessage.success('已新增字典类型');
    } else {
      // 空备注折成 null 再提交:后端 remark 是无条件 set,把清空表达成 null 而不是空串
      await updateDictType(typeEditingId.value, {
        dictName: typeForm.dictName.trim(),
        status: typeForm.status,
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

async function removeType(row: DictTypeVO): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    `删除字典类型 ${row.dictName}(${row.dictType})?该类型下的字典项一并删除,类型标识此后终身占用、不能同码重建。`,
    '删除确认',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
  )
    .then(() => true)
    .catch(() => false);
  if (!confirmed) return;
  await deleteDictType(row.dictId);
  // 删的正是右栏当前选中的类型:先把选中态与残留行清掉,否则表头还挂着已删类型的名字
  if (activeType.value?.dictId === row.dictId) {
    activeType.value = null;
    dataList.rows.value = [];
    dataList.total.value = 0;
  }
  ElMessage.success('已删除');
  await typeList.refresh();
}

/** 选中类型即换右侧字典项列表;类型停用不影响读,只影响业务侧下拉。 */
function selectType(row: DictTypeVO | null): void {
  if (!row) return;
  activeType.value = row;
  dataQuery.dictLabel = '';
  dataQuery.status = null;
  void dataList.search();
}

/* -------------------------------- 字典数据 -------------------------------- */

const dataQuery = reactive<Omit<DictDataQuery, 'dictId'>>({ dictLabel: '', status: null });
// dictId 从选中行现取,不进 query:类型没选中时直接回空页,不发请求
const dataList = usePageList<DictDataVO>((page) => {
  if (!activeType.value) return Promise.resolve({ rows: [], total: 0 });
  return pageDictData({ dictId: activeType.value.dictId, ...dataQuery, ...page });
});

const dataFormVisible = ref(false);
const dataFormRef = ref<FormInstance>();
const dataEditingId = ref<number | null>(null);
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

/** 未选中类型时按钮已禁用,这里再判一次:提交路径上 dictId 是必填项,不能靠界面兜。 */
function openDataCreate(): void {
  if (!activeType.value) {
    ElMessage.warning('请先选择字典类型');
    return;
  }
  dataEditingId.value = null;
  Object.assign(dataForm, { dictLabel: '', dictValue: '', sort: 0, status: 0, remark: '' });
  dataFormVisible.value = true;
}

function openDataEdit(row: DictDataVO): void {
  dataEditingId.value = row.dictDataId;
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
      if (!activeType.value) return;
      await createDictData({
        dictId: activeType.value.dictId,
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
    await dataList.refresh();
  } finally {
    dataSaving.value = false;
  }
}

async function removeData(row: DictDataVO): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    `删除字典项 ${row.dictLabel}(${row.dictValue})?`,
    '删除确认',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
  )
    .then(() => true)
    .catch(() => false);
  if (!confirmed) return;
  await deleteDictData(row.dictDataId);
  ElMessage.success('已删除');
  await dataList.refresh();
}

onMounted(typeList.refresh);
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="eyebrow">
          DATA DICTIONARY
        </div>
        <h1>字典管理</h1>
      </div>
    </div>

    <ElRow :gutter="16">
      <ElCol
        :xs="24"
        :md="11"
      >
        <ElCard
          shadow="never"
          class="panel"
        >
          <template #header>
            <div class="table-card-head">
              <h3>字典类型 <span>共 {{ typeList.total.value }} 个</span></h3>
              <div class="toolbar-actions">
                <ElButton
                  v-permission="'system:dict:create'"
                  type="primary"
                  size="small"
                  @click="openTypeCreate"
                >
                  新增类型
                </ElButton>
              </div>
            </div>
          </template>

          <ElForm
            inline
            :model="typeQuery"
            class="filter-form"
            @submit.prevent="typeList.search"
          >
            <ElFormItem label="名称">
              <ElInput
                v-model="typeQuery.dictName"
                clearable
                placeholder="模糊匹配"
                style="width: 140px"
                @keyup.enter="typeList.search"
              />
            </ElFormItem>
            <ElFormItem label="标识">
              <ElInput
                v-model="typeQuery.dictType"
                clearable
                placeholder="模糊匹配"
                style="width: 140px"
                @keyup.enter="typeList.search"
              />
            </ElFormItem>
            <ElFormItem>
              <ElButton
                type="primary"
                @click="typeList.search"
              >
                查询
              </ElButton>
            </ElFormItem>
          </ElForm>

          <ElTable
            v-loading="typeList.loading.value"
            :data="typeList.rows.value"
            row-key="dictId"
            highlight-current-row
            @current-change="selectType"
          >
            <ElTableColumn
              prop="dictName"
              label="名称"
              min-width="120"
            />
            <ElTableColumn
              prop="dictType"
              label="标识"
              min-width="140"
            />
            <ElTableColumn
              label="状态"
              width="86"
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
              width="120"
              align="center"
              fixed="right"
            >
              <template #default="{ row }">
                <ElButton
                  v-if="auth.has('system:dict:update')"
                  link
                  type="primary"
                  @click="openTypeEdit(row as DictTypeVO)"
                >
                  修改
                </ElButton>
                <ElButton
                  v-if="auth.has('system:dict:delete')"
                  link
                  type="danger"
                  @click="removeType(row as DictTypeVO)"
                >
                  删除
                </ElButton>
              </template>
            </ElTableColumn>
          </ElTable>

          <ElPagination
            v-model:current-page="typeList.pageNum.value"
            v-model:page-size="typeList.pageSize.value"
            :total="typeList.total.value"
            layout="total, prev, pager, next"
            class="pager"
            @change="typeList.refresh"
          />
        </ElCard>
      </ElCol>

      <ElCol
        :xs="24"
        :md="13"
      >
        <ElCard
          shadow="never"
          class="panel"
        >
          <template #header>
            <div class="table-card-head">
              <h3>字典项{{ activeType ? ` · ${activeType.dictName}` : '' }}</h3>
              <div class="toolbar-actions">
                <ElButton
                  v-permission="'system:dict:create'"
                  type="primary"
                  size="small"
                  :disabled="!activeType"
                  @click="openDataCreate"
                >
                  新增字典项
                </ElButton>
              </div>
            </div>
          </template>

          <ElEmpty
            v-if="!activeType"
            description="选择左侧字典类型"
            :image-size="60"
          />
          <template v-else>
            <ElForm
              inline
              :model="dataQuery"
              class="filter-form"
              @submit.prevent="dataList.search"
            >
              <ElFormItem label="标签">
                <ElInput
                  v-model="dataQuery.dictLabel"
                  clearable
                  placeholder="模糊匹配"
                  style="width: 160px"
                  @keyup.enter="dataList.search"
                />
              </ElFormItem>
              <ElFormItem label="状态">
                <ElSelect
                  v-model="dataQuery.status"
                  clearable
                  placeholder="不限"
                  style="width: 110px"
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
                <ElButton
                  type="primary"
                  @click="dataList.search"
                >
                  查询
                </ElButton>
              </ElFormItem>
            </ElForm>

            <ElTable
              v-loading="dataList.loading.value"
              :data="dataList.rows.value"
              row-key="dictDataId"
            >
              <ElTableColumn
                prop="dictLabel"
                label="标签"
                min-width="120"
              />
              <ElTableColumn
                prop="dictValue"
                label="值"
                min-width="120"
              />
              <ElTableColumn
                prop="sort"
                label="排序"
                width="80"
                align="center"
              />
              <ElTableColumn
                label="状态"
                width="86"
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
                width="120"
                align="center"
                fixed="right"
              >
                <template #default="{ row }">
                  <ElButton
                    v-if="auth.has('system:dict:update')"
                    link
                    type="primary"
                    @click="openDataEdit(row as DictDataVO)"
                  >
                    修改
                  </ElButton>
                  <ElButton
                    v-if="auth.has('system:dict:delete')"
                    link
                    type="danger"
                    @click="removeData(row as DictDataVO)"
                  >
                    删除
                  </ElButton>
                </template>
              </ElTableColumn>
            </ElTable>

            <ElPagination
              v-model:current-page="dataList.pageNum.value"
              v-model:page-size="dataList.pageSize.value"
              :total="dataList.total.value"
              layout="total, prev, pager, next"
              class="pager"
              @change="dataList.refresh"
            />
          </template>
        </ElCard>
      </ElCol>
    </ElRow>

    <ElDialog
      v-model="typeFormVisible"
      :title="typeEditingId === null ? '新增字典类型' : '修改字典类型'"
      width="460px"
    >
      <ElForm
        ref="typeFormRef"
        :model="typeForm"
        :rules="typeRules"
        label-width="88px"
      >
        <ElFormItem
          label="字典名称"
          prop="dictName"
        >
          <ElInput v-model="typeForm.dictName" />
        </ElFormItem>
        <ElFormItem
          label="类型标识"
          prop="dictType"
        >
          <ElInput
            v-model="typeForm.dictType"
            :disabled="typeEditingId !== null"
            :placeholder="typeEditingId === null ? '如 sys_notice_type' : '建后不可修改'"
          />
        </ElFormItem>
        <ElFormItem
          v-if="typeEditingId !== null"
          label="状态"
        >
          <ElSwitch
            v-model="typeForm.status"
            :active-value="0"
            :inactive-value="1"
            inline-prompt
            active-text="正常"
            inactive-text="停用"
          />
          <span>停用只挡业务侧按类型取字典项,本页列表与已有数据不受影响。</span>
        </ElFormItem>
        <ElFormItem
          label="备注"
          prop="remark"
        >
          <ElInput
            v-model="typeForm.remark"
            type="textarea"
            :rows="2"
            maxlength="500"
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
      :title="dataEditingId === null ? '新增字典项' : '修改字典项'"
      width="460px"
    >
      <ElForm
        ref="dataFormRef"
        :model="dataForm"
        :rules="dataRules"
        label-width="88px"
      >
        <ElFormItem
          label="字典标签"
          prop="dictLabel"
        >
          <ElInput v-model="dataForm.dictLabel" />
        </ElFormItem>
        <ElFormItem
          label="字典值"
          prop="dictValue"
        >
          <ElInput
            v-model="dataForm.dictValue"
            :disabled="dataEditingId !== null"
            :placeholder="dataEditingId === null ? '业务侧存储的值' : '建后不可修改'"
          />
        </ElFormItem>
        <ElFormItem
          label="排序"
          prop="sort"
        >
          <ElInputNumber
            v-model="dataForm.sort"
            :min="0"
            :precision="0"
            controls-position="right"
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
        <ElFormItem
          label="备注"
          prop="remark"
        >
          <ElInput
            v-model="dataForm.remark"
            type="textarea"
            :rows="2"
            maxlength="500"
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
.panel {
  margin-bottom: 16px;
}

.panel .el-table {
  margin-top: 4px;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
