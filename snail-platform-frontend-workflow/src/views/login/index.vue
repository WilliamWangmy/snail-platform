<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import type { FormInstance, FormRules } from 'element-plus';

import { getCaptcha } from '@/api/auth';
import { useAuthStore } from '@/stores/auth';

const router = useRouter();
const route = useRoute();
const auth = useAuthStore();

const formRef = ref<FormInstance>();
const submitting = ref(false);

const form = reactive({
  username: '',
  password: '',
  captchaKey: '',
  captchaCode: '',
});

const captchaImage = ref('');

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  captchaCode: [{ required: true, message: '请输入验证码', trigger: 'blur' }],
};

async function refreshCaptcha(): Promise<void> {
  const captcha = await getCaptcha();
  captchaImage.value = captcha.image;
  form.captchaKey = captcha.captchaKey;
  form.captchaCode = '';
}

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) return;

  submitting.value = true;
  try {
    await auth.login({ ...form });
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard';
    await router.push(redirect);
  } finally {
    submitting.value = false;
  }
}

onMounted(() => {
  void refreshCaptcha();
});
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <h1 class="login-title">
        Snail 流程管理平台
      </h1>
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @submit.prevent
      >
        <el-form-item
          label="用户名"
          prop="username"
        >
          <el-input
            v-model="form.username"
            placeholder="请输入用户名"
            autocomplete="username"
          />
        </el-form-item>
        <el-form-item
          label="密码"
          prop="password"
        >
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            show-password
            autocomplete="current-password"
          />
        </el-form-item>
        <el-form-item
          label="验证码"
          prop="captchaCode"
        >
          <div class="captcha-row">
            <el-input
              v-model="form.captchaCode"
              placeholder="请输入验证码"
              @keyup.enter="handleSubmit"
            />
            <img
              v-if="captchaImage"
              class="captcha-image"
              :src="captchaImage"
              alt="验证码"
              title="点击刷新"
              @click="refreshCaptcha"
            />
          </div>
        </el-form-item>
        <el-button
          class="login-button"
          type="primary"
          :loading="submitting"
          @click="handleSubmit"
        >
          登 录
        </el-button>
      </el-form>
    </div>
  </div>
</template>

<style scoped lang="scss">
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100vw;
  height: 100vh;
  background:
    radial-gradient(1200px 600px at 70% 20%, rgba(24, 95, 165, 0.25), transparent 60%),
    var(--snail-bg-deep);
}

.login-card {
  width: 400px;
  padding: 40px 36px 32px;
  border: 1px solid var(--snail-border);
  border-radius: var(--snail-radius-lg);
  background: var(--snail-bg-panel);
}

.login-title {
  margin: 0 0 28px;
  font-size: 22px;
  font-weight: 500;
  text-align: center;
  color: var(--snail-text-primary);
}

.captcha-row {
  display: flex;
  gap: 12px;
  width: 100%;

  .captcha-image {
    height: 40px;
    cursor: pointer;
    border-radius: 4px;
  }
}

.login-button {
  width: 100%;
  margin-top: 4px;
}
</style>
