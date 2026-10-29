<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  changePassword,
  fetchProfile,
  forceLogoutSelf,
  updateProfile,
  type Profile,
} from '@/api/profile';
import { useAuthStore } from '@/stores/auth';
import { copyText, generatePassword, validatePassword } from '@/utils/password';

const router = useRouter();
const auth = useAuthStore();

const profile = ref<Profile | null>(null);

const profileFormRef = ref();
const profileForm = reactive({ nickname: '', email: '', phone: '' });
const profileRules = {
  nickname: [
    { required: true, message: '请输入显示名', trigger: 'blur' },
    { max: 30, message: '显示名至多 30 字', trigger: 'blur' },
  ],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
};

const pwdFormRef = ref();
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' });
const pwdRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    {
      // 密码强度口径与后端 PasswordPolicy 同值,由 validatePassword 单点承载
      validator: (_rule: unknown, value: string, callback: (error?: Error) => void): void => {
        const message = validatePassword(value);
        callback(message ? new Error(message) : undefined);
      },
      trigger: 'blur',
    },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule: unknown, value: string, callback: (error?: Error) => void): void => {
        if (value !== pwdForm.newPassword) callback(new Error('两次输入的密码不一致'));
        else callback();
      },
      trigger: 'blur',
    },
  ],
};

onMounted(async () => {
  profile.value = await fetchProfile();
  profileForm.nickname = profile.value.nickname;
  profileForm.email = profile.value.email ?? '';
  profileForm.phone = profile.value.phone ?? '';
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

const changingPwd = ref(false);

function generateNewPassword(): void {
  pwdForm.newPassword = generatePassword();
}

async function copyNewPassword(): Promise<void> {
  if (!pwdForm.newPassword) return;
  if (await copyText(pwdForm.newPassword)) {
    ElMessage.success('已复制到剪贴板');
  } else {
    ElMessage.error('复制失败,请手动复制');
  }
}

/** 改密成功即令牌版本自增,当前令牌下一次请求就废,所以主动登出比等 401 再跳更干净 */
async function submitPassword(): Promise<void> {
  await pwdFormRef.value?.validate();
  await ElMessageBox.confirm('修改成功后所有设备将被强制重新登录,确认修改?', '修改口令', { type: 'warning' });
  changingPwd.value = true;
  try {
    await changePassword({ oldPassword: pwdForm.oldPassword, newPassword: pwdForm.newPassword });
    ElMessage.success('口令已修改,请重新登录');
    signOutLocal();
  } finally {
    changingPwd.value = false;
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
      </div>
    </div>

    <ElRow :gutter="16">
      <ElCol :span="12">
        <ElCard
          shadow="never"
          class="table-card"
        >
          <template #header>
            <div class="table-card-head">
              <h3>个人资料</h3>
            </div>
          </template>
          <ElForm
            ref="profileFormRef"
            :model="profileForm"
            :rules="profileRules"
            label-width="80px"
          >
            <ElFormItem label="登录名">
              <ElInput
                :model-value="profile?.username"
                disabled
              />
            </ElFormItem>
            <ElFormItem label="所属组织">
              <ElInput
                :model-value="profile?.orgName ?? '—'"
                disabled
              />
            </ElFormItem>
            <ElFormItem label="所属租户">
              <ElInput
                :model-value="profile?.tenantName ?? '—'"
                disabled
              />
            </ElFormItem>
            <ElFormItem
              label="显示名"
              prop="nickname"
            >
              <ElInput v-model="profileForm.nickname" />
            </ElFormItem>
            <ElFormItem
              label="邮箱"
              prop="email"
            >
              <ElInput v-model="profileForm.email" />
            </ElFormItem>
            <ElFormItem
              label="手机号"
              prop="phone"
            >
              <ElInput v-model="profileForm.phone" />
            </ElFormItem>
            <ElFormItem>
              <ElButton
                type="primary"
                :loading="savingProfile"
                @click="submitProfile"
              >
                保存资料
              </ElButton>
            </ElFormItem>
          </ElForm>
        </ElCard>
      </ElCol>
      <ElCol :span="12">
        <ElCard
          shadow="never"
          class="table-card"
        >
          <template #header>
            <div class="table-card-head">
              <h3>修改口令</h3>
            </div>
          </template>
          <ElForm
            ref="pwdFormRef"
            :model="pwdForm"
            :rules="pwdRules"
            label-width="90px"
          >
            <ElFormItem
              label="原密码"
              prop="oldPassword"
            >
              <ElInput
                v-model="pwdForm.oldPassword"
                type="password"
                show-password
              />
            </ElFormItem>
            <ElFormItem
              label="新密码"
              prop="newPassword"
            >
              <ElInput
                v-model="pwdForm.newPassword"
                type="password"
                show-password
                placeholder="8-20 位,四类字符全含"
              >
                <template #append>
                  <ElButton @click="generateNewPassword">
                    生成
                  </ElButton>
                  <ElButton @click="copyNewPassword">
                    复制
                  </ElButton>
                </template>
              </ElInput>
            </ElFormItem>
            <ElFormItem
              label="确认新密码"
              prop="confirmPassword"
            >
              <ElInput
                v-model="pwdForm.confirmPassword"
                type="password"
                show-password
              />
            </ElFormItem>
            <ElFormItem>
              <ElButton
                type="primary"
                :loading="changingPwd"
                @click="submitPassword"
              >
                修改口令
              </ElButton>
            </ElFormItem>
          </ElForm>
          <ElAlert
            type="warning"
            :closable="false"
            show-icon
            title="改密成功后所有设备将被强制重新登录。"
          />
          <div style="margin-top: 16px">
            <ElButton
              type="danger"
              plain
              :loading="forcingLogout"
              @click="handleForceLogout"
            >
              强制下线全部会话
            </ElButton>
          </div>
        </ElCard>
      </ElCol>
    </ElRow>
  </div>
</template>
