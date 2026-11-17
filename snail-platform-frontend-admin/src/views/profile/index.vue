<script setup lang="ts">
/** 组件名与路由 meta.viewName 对应,多页签 KeepAlive include 依此缓存。 */
defineOptions({ name: 'ProfileView' });
import { onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  fetchMyIdentities,
  fetchProfile,
  forceLogoutSelf,
  unbindMyIdentity,
  updateProfile,
  type Profile,
  type UserIdentity,
} from '@/api/profile';
import {
  pageMyLoginLogs,
  pageMyOperLogs,
  type LoginLogVO,
  type OperLogListVO,
} from '@/api/log';
import { uploadMyAvatar } from '@/api/file';
import { useAuthStore } from '@/stores/auth';
import { useAvatarStore } from '@/stores/avatar';
import { usePageList } from '@/composables/usePageList';
import AppIcon from '@/components/AppIcon.vue';

const router = useRouter();
const auth = useAuthStore();
const avatarStore = useAvatarStore();

const profile = ref<Profile | null>(null);

/** 第三方身份展示名与语义图标,与登录页 OAUTH_LABELS/OAUTH_ICONS 同口径 */
const OAUTH_SOURCE_META: Record<string, { label: string; icon: string }> = {
  dingtalk: { label: '钉钉', icon: 'zap' },
  feishu: { label: '飞书', icon: 'send' },
  wecom: { label: '企业微信', icon: 'chat' },
};

const identities = ref<UserIdentity[]>([]);
const identitiesLoading = ref(false);
const unbindingId = ref<number | null>(null);

async function loadIdentities(): Promise<void> {
  identitiesLoading.value = true;
  try {
    identities.value = await fetchMyIdentities();
  } finally {
    identitiesLoading.value = false;
  }
}

function formatTime(value: string | null): string {
  return value ? value.replace('T', ' ').slice(0, 19) : '—';
}

/* ----------------------- 本人日志(登录/操作)----------------------- */

/** 登录端中文标签:与登录日志管理页 SCENE_OPTIONS 同口径,未登记的新端原样回显。 */
const SCENE_LABELS: Record<string, string> = {
  admin: '管理系统',
  workflow: '流程系统',
  app: '移动端',
  miniapp: '移动端小程序',
  dashboard: '数据大屏',
  portal: '企业门户',
};

function sceneOf(scene: string): string {
  return SCENE_LABELS[scene] ?? scene;
}

/** 业务类型标签:与后端 BusinessType 枚举同一份口径。 */
const BUSINESS_TYPES: Record<number, string> = {
  1: '新增',
  2: '修改',
  3: '删除',
  4: '导出',
  5: '导入',
  9: '其他',
};

function businessOf(value: number): string {
  return BUSINESS_TYPES[value] ?? String(value);
}

/** 本人登录日志:后端按登录上下文钉死只看自己,这里只管分页。 */
const {
  rows: loginLogRows,
  total: loginLogTotal,
  loading: loginLogLoading,
  pageNum: loginLogPage,
  refresh: refreshLoginLogs,
} = usePageList<LoginLogVO>((page) => pageMyLoginLogs(page), 8);

/** 本人操作日志:同上,按登录名+本租户钉死,只管分页。 */
const {
  rows: operLogRows,
  total: operLogTotal,
  loading: operLogLoading,
  pageNum: operLogPage,
  refresh: refreshOperLogs,
} = usePageList<OperLogListVO>((page) => pageMyOperLogs(page), 8);

/** 解绑后该第三方身份不能再登录本账号;账号密码登录不受影响,所以不需要二次保底 */
async function handleUnbind(identity: UserIdentity): Promise<void> {
  const label = OAUTH_SOURCE_META[identity.source]?.label ?? identity.source;
  await ElMessageBox.confirm(
    `解绑后「${label}」将不能再登录本账号,确认解绑?`,
    '解绑第三方账号',
    { type: 'warning' },
  );
  unbindingId.value = identity.id;
  try {
    await unbindMyIdentity(identity.id);
    ElMessage.success('已解绑');
    await loadIdentities();
  } finally {
    unbindingId.value = null;
  }
}

