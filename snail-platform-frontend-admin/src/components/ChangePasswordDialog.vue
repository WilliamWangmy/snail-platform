<script setup lang="ts">
import { reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import { changePassword } from '@/api/profile';
import { useAuthStore } from '@/stores/auth';
import { validatePassword } from '@/utils/password';
import PasswordHintInput from '@/components/PasswordHintInput.vue';

const props = defineProps<{ visible: boolean }>();
const emit = defineEmits<{ (e: 'closed'): void }>();

const auth = useAuthStore();

const formRef = ref<FormInstance>();
const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' });
const submitting = ref(false);

watch(
  () => props.visible,
  (open) => {
    if (open) {
      form.oldPassword = '';
      form.newPassword = '';
      form.confirmPassword = '';
      formRef.value?.clearValidate();
    }
  },
);

const rules: FormRules = {
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
        if (value !== form.newPassword) callback(new Error('两次输入的密码不一致'));
        else callback();
      },
      trigger: 'blur',
    },
  ],
};

/** 改密成功即令牌版本自增,所有设备(含本机)被强制重新登录,清本地态回登录页 */
async function submit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) return;
  const confirmed = await ElMessageBox.confirm(
    '修改成功后所有设备将被强制重新登录，确认修改？',
    '修改密码',
    { type: 'warning', confirmButtonText: '确认修改', cancelButtonText: '取消' },
  )
    .then(() => true)
    .catch(() => false);
  if (!confirmed) return;
  submitting.value = true;
  try {
    await changePassword({ oldPassword: form.oldPassword, newPassword: form.newPassword });
    ElMessage.success('密码已修改，请用新密码重新登录');
    auth.signOut();
    emit('closed');
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <ElDialog
    :model-value="visible"
    width="456px"
    align-center
    class="change-password-dialog"
    @update:model-value="emit('closed')"
  >
    <template #header>
      <h3 class="cp-title">修改密码</h3>
    </template>

    <ElForm
      ref="formRef"
      :model="form"
      :rules="rules"
      label-position="top"
      @submit.prevent="submit"
    >
      <ElFormItem
        label="原密码"
        prop="oldPassword"
      >
        <ElInput
          v-model="form.oldPassword"
          type="password"
          show-password
          size="large"
          autocomplete="current-password"
          placeholder="输入当前使用的密码"
        />
      </ElFormItem>
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
        type="warning"
        :closable="false"
        show-icon
        title="修改成功后所有设备将被强制重新登录。"
      />
    </ElForm>

    <template #footer>
      <ElButton @click="emit('closed')">
        取消
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
.cp-title {
  margin: 0;
  color: var(--app-ink);
  font-size: 17px;
  letter-spacing: -0.02em;
}
</style>
