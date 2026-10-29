<script setup lang="ts">
import { reactive, ref, watch } from 'vue';
import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
import AppIcon from '@/components/AppIcon.vue';
import PasswordHintInput from '@/components/PasswordHintInput.vue';
import { forceChangePassword } from '@/api/profile';
import { useAuthStore } from '@/stores/auth';
import { validatePassword } from '@/utils/password';
const props = defineProps<{ visible: boolean }>();
const emit = defineEmits<{ (e: 'closed'): void }>();

const auth = useAuthStore();

const formRef = ref<FormInstance>();
const form = reactive({ newPassword: '', confirmPassword: '' });
const submitting = ref(false);

watch(
  () => props.visible,
  (open) => {
    if (open) {
      form.newPassword = '';
      form.confirmPassword = '';
      formRef.value?.clearValidate();
    }
  },
);

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

/** 改密成功即令牌版本自增,当前会话已被服务端吊销,清本地态并由父级关窗回登录表单 */
async function submit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) return;
  submitting.value = true;
  try {
    await forceChangePassword({ newPassword: form.newPassword });
    ElMessage.success('密码已更新，请用新密码重新登录');
    auth.signOut();
    emit('closed');
  } finally {
    submitting.value = false;
  }
}

/** 不改密先走人:吊销服务端会话后回到登录表单 */
async function signOutHere(): Promise<void> {
  await auth.signOutEverywhere();
  emit('closed');
}
</script>

<template>
  <ElDialog
    :model-value="visible"
    width="456px"
    align-center
    class="force-change-dialog"
    :show-close="false"
    :close-on-click-modal="false"
    :close-on-escape="false"
  >
    <template #header>
      <div class="fc-head">
        <span class="fc-badge"><AppIcon
          name="shield"
          :size="19"
        /></span>
        <div>
          <h3>修改初始密码</h3>
          <p>首次登录或密码被重置后，须先设置新密码才能使用系统。</p>
        </div>
      </div>
    </template>

    <ElForm
      ref="formRef"
      :model="form"
      :rules="rules"
      label-position="top"
      @submit.prevent="submit"
    >
      <ElFormItem
        label="新密码"
        prop="newPassword"
      >
        <PasswordHintInput v-model="form.newPassword" />
      </ElFormItem>
      <ElFormItem
        label="确认新密码"
        prop="confirmPassword"
      >
        <ElInput
          v-model="form.confirmPassword"
          type="password"
          show-password
          size="large"
          autocomplete="new-password"
          placeholder="再次输入新密码"
          @keydown.enter.prevent="submit"
        />
      </ElFormItem>
      <ElAlert
        type="info"
        :closable="false"
        show-icon
        title="修改成功后当前会话立即失效，需用新密码重新登录。"
      />
    </ElForm>

    <template #footer>
      <ElButton
        class="fc-signout"
        @click="signOutHere"
      >
        退出登录
      </ElButton>
      <ElButton
        type="primary"
        :loading="submitting"
        @click="submit"
      >
        确认修改
      </ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
.fc-head {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.fc-badge {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  flex: none;
  border: 1px solid rgba(var(--app-primary-rgb), 0.28);
  border-radius: 11px;
  color: var(--app-primary);
  background: rgba(var(--app-primary-rgb), 0.1);
}

.fc-head h3 {
  margin: 0;
  color: var(--app-ink);
  font-size: 17px;
  letter-spacing: -0.02em;
}

.fc-head p {
  margin: 3px 0 0;
  color: var(--app-muted);
  font-size: 12px;
}

.fc-signout {
  cursor: pointer;
}
</style>