const profileFormRef = ref();
const profileForm = reactive({ nickname: '', email: '', phone: '' });
const profileRules = {
  nickname: [
    { required: true, message: '请输入显示名', trigger: 'blur' },
    { max: 30, message: '显示名至多 30 字', trigger: 'blur' },
  ],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
  ],
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    // 与后端 UpdateProfileRequest 的 1[3-9] 口径一致,避免本地放过、服务端再拒
    { pattern: /^1[3-9]\d{9}$/, message: '请输入 11 位手机号', trigger: 'blur' },
  ],
};

onMounted(async () => {
  profile.value = await fetchProfile();
  profileForm.nickname = profile.value.nickname;
  profileForm.email = profile.value.email ?? '';
  profileForm.phone = profile.value.phone ?? '';
  void avatarStore.load(profile.value.userId);
  void refreshLoginLogs();
  void refreshOperLogs();
  await loadIdentities();
});

const savingProfile = ref(false);

/** 顶栏显示名来自登录快照而非档案接口,所以存完资料必须重取会话,否则名字还是旧的 */
async function submitProfile(): Promise<void> {
  await profileFormRef.value?.validate();
  savingProfile.value = true;
  try {
    await updateProfile({
      nickname: profileForm.nickname.trim(),
      email: profileForm.email.trim(),
      phone: profileForm.phone.trim(),
    });
    ElMessage.success('资料已更新');
    await auth.loadSession();
  } finally {
    savingProfile.value = false;
  }
}

const AVATAR_EXTS = new Set(['jpg', 'jpeg', 'png', 'gif', 'webp']);
const AVATAR_MAX_BYTES = 2 * 1024 * 1024;

const avatarInput = ref<HTMLInputElement | null>(null);
const uploadingAvatar = ref(false);

function pickAvatar(): void {
  if (!uploadingAvatar.value) avatarInput.value?.click();
}

async function onAvatarChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;
  const ext = file.name.split('.').pop()?.toLowerCase() ?? '';
  if (!AVATAR_EXTS.has(ext)) {
    ElMessage.warning('头像仅支持 JPG/PNG/GIF/WEBP 图片');
    return;
  }
  if (file.size > AVATAR_MAX_BYTES) {
    ElMessage.warning('头像大小不能超过 2MB');
    return;
  }
  uploadingAvatar.value = true;
  try {
    await uploadMyAvatar(file);
    const userId = profile.value?.userId;
    if (userId) await avatarStore.refresh(userId);
    ElMessage.success('头像已更新');
  } finally {
    uploadingAvatar.value = false;
  }
}

const forcingLogout = ref(false);

/** 服务端吊销自己全部会话(含本设备),本地清理只是把界面带回登录页;取消确认框则什么都不发生 */
async function handleForceLogout(): Promise<void> {
  await ElMessageBox.confirm('将强制下线自己的全部会话(含本设备),确认执行?', '强制下线', { type: 'warning' });
  forcingLogout.value = true;
  try {
    await forceLogoutSelf();
    ElMessage.success('已强制下线,请重新登录');
    signOutLocal();
  } finally {
    forcingLogout.value = false;
  }
}

