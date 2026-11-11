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
import { useAuthStore } from '@/stores/auth';
import AppIcon from '@/components/AppIcon.vue';

const router = useRouter();
const auth = useAuthStore();

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
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  phone: [
    // 空值跳过规则(async-validator 惯例),选填;位数上限由输入框 maxlength 双保险
    { pattern: /^1\d{10}$/, message: '请输入 11 位手机号', trigger: 'blur' },
  ],
};

onMounted(async () => {
  profile.value = await fetchProfile();
  profileForm.nickname = profile.value.nickname;
  profileForm.email = profile.value.email ?? '';
  profileForm.phone = profile.value.phone ?? '';
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
      email: profileForm.email.trim() || null,
      phone: profileForm.phone.trim() || null,
    });
    ElMessage.success('资料已更新');
    await auth.loadSession();
  } finally {
    savingProfile.value = false;
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

    <!-- 身份卡:主题渐变头像 + 名称/角色胶囊 + 等宽标识行 + 归属事实区,与租户详情抽屉同一视觉母题 -->
    <ElCard
      shadow="never"
      class="hero-card"
    >
      <div class="hero">
        <span class="p-avatar">{{ profile?.nickname?.charAt(0) || '?' }}</span>
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
                  placeholder="选填"
                />
              </ElFormItem>
              <ElFormItem
                label="邮箱"
                prop="email"
                class="span-2"
              >
                <ElInput
                  v-model="profileForm.email"
                  placeholder="选填"
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
  </div>
</template>

<style scoped>
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
  display: inline-grid;
  place-items: center;
  width: 56px;
  height: 56px;
  flex: none;
  border-radius: 14px;
  color: var(--app-on-primary);
  background: linear-gradient(135deg, var(--app-primary), var(--app-primary-tint));
  box-shadow: 0 6px 16px rgba(var(--app-primary-rgb), 0.28);
  font-size: 22px;
  font-weight: 700;
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
