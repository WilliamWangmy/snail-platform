<script setup lang="ts">
import { reactive, ref, watch } from 'vue';
import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
import { fetchProfile, updateProfile } from '@/api/profile';
import { useAuthStore } from '@/stores/auth';

const props = defineProps<{ visible: boolean }>();
const emit = defineEmits<{ (e: 'closed'): void }>();

const auth = useAuthStore();

const formRef = ref<FormInstance>();
const form = reactive({ nickname: '', email: '', phone: '' });
const submitting = ref(false);
const loading = ref(false);

watch(
  () => props.visible,
  async (open) => {
    if (!open) return;
    form.nickname = '';
    form.email = '';
    form.phone = '';
    formRef.value?.clearValidate();
    loading.value = true;
    try {
      // 顶栏显示名来自登录快照,这里以档案接口现值为准,避免快照滞后
      const profile = await fetchProfile();
      form.nickname = profile.nickname;
      form.email = profile.email ?? '';
      form.phone = profile.phone ?? '';
    } finally {
      loading.value = false;
    }
  },
);

const rules: FormRules = {
  nickname: [
    { required: true, message: '请输入显示名', trigger: 'blur' },
    { max: 30, message: '显示名至多 30 字', trigger: 'blur' },
  ],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
};

/** 存完资料必须重取会话:顶栏与侧栏的显示名来自登录快照而非档案接口 */
async function submit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) return;
  submitting.value = true;
  try {
    await updateProfile({
      nickname: form.nickname.trim(),
      email: form.email.trim() || null,
      phone: form.phone.trim() || null,
    });
    ElMessage.success('资料已更新');
    await auth.loadSession();
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
    class="profile-edit-dialog"
    @update:model-value="emit('closed')"
  >
    <template #header>
      <h3 class="pe-title">个人信息</h3>
    </template>

    <ElForm
      v-loading="loading"
      ref="formRef"
      :model="form"
      :rules="rules"
      label-position="top"
      @submit.prevent="submit"
    >
      <ElFormItem label="登录名">
        <ElInput
          :model-value="auth.user?.username"
          disabled
        />
      </ElFormItem>
      <ElFormItem
        label="显示名"
        prop="nickname"
      >
        <ElInput
          v-model="form.nickname"
          maxlength="30"
        />
      </ElFormItem>
      <ElFormItem
        label="邮箱"
        prop="email"
      >
        <ElInput v-model="form.email" />
      </ElFormItem>
      <ElFormItem
        label="手机号"
        prop="phone"
      >
        <ElInput v-model="form.phone" />
      </ElFormItem>
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
        保存
      </ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
.pe-title {
  margin: 0;
  color: var(--app-ink);
  font-size: 17px;
  letter-spacing: -0.02em;
}
</style>
