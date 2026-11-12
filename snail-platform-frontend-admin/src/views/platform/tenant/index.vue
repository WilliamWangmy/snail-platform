<script setup lang="ts">
/** 组件名与路由 meta.viewName 对应,多页签 KeepAlive include 依此缓存。 */
defineOptions({ name: 'PlatformTenant' });
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import AppIcon from '@/components/AppIcon.vue';
import {
  changeTenantStatus,
  checkAdminUsername,
  createTenant,
  getTenantDetail,
  getTenantStorageUsage,
  getTenantUsageStats,
  pageTenants,
  updateTenant,
  type StorageUsageVO,
  type TenantFeatureFlags,
  type TenantQuery,
  type TenantUpdatePayload,
  type TenantVO,
  type UsageStatsVO,
} from '@/api/tenant';
import { usePageList } from '@/composables/usePageList';
import { date, dateTime } from '@/utils/format';
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
  /** 五系统开通开关:boolean 表单口径,提交时换算 1/0;默认全关(2026-11-11 拍板"默认都是不开启") */
  features: TenantFeatureFlags;
  contactUser: string;
  contactPhone: string;
  expireTime: string;
  /** 开通用户数=后端 account_limit(原型文案「开通用户数」),null 为不限 */
  accountLimit: number | null;
  /** 存储配额 GB 输入口径:提交 ×1024 换算 MB(契约 5.3),null 为不限 */
  storageGb: number | null;
  remark: string;
  adminUsername: string;
  /** 首任管理员初始口令(P2-3):平台管理员自输或点生成,仅在开通态提交 */
  adminPassword: string;
}

const emptyForm: TenantForm = {
  tenantCode: '',
  name: '',
  features: {
    adminEnabled: false,
    workflowEnabled: false,
    mobileEnabled: false,
    dashboardEnabled: false,
    portalEnabled: false,
  },
  contactUser: '',
  contactPhone: '',
  expireTime: '',
  accountLimit: null,
  storageGb: null,
  remark: '',
  adminUsername: '',
  adminPassword: '',
};

/**
 * 五系统开通项元数据:面板顺序与文案按原型 B 块;开关列与登录端 scene 的映射见契约 5.1
 * (admin→管理系统、workflow→流程系统、app/miniapp→移动端共用一开关、dashboard→大屏、portal→门户)。
 * icon 仅详情抽屉的功能行使用,弹窗芯片与列表 tag 不消费。
 */
const FEATURE_META: Array<{ key: keyof TenantFeatureFlags; label: string; hint: string; icon: string }> = [
  { key: 'adminEnabled', label: '管理系统', hint: '开通后可登录管理后台', icon: 'shield' },
  { key: 'workflowEnabled', label: '流程系统', hint: '开通后可登录流程平台', icon: 'flow' },
  { key: 'mobileEnabled', label: '移动端', hint: 'app 与小程序共用', icon: 'chat' },
  { key: 'dashboardEnabled', label: '数据大屏', hint: '开通后可登录大屏查看看板', icon: 'chart' },
  { key: 'portalEnabled', label: '企业门户', hint: '门户立项前为配置先行', icon: 'external' },
];

/** 列表功能开通 tag 的短文案:列宽有限,取单字/双字 */
const FEATURE_SHORT: Record<keyof TenantFeatureFlags, string> = {
  adminEnabled: '管理',
  workflowEnabled: '流程',
  mobileEnabled: '移动',
  dashboardEnabled: '大屏',
  portalEnabled: '门户',
};

/** 抽屉「系统使用」分端统计行:与 UsageStatsVO.scenes 的键一一对应;门户不参与(契约 BR-8 占位行) */
const SCENE_ROWS: Array<{ key: 'admin' | 'workflow' | 'mobile' | 'dashboard'; label: string }> = [
  { key: 'admin', label: '管理系统' },
  { key: 'workflow', label: '流程系统' },
  { key: 'mobile', label: '移动端' },
  { key: 'dashboard', label: '数据大屏' },
];

