<script setup lang="ts">
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
import { forceChangePassword } from '@/api/profile';
import { useAuthStore } from '@/stores/auth';
import { copyText, generatePassword, validatePassword } from '@/utils/password';

const router = useRouter();
const auth = useAuthStore();

const formRef = ref<FormInstance>();
const form = reactive({ newPassword: '', confirmPassword: '' });
const submitting = ref(false);

const rules: FormRules = {
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
        if (value !== form.newPassword) callback(new Error('两次输入的密码不一致'));
        else callback();
      },
      trigger: 'blur',
    },
  ],
};

function generateNewPassword(): void {
  form.newPassword = generatePassword();
}

async function copyNewPassword(): Promise<void> {
  if (!form.newPassword) return;
  if (await copyText(form.newPassword)) {
    ElMessage.success('已复制到剪贴板');
  } else {
    ElMessage.error('复制失败,请手动复制');
  }
}

/** 改密成功即令牌版本自增,当前会话已被服务端吊销,清本地态回登录页走全新登录 */
async function submit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) return;
  submitting.value = true;
  try {
    await forceChangePassword({ newPassword: form.newPassword });
    ElMessage.success('密码已更新,请重新登录');
    auth.signOut();
    await router.replace('/login');
  } finally {
    submitting.value = false;
  }
}

/** 不改密先走人:走标准登出,服务端吊销本会话 */
async function signOutHere(): Promise<void> {
  await auth.signOutEverywhere();
  await router.replace('/login');
}
</script>

<template>
  <div class="force-change-page">
    <ElCard
      shadow="never"
      class="force-change-card"
    >
      <template #header>
        <div class="card-head">
          <h2>修改初始密码</h2>
          <p>首次登录或密码被重置后,须先设置新密码才能使用系统。</p>
        </div>
      </template>
      <ElForm
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="90px"
        @submit.prevent="submit"
      >
        <ElFormItem
          label="新密码"
          prop="newPassword"
        >
          <ElInput
            v-model="form.newPassword"
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
          label="确认密码"
          prop="confirmPassword"
        >
          <ElInput
            v-model="form.confirmPassword"
            type="password"
            show-password
          />
        </ElFormItem>
        <ElFormItem>
          <ElButton
            type="primary"
            native-type="submit"
            :loading="submitting"
          >
            确认修改
          </ElButton>
          <ElButton @click="signOutHere">
            退出登录
          </ElButton>
        </ElFormItem>
      </ElForm>
      <ElAlert
        type="info"
        :closable="false"
        show-icon
        title="修改成功后当前会话立即失效,需用新密码重新登录。"
      />
    </ElCard>
  </div>
</template>

<style scoped>
.force-change-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--el-bg-color-page, #f2f3f5);
}

.force-change-card {
  width: 460px;
}

.card-head h2 {
  margin: 0 0 8px;
  font-size: 20px;
}

.card-head p {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