/** 只清本地状态:这两条路径的令牌已被服务端版本前推作废,不必再跑一次登出接口 */
function signOutLocal(): void {
  auth.signOut();
  void router.push('/login');
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="eyebrow">
          PROFILE
        </div>
        <h1>个人中心</h1>
        <p>查看与维护个人资料、登录安全与第三方账号。</p>
      </div>
    </div>

    <!-- 身份卡:可点击更换的头像 + 名称/角色胶囊 + 等宽标识行 + 归属事实区,与租户详情抽屉同一视觉母题 -->
    <ElCard
      shadow="never"
      class="hero-card"
    >
      <div class="hero">
        <button
          class="p-avatar"
          type="button"
          title="点击更换头像"
          :aria-label="uploadingAvatar ? '头像上传中' : '更换头像'"
          :disabled="uploadingAvatar"
          @click="pickAvatar"
        >
          <img
            v-if="avatarStore.url"
            :src="avatarStore.url"
            class="p-avatar-img"
            alt=""
          />
          <template v-else>
            {{ profile?.nickname?.charAt(0) || '?' }}
          </template>
          <span class="p-avatar-mask">{{ uploadingAvatar ? '上传中…' : '更换头像' }}</span>
        </button>
        <input
          ref="avatarInput"
          type="file"
          accept="image/jpeg,image/png,image/gif,image/webp"
          hidden
          @change="onAvatarChange"
        />
        <div class="p-meta">
          <div class="p-name-row">
            <h2>{{ profile?.nickname ?? '—' }}</h2>
            <template v-if="profile?.roles?.length">
              <span
                v-for="role in profile.roles"
                :key="role"
                class="role-pill"
              >
                {{ role }}
              </span>
            </template>
            <span
              v-else
              class="role-pill empty"
            >
              暂未授权角色
            </span>
          </div>
          <div class="p-username">
            @{{ profile?.username ?? '—' }} · ID {{ profile?.userId ?? '—' }}
          </div>
          <div class="p-facts">
            <div class="fact">
              <dt>所属组织</dt>
              <dd>{{ profile?.orgName ?? '—' }}</dd>
            </div>
            <div class="fact">
              <dt>所属租户</dt>
              <dd>{{ profile?.tenantName ?? '—' }}</dd>
            </div>
            <div class="fact">
              <dt>创建时间</dt>
              <dd class="mono">
                {{ formatTime(profile?.createTime ?? null) }}
              </dd>
            </div>
          </div>
        </div>
      </div>
    </ElCard>

    <ElRow
      :gutter="16"
      class="body-row"
    >
      <ElCol
        :xs="24"
        :lg="12"
      >
        <ElCard
          shadow="never"
          class="section-card"
        >
          <template #header>
            <div class="table-card-head">
              <h3>
                账号资料
                <span>ACCOUNT</span>
              </h3>
            </div>
          </template>
          <ElForm
            ref="profileFormRef"
            :model="profileForm"
            :rules="profileRules"
            label-position="top"
          >
            <div class="form-grid">
              <ElFormItem
                label="显示名"
                prop="nickname"
              >
                <ElInput
                  v-model="profileForm.nickname"
                  maxlength="30"
                />
              </ElFormItem>
              <ElFormItem
                label="手机号"
                prop="phone"
              >
                <ElInput
                  v-model="profileForm.phone"
                  class="mono-input"
                  maxlength="11"
                  show-word-limit
                  placeholder="请输入 11 位手机号"
                />
              </ElFormItem>
              <ElFormItem
                label="邮箱"
                prop="email"
                class="span-2"
              >
                <ElInput
                  v-model="profileForm.email"
                  placeholder="请输入邮箱"
                />
              </ElFormItem>
            </div>
            <div class="form-foot">
              <span class="foot-hint">登录名、组织与租户由管理员管理,本人不可修改。</span>
              <div class="foot-actions">
                <ElButton
                  type="danger"
                  plain
                  size="small"
                  :loading="forcingLogout"
                  @click="handleForceLogout"
                >
                  强制下线全部会话
                </ElButton>
                <ElButton
                  type="primary"
                  :loading="savingProfile"
                  @click="submitProfile"
                >
                  保存资料
                </ElButton>
              </div>
            </div>
          </ElForm>
        </ElCard>
      </ElCol>
      <ElCol
        :xs="24"
        :lg="12"
      >
        <!-- 第三方账号:行式布局沿用抽屉 feature-line 语言(语义图标方块 + 名称/说明 + 右侧动作),openId 等宽字形便于与厂商后台核对 -->
        <ElCard
          shadow="never"
          class="section-card"
        >
          <template #header>
            <div class="table-card-head">
              <h3>
                第三方账号
                <span>LINKED ACCOUNTS</span>
              </h3>
              <span
                v-if="identities.length"
                class="head-count"
              >
                已绑定 {{ identities.length }}
              </span>
            </div>
          </template>
          <div
            v-if="!identitiesLoading && identities.length === 0"
            class="id-empty"
          >
            <span class="empty-ico">
              <AppIcon
                name="external"
                :size="18"
              />
            </span>
            <b>尚未绑定第三方账号</b>
            <span>绑定在首次扫码登录成功后自动建立,解绑不影响账号密码登录。</span>
          </div>
          <div
            v-else
            v-loading="identitiesLoading"
            class="id-list"
          >
            <div
              v-for="identity in identities"
              :key="identity.id"
              class="id-line"
            >
              <span class="id-ico">
                <AppIcon
                  :name="OAUTH_SOURCE_META[identity.source]?.icon ?? 'external'"
                  :size="14"
                />
              </span>
              <div class="id-txt">
                <div class="id-name">
                  <b>{{ OAUTH_SOURCE_META[identity.source]?.label ?? identity.source }}</b>
                  <span class="id-nick">{{ identity.nickname ?? '—' }}</span>
                </div>
                <div class="id-meta">
                  <span>身份标识 <i class="mono">{{ identity.openId }}</i></span>
                  <span>绑定于 <i class="mono">{{ formatTime(identity.createTime) }}</i></span>
                  <span>最近登录 <i class="mono">{{ formatTime(identity.lastLoginTime) }}</i></span>
                </div>
              </div>
              <ElButton
                type="danger"
                plain
                size="small"
                :loading="unbindingId === identity.id"
                @click="handleUnbind(identity)"
              >
                解绑
              </ElButton>
            </div>
          </div>
        </ElCard>
      </ElCol>
    </ElRow>

    <!-- 本人日志:登录/操作各一卡,只展示当前登录人的留痕;后端按上下文钉死,翻页在此分页取数 -->
    <ElRow
      :gutter="16"
      class="log-row"
    >
      <ElCol
        :xs="24"
        :lg="12"
      >
        <ElCard
          shadow="never"
          class="section-card"
        >
          <template #header>
            <div class="table-card-head">
              <h3>
                登录日志
                <span>LOGIN LOGS</span>
              </h3>
              <span
                v-if="loginLogTotal"
                class="head-count"
              >
                共 {{ loginLogTotal }} 条
              </span>
            </div>
          </template>
          <div
            v-if="!loginLogLoading && loginLogRows.length === 0"
            class="log-empty"
          >
            暂无登录记录
          </div>
          <ElTable
            v-else
            v-loading="loginLogLoading"
            :data="loginLogRows"
            size="small"
            class="log-table"
          >
            <ElTableColumn
              label="登录端"
              width="100"
            >
              <template #default="{ row }">
                <span class="log-strong">{{ sceneOf(row.scene) }}</span>
              </template>
            </ElTableColumn>
            <ElTableColumn
              label="结果"
              width="72"
            >
              <template #default="{ row }">
                <ElTag
                  size="small"
                  effect="plain"
                  :type="row.status === 1 ? 'success' : 'danger'"
                >
                  {{ row.status === 1 ? '成功' : '失败' }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn
              label="IP / 环境"
              min-width="140"
            >
              <template #default="{ row }">
                <span class="mono log-strong">{{ row.ip ?? '—' }}</span>
                <span class="log-sub">{{ [row.browser, row.os].filter(Boolean).join(' · ') || '—' }}</span>
              </template>
            </ElTableColumn>
            <ElTableColumn
              label="说明"
              min-width="120"
              show-overflow-tooltip
            >
              <template #default="{ row }">
                {{ row.msg || '—' }}
              </template>
            </ElTableColumn>
            <ElTableColumn
              label="时间"
              width="150"
            >
              <template #default="{ row }">
                <span class="mono">{{ formatTime(row.loginTime) }}</span>
              </template>
            </ElTableColumn>
          </ElTable>
          <div
            v-if="loginLogTotal"
            class="log-foot"
          >
            <ElPagination
              v-model:current-page="loginLogPage"
              small
              background
              layout="prev, pager, next"
              :total="loginLogTotal"
              :page-size="8"
              @current-change="refreshLoginLogs"
            />
          </div>
        </ElCard>
      </ElCol>
      <ElCol
        :xs="24"
        :lg="12"
      >
        <ElCard
          shadow="never"
          class="section-card"
        >
          <template #header>
            <div class="table-card-head">
              <h3>
                操作日志
                <span>OPERATION LOGS</span>
              </h3>
              <span
                v-if="operLogTotal"
                class="head-count"
              >
                共 {{ operLogTotal }} 条
              </span>
            </div>
          </template>
          <div
            v-if="!operLogLoading && operLogRows.length === 0"
            class="log-empty"
          >
            暂无操作记录
          </div>
          <ElTable
            v-else
            v-loading="operLogLoading"
            :data="operLogRows"
            size="small"
            class="log-table"
          >
            <ElTableColumn
              label="模块"
              min-width="110"
              show-overflow-tooltip
            >
              <template #default="{ row }">
                <span class="log-strong">{{ row.module }}</span>
              </template>
            </ElTableColumn>
            <ElTableColumn
              label="类型"
              width="72"
            >
              <template #default="{ row }">
                {{ businessOf(row.businessType) }}
              </template>
            </ElTableColumn>
            <ElTableColumn
              label="结果"
              width="72"
            >
              <template #default="{ row }">
                <ElTag
                  size="small"
                  effect="plain"
                  :type="row.status === 1 ? 'success' : 'danger'"
                >
                  {{ row.status === 1 ? '成功' : '失败' }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn
              label="耗时"
              width="80"
            >
              <template #default="{ row }">
                <span class="mono">{{ row.costTime == null ? '—' : `${row.costTime}ms` }}</span>
              </template>
            </ElTableColumn>
            <ElTableColumn
              label="时间"
              width="150"
            >
              <template #default="{ row }">
                <span class="mono">{{ formatTime(row.operTime) }}</span>
              </template>
            </ElTableColumn>
          </ElTable>
          <div
            v-if="operLogTotal"
            class="log-foot"
          >
            <ElPagination
              v-model:current-page="operLogPage"
              small
              background
              layout="prev, pager, next"
              :total="operLogTotal"
              :page-size="8"
              @current-change="refreshOperLogs"
            />
          </div>
        </ElCard>
      </ElCol>
    </ElRow>
  </div>
</template>

<style scoped>
/* 固定视口高度布局的收缩防线:.page 纵向铺满主区,页面块默认 flex-shrink:1 可被压缩,
   而 ElCard 自带 overflow:hidden 令 flex 的 min-height 自动下限归零——内容一超屏,
   身份卡会被压到近乎消失。这里钉死各块自然高度,放行溢出交给 .page-main 滚动兜底 */
.page > .page-head,
.page > .hero-card,
.page > .body-row,
.page > .log-row {
  flex: none;
}

/* 身份卡:渐变头像同租户抽屉 t-avatar 母题,角色胶囊走主题主色系 */
.hero-card {
  margin-bottom: 16px;
}

.hero {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  min-width: 0;
}

.p-avatar {
  position: relative;
  display: inline-grid;
  place-items: center;
  width: 56px;
  height: 56px;
  flex: none;
  padding: 0;
  border: 0;
  border-radius: 14px;
  overflow: hidden;
  color: var(--app-on-primary);
  background: linear-gradient(135deg, var(--app-primary), var(--app-primary-tint));
  box-shadow: 0 6px 16px rgba(var(--app-primary-rgb), 0.28);
  cursor: pointer;
  font-family: inherit;
  font-size: 22px;
  font-weight: 700;
}

.p-avatar:disabled {
  cursor: default;
}

.p-avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.p-avatar-mask {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  padding: 3px 0;
  background: rgba(0, 0, 0, 0.52);
  color: #fff;
  font-size: 10px;
  font-weight: 500;
  opacity: 0;
  transition: opacity 0.16s;
}

.p-avatar:hover .p-avatar-mask,
.p-avatar:focus-visible .p-avatar-mask {
  opacity: 1;
}

.p-meta {
  flex: 1;
  min-width: 0;
}

.p-name-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 8px;
}

.p-name-row h2 {
  margin: 0;
  color: var(--app-ink);
  font-size: 19px;
  letter-spacing: -0.02em;
}

.role-pill {
  padding: 3px 9px;
  border-radius: 99px;
  color: var(--app-primary);
  background: rgba(var(--app-primary-rgb), 0.1);
  font-size: 10px;
  font-weight: 600;
  white-space: nowrap;
}

.role-pill.empty {
  color: var(--app-muted);
  background: var(--app-soft-2);
}

.p-username {
  margin-top: 4px;
  color: var(--app-muted);
  font-family: var(--app-font-mono);
  font-size: 11px;
  letter-spacing: 0.01em;
}

/* 归属事实区:小网格自动换行;标签小字弱化,值走主题墨色 */
.p-facts {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 28px;
  margin-top: 14px;
  padding-top: 13px;
  border-top: 1px solid var(--app-line);
}

.fact dt {
  margin-bottom: 3px;
  color: var(--app-muted);
  font-size: 10px;
}

.fact dd {
  margin: 0;
  color: var(--app-ink);
  font-size: 12.5px;
}

.fact dd.mono {
  font-family: var(--app-font-mono);
  font-size: 12px;
}

/* 双栏正文 + 整行第三方卡:统一 16px 纵向节奏 */
.body-row + .section-card {
  margin-top: 0;
}

.section-card {
  margin-bottom: 16px;
}

/* 卡片高度对齐:列内改弹性布局,左右两张卡拉伸到同一行高,边框线齐平 */
.body-row :deep(.el-col),
.log-row :deep(.el-col) {
  display: flex;
}

.body-row .section-card,
.log-row .section-card {
  flex: 1;
}

/* 本人日志卡:紧凑小表 + 右下翻页;空态一行弱化文案,不另立大空态 */
.log-table {
  --el-table-border-color: var(--app-line);
}

.log-table :deep(.el-table__cell) {
  padding: 7px 0;
}

.log-strong {
  color: var(--app-ink);
  font-size: 12.5px;
}

.log-sub {
  display: block;
  margin-top: 2px;
  overflow: hidden;
  color: var(--app-muted);
  font-size: 10.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.log-foot {
  display: flex;
  justify-content: flex-end;
  padding-top: 10px;
}

.log-empty {
  padding: 26px 0;
  color: var(--app-muted);
  font-size: 11px;
  text-align: center;
}

.head-count {
  color: var(--app-muted);
  font-family: var(--app-font-mono);
  font-size: 10px;
}

/* 资料表单:两列舒展 + 底部动作行(提示左、按钮组右),与弹窗表单套件同语言 */
.form-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-top: 2px;
}

.foot-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}

.foot-hint {
  color: var(--app-muted);
  font-size: 10.5px;
  line-height: 1.5;
}

/* 第三方账号行:语义图标方块 + 名称/昵称 + meta 等宽行 + 右侧解绑 */
.id-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.id-line {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 13px;
  border: 1px solid var(--app-line);
  border-radius: 10px;
  background: var(--app-soft);
  transition: border-color 0.15s ease;
}

.id-line:hover {
  border-color: color-mix(in srgb, var(--app-primary) 38%, var(--app-line));
}

.id-ico {
  display: inline-grid;
  place-items: center;
  width: 32px;
  height: 32px;
  flex: none;
  border: 1px solid rgba(var(--app-primary-rgb), 0.35);
  border-radius: 9px;
  color: var(--app-primary);
  background: rgba(var(--app-primary-rgb), 0.08);
}

.id-txt {
  flex: 1;
  min-width: 0;
}

.id-name {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.id-name b {
  color: var(--app-ink);
  font-size: 12.5px;
}

.id-nick {
  overflow: hidden;
  color: var(--app-muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.id-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 16px;
  margin-top: 4px;
  color: var(--app-muted);
  font-size: 10.5px;
}

.id-meta .mono {
  color: var(--app-ink);
  font-family: var(--app-font-mono);
  font-style: normal;
  font-size: 10.5px;
}

/* 空态:居中图标 + 双行文案,替代裸 alert 更轻 */
.id-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 28px 0 22px;
  text-align: center;
}

.empty-ico {
  display: inline-grid;
  place-items: center;
  width: 42px;
  height: 42px;
  margin-bottom: 4px;
  border: 1px solid var(--app-line);
  border-radius: 12px;
  color: var(--app-muted);
  background: var(--app-soft-2);
}

.id-empty b {
  color: var(--app-ink);
  font-size: 12.5px;
}

.id-empty span {
  max-width: 380px;
  color: var(--app-muted);
  font-size: 10.5px;
  line-height: 1.6;
}

@media (max-width: 760px) {
  .hero {
    flex-direction: column;
    align-items: flex-start;
  }

  .form-foot {
    flex-direction: column;
    align-items: stretch;
  }

  .foot-actions {
    justify-content: flex-end;
  }

  .id-line {
    flex-wrap: wrap;
  }
}
</style>