const formVisible = ref(false);
const formRef = ref<FormInstance>();
const editingId = ref<number | null>(null);
const form = reactive<TenantForm>({ ...emptyForm });
const saving = ref(false);

/** 功能开通芯片的切换出口:点亮=开通,再点收回;与开关面板同写 form.features,提交链路不变 */
function toggleFeature(key: keyof TenantFeatureFlags, on: boolean): void {
  form.features[key] = on;
}

/** 必填口径按需求收紧:开通态除备注外全部必填;修改态保持"留空即清空"的原语义不拦截 */
const rules = computed<FormRules<TenantForm>>(() => ({
  tenantCode: [
    { required: true, message: '请输入租户标识', trigger: 'blur' },
    { min: 2, max: 10, message: '租户标识长度 2-10', trigger: 'blur' },
    {
      pattern: /^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?$/,
      message: '租户标识只允许小写字母、数字、点与连字符,首尾须为字母或数字',
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
  accountLimit: editingId.value === null ? [{ required: true, message: '请输入账户数量', trigger: 'blur' }] : [],
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
  Object.assign(form, emptyForm, {
    // Object.assign 浅拷贝:features 是嵌套对象,必须整体重置,否则上一单的开关状态会残留进下一单
    features: { ...emptyForm.features },
  });
  formVisible.value = true;
}

/** 编辑回填:租户标识只读展示(后端修改载荷里没有它),管理员登录名清空——同一段模板只在开通态渲染。
 *  开关按行数据回填(列表投影已带五开关);存储配额 MB÷1024 折回 GB 输入口径。 */
function openEdit(row: TenantVO): void {
  editingId.value = row.tenantId;
  Object.assign(form, {
    tenantCode: row.tenantCode,
    name: row.name,
    features: {
      adminEnabled: row.adminEnabled === 1,
      workflowEnabled: row.workflowEnabled === 1,
      mobileEnabled: row.mobileEnabled === 1,
      dashboardEnabled: row.dashboardEnabled === 1,
      portalEnabled: row.portalEnabled === 1,
    },
    contactUser: row.contactUser ?? '',
    contactPhone: row.contactPhone ?? '',
    expireTime: row.expireTime ?? '',
    accountLimit: row.accountLimit,
    storageGb: row.storageLimitMb == null ? null : row.storageLimitMb / 1024,
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
    // 显式标注载荷类型:三元产生的 1/0 会被 TS 放宽成 number,靠注解收窄回 0|1 字面量口径
    const common: TenantUpdatePayload = {
      name: form.name.trim(),
      contactUser: text(form.contactUser),
      contactPhone: text(form.contactPhone),
      expireTime: form.expireTime || null,
      accountLimit: form.accountLimit,
      // 存储配额 GB×1024→MB(契约 5.3 冻结换算):留空即 null=不限;后端与配额校验链同一列
      storageLimitMb: form.storageGb == null ? null : form.storageGb * 1024,
      // 开关 boolean→0/1:不勾即 0,与后端"非 1 即 0"归一口径同向
      adminEnabled: form.features.adminEnabled ? 1 : 0,
      workflowEnabled: form.features.workflowEnabled ? 1 : 0,
      mobileEnabled: form.features.mobileEnabled ? 1 : 0,
      dashboardEnabled: form.features.dashboardEnabled ? 1 : 0,
      portalEnabled: form.features.portalEnabled ? 1 : 0,
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
      // 开关收回(1→0)会作废该租户全部存量令牌,由后端判定,前端不重复提示
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

/* ------------------------------- 详情抽屉 --------------------------------- */

/**
 * 详情抽屉四区:基础/功能开通/系统使用/存储使用(原型 C 块)。
 * 三接口并行:详情失败回落到列表行数据(同一投影,只少刷新不缺字段);
 * usage-stats 与 storage-usage 各自持有数据与失败态——单接口失败只降级所在分区,不拖垮整个抽屉。
 */
const drawerVisible = ref(false);
const drawerTenant = ref<TenantVO | null>(null);
const usage = ref<UsageStatsVO | null>(null);
const usageFailed = ref(false);
const storage = ref<StorageUsageVO | null>(null);
const storageFailed = ref(false);

function openDetail(row: TenantVO): void {
  drawerTenant.value = row;
  usage.value = null;
  usageFailed.value = false;
  storage.value = null;
  storageFailed.value = false;
  drawerVisible.value = true;
  getTenantDetail(row.tenantId)
    .then((detail) => (drawerTenant.value = detail))
    .catch(() => (drawerTenant.value = row));
  getTenantUsageStats(row.tenantId)
    .then((v) => (usage.value = v))
    .catch(() => (usageFailed.value = true));
  getTenantStorageUsage(row.tenantId)
    .then((v) => (storage.value = v))
    .catch(() => (storageFailed.value = true));
}

/** 存储使用率百分比:limitMb 为 null(不限)不渲染进度条与百分比,契约 §5.5 */
const storagePercent = computed<number | null>(() => {
  if (!storage.value || storage.value.limitMb == null || storage.value.limitMb <= 0) return null;
  return Math.min(
    100,
    Math.round((storage.value.usedBytes / (storage.value.limitMb * 1024 * 1024)) * 100),
  );
});

/** 存储超额判定:已用字节越过配额时,进度条与百分比转危险色提示尽快扩容 */
const storageOver = computed<boolean>(() => {
  if (!storage.value || storage.value.limitMb == null) return false;
  return storage.value.usedBytes > storage.value.limitMb * 1024 * 1024;
});

/** 到期已过判定(不限期不警示):到期时间在抽屉里标警示色,提示平台侧留意续期 */
const expirePassed = computed<boolean>(() => {
  const t = drawerTenant.value?.expireTime;
  if (!t) return false;
  return new Date(t).getTime() < Date.now();
});

/** 已开通系统数:展示在「功能开通」分区标题右侧,一眼看清开通规模 */
const featureOnCount = computed<number>(() => {
  const t = drawerTenant.value;
  if (!t) return 0;
  return FEATURE_META.filter((item) => t[item.key] === 1).length;
});

/** 剩余配额文案:limitMb 不限时返回空串(不渲染);负数(超额)钳到 0 */
const storageRemainGbText = computed<string>(() => {
  if (!storage.value || storage.value.limitMb == null) return '';
  const remainBytes = storage.value.limitMb * 1024 * 1024 - storage.value.usedBytes;
  return bytesToGbText(Math.max(0, remainBytes));
});

/** MB→GB 展示文案:整数 GB 不带小数,否则保留 1 位;null=不限 */
function mbToGbText(mb: number | null): string {
  if (mb == null) return '不限';
  const gb = mb / 1024;
  return Number.isInteger(gb) ? `${gb} GB` : `${gb.toFixed(1)} GB`;
}

/** 字节→GB 展示文案:≥10 GB 取整,否则保留 1 位 */
function bytesToGbText(bytes: number): string {
  const gb = bytes / 1024 ** 3;
  return gb >= 10 ? `${Math.round(gb)} GB` : `${gb.toFixed(1)} GB`;
}

/** 使用率百分比文案:0.925 → 93%(契约两位小数,展示取整百分比) */
function ratePercent(rate: number): string {
  return `${Math.round(rate * 100)}%`;
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
          min-width="110"
        />
        <ElTableColumn
          prop="name"
          label="租户名称"
          min-width="150"
          show-overflow-tooltip
        />
        <ElTableColumn
          label="联系人"
          width="96"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ row.contactUser ?? '—' }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="联系电话"
          min-width="120"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ row.contactPhone ?? '—' }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="到期时间"
          min-width="108"
        >
          <!-- 列表只看日期:时分秒进详情抽屉看(dateTime 全量口径),列宽让给功能开通 -->
          <template #default="{ row }">
            {{ date(row.expireTime) }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="账号上限"
          width="84"
          align="right"
        >
          <template #default="{ row }">
            {{ row.accountLimit ?? '不限' }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="存储配额"
          width="92"
          align="right"
        >
          <template #default="{ row }">
            {{ mbToGbText(row.storageLimitMb) }}
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="功能开通"
          width="160"
        >
          <template #default="{ row }">
            <div class="feature-tags">
              <span
                v-for="item in FEATURE_META"
                :key="item.key"
                class="feature-tag"
                :class="row[item.key] === 1 ? 'on' : 'off'"
              >
                {{ FEATURE_SHORT[item.key] }}
              </span>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn
          label="状态"
          width="72"
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
            <ElButton
              v-permission="'platform:tenant:list'"
              link
              type="primary"
              @click="openDetail(row as TenantVO)"
            >
              详情
            </ElButton>
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
              :type="row.status === 0 ? 'danger' : 'success'"
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
      width="min(760px, 94vw)"
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
        <!-- 分区标题轻量化:无边框面板盒,字段两列舒展;功能开通改一行点亮芯片,单击切换 -->
        <div class="sec-head">
          <i class="bar"></i>
          <b>基本信息</b>
          <span>标识开通后不可修改</span>
        </div>
        <div class="form-grid">
          <ElFormItem
            label="租户标识"
            prop="tenantCode"
          >
            <ElInput
              v-model="form.tenantCode"
              :disabled="editingId !== null"
              placeholder="2-10 位小写,字母、数字或 . -"
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

        <div
          v-if="editingId === null"
          class="sec-head"
        >
          <i class="bar"></i>
          <b>管理员账号</b>
          <span>初始口令请线下转交租户</span>
        </div>
        <div
          v-if="editingId === null"
          class="form-grid"
        >
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

        <div class="sec-head">
          <i class="bar"></i>
          <b>联系方式</b>
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

        <div class="sec-head">
          <i class="bar"></i>
          <b>使用限制</b>
          <span>{{ editingId === null ? '账号数与存储配额留空即不限,开通后可随时调整' : '留空即恢复不限' }}</span>
        </div>
        <div class="form-grid cols-3">
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
            label="开通用户数"
            prop="accountLimit"
          >
            <!-- 开通用户数=后端 account_limit(原型文案);min=1 是有意的:后端只认 null 为不限,0 会被当成"一个账号都不许建" -->
            <ElInputNumber
              v-model="form.accountLimit"
              :min="1"
              :precision="0"
              controls-position="right"
              :placeholder="editingId === null ? '请输入账户数量' : '留空不限'"
              class="full"
            />
          </ElFormItem>
          <ElFormItem
            label="存储配额(GB)"
            prop="storageGb"
          >
            <!-- GB 输入口径:提交 ×1024 换算 MB(契约 5.3 冻结换算,防止双端口径 1024 倍错位);留空不限,超限后上传被拒 -->
            <ElInputNumber
              v-model="form.storageGb"
              :min="1"
              :precision="0"
              controls-position="right"
              :placeholder="editingId === null ? '请输入配额大小' : '留空不限'"
              class="full"
            />
          </ElFormItem>
        </div>

        <div class="sec-head">
          <i class="bar"></i>
          <b>功能开通</b>
          <span>点亮即开通;未点亮的端用户无法登录,默认全部关闭</span>
        </div>
        <div class="chip-row">
          <!-- 点亮式芯片:选中=主题实底+对勾,单击切换;hint 悬停可见(细节口径与抽屉五开关同源 FEATURE_META) -->
          <ElButton
            v-for="item in FEATURE_META"
            :key="item.key"
            class="feature-chip"
            :type="form.features[item.key] ? 'primary' : 'info'"
            :plain="!form.features[item.key]"
            round
            :title="item.hint"
            @click="toggleFeature(item.key, !form.features[item.key])"
          >
            <AppIcon
              v-if="form.features[item.key]"
              name="check"
              :size="13"
            />
            {{ item.label }}
          </ElButton>
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

    <!-- 详情抽屉(原型 C 块):头部品牌化 + 四区块面板化,复用全局 panel 套件;单接口失败只降级所在分区 -->
    <ElDrawer
      v-model="drawerVisible"
      size="620px"
    >
      <template #header>
        <div
          v-if="drawerTenant"
          class="drawer-head"
        >
          <span class="t-avatar">{{ drawerTenant.name?.charAt(0) || '租' }}</span>
          <div class="t-meta">
            <div class="t-name-row">
              <h3>{{ drawerTenant.name }}</h3>
              <span
                class="status-pill"
                :class="drawerTenant.status === 0 ? 'success' : 'info'"
              >
                {{ drawerTenant.status === 0 ? '正常' : '停用' }}
              </span>
            </div>
            <div class="t-code">
              #{{ drawerTenant.tenantCode }} · ID {{ drawerTenant.tenantId }}
            </div>
          </div>
        </div>
      </template>

      <div
        v-if="drawerTenant"
        class="drawer-body"
      >
        <!-- 区一:基础信息(列表行数据兜底,不单独发请求);编码/时间/数字用等宽字形与系统日志页同语言 -->
        <section class="d-panel">
          <div class="panel-head">
            <span class="panel-ico">
              <AppIcon
                name="receipt"
                :size="13"
              />
            </span>
            <b class="panel-title">基础信息</b>
          </div>
          <dl class="info-grid">
            <div class="info-item">
              <dt>联系人</dt>
              <dd>{{ drawerTenant.contactUser ?? '—' }}</dd>
            </div>
            <div class="info-item">
              <dt>联系电话</dt>
              <dd class="mono">
                {{ drawerTenant.contactPhone ?? '—' }}
              </dd>
            </div>
            <div class="info-item">
              <dt>开通时间</dt>
              <dd class="mono">
                {{ dateTime(drawerTenant.createTime) }}
              </dd>
            </div>
            <div class="info-item">
              <dt>到期时间</dt>
              <dd
                class="mono"
                :class="{ warn: expirePassed }"
              >
                {{ drawerTenant.expireTime ? dateTime(drawerTenant.expireTime) : '不限' }}
              </dd>
            </div>
            <div class="info-item">
              <dt>开通用户数</dt>
              <dd class="mono">
                {{ drawerTenant.accountLimit ?? '不限' }}
              </dd>
            </div>
            <div class="info-item">
              <dt>存储配额</dt>
              <dd class="mono">
                {{ mbToGbText(drawerTenant.storageLimitMb) }}
              </dd>
            </div>
            <div class="info-item span-2">
              <dt>备注</dt>
              <dd>{{ drawerTenant.remark ?? '—' }}</dd>
            </div>
          </dl>
        </section>

        <!-- 区二:功能开通(行数据已带五开关);每行配语义图标,点亮态走成功色系 -->
        <section class="d-panel">
          <div class="panel-head">
            <span class="panel-ico">
              <AppIcon
                name="sliders"
                :size="13"
              />
            </span>
            <b class="panel-title">功能开通</b>
            <span class="panel-hint">已开通 {{ featureOnCount }}/5</span>
          </div>
          <div class="feature-list">
            <div
              v-for="item in FEATURE_META"
              :key="item.key"
              class="feature-line"
            >
              <span
                class="f-ico"
                :class="{ on: drawerTenant[item.key] === 1 }"
              >
                <AppIcon
                  :name="item.icon"
                  :size="14"
                />
              </span>
              <div class="f-txt">
                <b>{{ item.label }}</b>
                <span>{{ item.hint }}</span>
              </div>
              <span
                class="feature-tag big"
                :class="drawerTenant[item.key] === 1 ? 'on' : 'off'"
              >
                {{ drawerTenant[item.key] === 1 ? '已开通' : '未开通' }}
              </span>
            </div>
          </div>
        </section>

        <!-- 区三:系统使用(usage-stats):用户五卡+分端使用率表;门户行占位(契约 BR-8) -->
        <section class="d-panel">
          <div class="panel-head">
            <span class="panel-ico">
              <AppIcon
                name="chart"
                :size="13"
              />
            </span>
            <b class="panel-title">系统使用</b>
          </div>
          <div
            v-if="usageFailed"
            class="sec-fallback"
          >
            使用统计加载失败,请稍后重试
          </div>
          <template v-else-if="usage">
            <div class="user-grid">
              <div class="stat">
                <b>{{ usage.users.total }}</b><span>总用户</span>
              </div>
              <div class="stat">
                <b>{{ usage.users.enabled }}</b><span>启用</span>
              </div>
              <div class="stat">
                <b>{{ usage.users.disabled }}</b><span>禁用</span>
              </div>
              <div class="stat">
                <b>{{ usage.users.used }}</b><span>已使用</span>
              </div>
              <div class="stat hl">
                <b>{{ usage.users.online }}</b><span>在线</span>
              </div>
            </div>
            <table class="mini-table">
              <thead>
                <tr>
                  <th>端</th>
                  <th>登录用户</th>
                  <th>总用户</th>
                  <th>使用率</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="scene in SCENE_ROWS"
                  :key="scene.key"
                >
                  <td>{{ scene.label }}</td>
                  <td class="mono">
                    {{ usage.scenes[scene.key].loggedIn }}
                  </td>
                  <td class="mono">
                    {{ usage.users.total }}
                  </td>
                  <td>
                    <div class="rate-wrap">
                      <div class="rate-bar">
                        <i :style="{ width: ratePercent(usage.scenes[scene.key].rate) }"></i>
                      </div>
                      <span class="mono">{{ ratePercent(usage.scenes[scene.key].rate) }}</span>
                    </div>
                  </td>
                </tr>
                <tr>
                  <td>企业门户</td>
                  <td
                    colspan="3"
                    class="portal-note"
                  >
                    未开通,访问人数与 IP 统计随门户上线后展示
                  </td>
                </tr>
              </tbody>
            </table>
          </template>
          <div
            v-else
            class="sec-loading"
          >
            加载中…
          </div>
        </section>

        <!-- 区四:存储使用(storage-usage):limitMb 不限时不渲染进度与百分比(契约 §5.5);超额转危险色 -->
        <section class="d-panel">
          <div class="panel-head">
            <span class="panel-ico">
              <AppIcon
                name="box"
                :size="13"
              />
            </span>
            <b class="panel-title">存储使用</b>
            <span
              v-if="storage && storage.fileCount != null"
              class="panel-hint"
            >共 {{ storage.fileCount }} 个文件</span>
          </div>
          <div
            v-if="storageFailed"
            class="sec-fallback"
          >
            存储统计加载失败,请稍后重试
          </div>
          <template v-else-if="storage">
            <div class="usage-top">
              <span class="num">
                {{ bytesToGbText(storage.usedBytes) }}
                <small>/ {{ storage.limitMb == null ? '不限' : mbToGbText(storage.limitMb) }}</small>
              </span>
              <span
                v-if="storagePercent !== null"
                class="pct"
                :class="{ over: storageOver }"
              >{{ storagePercent }}%</span>
            </div>
            <div
              v-if="storagePercent !== null"
              class="rate-bar big"
              :class="{ over: storageOver }"
            >
              <i :style="{ width: storagePercent + '%' }"></i>
            </div>
            <div class="usage-sub">
              <template v-if="storage.limitMb != null">
                剩余 {{ storageRemainGbText }} ·
              </template>配额可在「修改」中调整;存储不区分分类明细
            </div>
          </template>
          <div
            v-else
            class="sec-loading"
          >
            加载中…
          </div>
        </section>
      </div>
    </ElDrawer>
  </div>
</template>

<style scoped>
.full {
  width: 100%;
}

/* 弹窗分区标题:竖条+标题+灰色说明,替代边框面板盒,视觉更轻、字段行距更舒展 */
.sec-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 20px 0 12px;
}

.sec-head .bar {
  width: 3px;
  height: 14px;
  flex: none;
  border-radius: 2px;
  background: var(--app-primary, #409eff);
}

.sec-head b {
  font-size: 14px;
  color: var(--app-ink, #303133);
}

.sec-head span {
  font-size: 12px;
  color: var(--app-muted, #909399);
}

/* 使用限制一行三列(到期时间/账号数/存储配额):760px 弹窗下三格并排,免纵向堆三行 */
.form-grid.cols-3 {
  grid-template-columns: repeat(3, 1fr);
}

@media (max-width: 640px) {
  .form-grid.cols-3 {
    grid-template-columns: 1fr;
  }
}

/* 功能开通芯片行:点亮=主题实底+对勾,灰=未开通;圆角胶囊比开关行省高且状态一目了然 */
.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  padding-bottom: 4px;
}

.chip-row .feature-chip {
  height: 32px;
  padding: 0 16px;
  font-size: 13px;
}

.chip-row .feature-chip .app-icon {
  margin-right: 5px;
}

/* 功能开通 tag(列表列与抽屉共用形态):on=已开通(绿系),off=未开通(灰系);列表侧一行三个、两行展示(3+2),间距舒展不挤 */
.feature-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.feature-tag {
  font-size: 12px;
  line-height: 20px;
  padding: 0 7px;
  border-radius: 10px;
  border: 1px solid transparent;
}

.feature-tag.on {
  color: var(--app-success, #67c23a);
  background: rgba(var(--app-success-rgb, 103, 194, 58), 0.12);
  border-color: rgba(var(--app-success-rgb, 103, 194, 58), 0.4);
}

.feature-tag.off {
  color: var(--app-muted, #909399);
  background: var(--app-soft-2, #f4f4f5);
  border-color: var(--app-line, #dcdfe6);
}

.feature-tag.big {
  padding: 2px 12px;
}

/* 抽屉头部:主题色品牌头像 + 名称/状态行 + 等宽标识行,与登录页 brand-mark 同一视觉母题 */
.drawer-head {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.t-avatar {
  display: inline-grid;
  place-items: center;
  width: 42px;
  height: 42px;
  flex: none;
  border-radius: 12px;
  color: var(--app-on-primary);
  background: linear-gradient(135deg, var(--app-primary), var(--app-primary-tint));
  box-shadow: 0 4px 12px rgba(var(--app-primary-rgb), 0.28);
  font-size: 17px;
  font-weight: 700;
}

.t-meta {
  min-width: 0;
}

.t-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.t-name-row h3 {
  margin: 0;
  font-size: 16px;
  letter-spacing: -0.02em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.t-code {
  margin-top: 3px;
  color: var(--app-muted);
  font-family: var(--app-font-mono);
  font-size: 11px;
  letter-spacing: 0.01em;
}

/* 抽屉区块面板:线框圆角软底,与弹窗表单套件(form-panel)同一语言;标题复用全局 panel-ico/panel-title */
.d-panel {
  padding: 14px 16px;
  margin-bottom: 14px;
  border: 1px solid var(--app-line);
  border-radius: 12px;
  background: var(--app-soft);
}

.d-panel .panel-head {
  margin-bottom: 12px;
}

/* 基础信息两列网格:标签小字弱化,值走主题墨色;编码/时间/数字用等宽字形 */
.info-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px 16px;
  margin: 0;
}

.info-item dt {
  margin-bottom: 3px;
  color: var(--app-muted);
  font-size: 10px;
}

.info-item dd {
  margin: 0;
  color: var(--app-ink);
  font-size: 12.5px;
  line-height: 1.4;
  word-break: break-all;
}

.info-item dd.mono {
  font-family: var(--app-font-mono);
  font-size: 12px;
  letter-spacing: 0.01em;
}

.info-item dd.warn {
  color: var(--app-warning);
}

/* 功能开通行:语义图标方块(点亮态走成功色系)+ 名称/说明 + 状态胶囊 */
.feature-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.feature-line {
  display: flex;
  align-items: center;
  gap: 10px;
}

.f-ico {
  display: inline-grid;
  place-items: center;
  width: 30px;
  height: 30px;
  flex: none;
  border: 1px solid var(--app-line);
  border-radius: 9px;
  color: var(--app-muted);
  background: var(--app-soft-2);
}

.f-ico.on {
  color: var(--app-success);
  background: rgba(var(--app-success-rgb), 0.1);
  border-color: rgba(var(--app-success-rgb), 0.35);
}

.f-txt {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.f-txt b {
  font-size: 12.5px;
  color: var(--app-ink);
}

.f-txt span {
  font-size: 10.5px;
  color: var(--app-muted);
}

/* 用户五卡:面板内的白卡层次,数字等宽;在线卡主题点亮 */
.user-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 8px;
  margin-bottom: 12px;
}

.stat {
  padding: 10px 6px;
  border: 1px solid var(--app-line);
  border-radius: 10px;
  background: var(--app-card);
  text-align: center;
}

.stat b {
  display: block;
  font-family: var(--app-font-mono);
  font-size: 17px;
  color: var(--app-ink);
}

.stat span {
  font-size: 10px;
  color: var(--app-muted);
}

.stat.hl {
  border-color: rgba(var(--app-primary-rgb), 0.32);
  background: rgba(var(--app-primary-rgb), 0.08);
}

.stat.hl b {
  color: var(--app-primary);
}

.mini-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}

.mini-table th,
.mini-table td {
  padding: 6px 8px;
  text-align: left;
  border-bottom: 1px solid var(--app-line);
}

.mini-table th {
  color: var(--app-muted);
  font-size: 10.5px;
  font-weight: 500;
}

.mini-table td.mono {
  font-family: var(--app-font-mono);
  font-size: 11.5px;
}

.rate-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
}

.rate-wrap .mono {
  min-width: 34px;
  text-align: right;
  font-family: var(--app-font-mono);
  font-size: 11px;
}

.rate-bar {
  flex: 1;
  height: 6px;
  background: var(--app-soft-2);
  border-radius: 3px;
  overflow: hidden;
}

.rate-bar i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, var(--app-primary), var(--app-primary-tint));
  border-radius: 3px;
}

.rate-bar.big {
  height: 10px;
  border-radius: 5px;
}

.rate-bar.over,
.rate-bar.over i {
  background: var(--app-soft-2);
}

.rate-bar.over i {
  background: linear-gradient(90deg, var(--app-danger), color-mix(in srgb, var(--app-danger) 70%, white));
}

.usage-top {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 8px;
}

.usage-top .num {
  font-family: var(--app-font-mono);
  font-size: 20px;
  font-weight: 600;
  color: var(--app-ink);
}

.usage-top .num small {
  font-size: 12px;
  font-weight: 400;
  color: var(--app-muted);
  margin-left: 4px;
}

.usage-top .pct {
  font-family: var(--app-font-mono);
  font-size: 13px;
  font-weight: 600;
  color: var(--app-primary);
}

.usage-top .pct.over {
  color: var(--app-danger);
}

.usage-sub {
  font-size: 12px;
  color: var(--app-muted);
  margin-top: 10px;
}

.sec-fallback {
  font-size: 12px;
  color: var(--app-muted);
  background: var(--app-soft-2);
  padding: 14px;
  border-radius: 8px;
}

.sec-loading {
  font-size: 12px;
  color: var(--app-muted);
  padding: 6px 0;
}

.portal-note {
  color: var(--app-muted);
}

/* 弹窗面板/横幅/口令动作等公共样式在 styles/index.css 的「弹窗表单套件」一节,全局单点维护 */
</style>
