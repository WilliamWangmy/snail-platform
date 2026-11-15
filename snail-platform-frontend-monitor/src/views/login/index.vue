<template>
  <div class="login-page">
    <div class="login-card">
      <div class="brand">
        <span class="brand-mark">S</span>
        <div>
          <h1>蜗牛平台 · 系统监控</h1>
          <p>Snail Platform Monitor Console</p>
        </div>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="submit">
        <el-form-item prop="username" label="账号">
          <el-input v-model="form.username" placeholder="请输入账号" autocomplete="username" />
        </el-form-item>
        <el-form-item prop="password" label="密码">
          <el-input v-model="form.password" type="password" show-password placeholder="请输入密码" autocomplete="current-password" />
        </el-form-item>
        <el-form-item prop="captchaCode" label="图形验证码">
          <div class="captcha-row">
            <el-input v-model="form.captchaCode" placeholder="请输入验证码" />
            <img v-if="captcha?.image" :src="captcha.image" class="captcha-img" title="点击刷新" alt="验证码" @click="refreshCaptcha" />
            <el-button v-else :loading="captchaLoading" @click="refreshCaptcha">获取</el-button>
          </div>
        </el-form-item>
        <el-button type="primary" class="submit-btn" :loading="loading" @click="submit">登 录</el-button>
        <p v-if="errorMessage" class="error-msg">{{ errorMessage }}</p>
        <p class="hint">监控系统仅限平台管理员使用；非平台租户账号登录将被拒绝。</p>
      </el-form>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { onMounted, reactive, ref } from 'vue';
import type { FormInstance, FormRules } from 'element-plus';
import { useRoute, useRouter } from 'vue-router';
import { fetchCaptcha, type Captcha } from '@/api/auth';
import { useAuthStore } from '@/stores/auth';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

const formRef = ref<FormInstance | null>(null);
const form = reactive({ username: '', password: '', captchaCode: '' });
const captcha = ref<Captcha | null>(null);
const captchaLoading = ref(false);
const loading = ref(false);
const errorMessage = ref('');

const rules: FormRules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  captchaCode: [{ required: true, message: '请输入验证码', trigger: 'blur' }],
};

async function refreshCaptcha(): Promise<void> {
  captchaLoading.value = true;
  try {
    captcha.value = await fetchCaptcha();
    form.captchaCode = '';
  } finally {
    captchaLoading.value = false;
  }
}

async function submit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) return;
  loading.value = true;
  errorMessage.value = '';
  try {
    await auth.signIn({
      username: form.username,
      password: form.password,
      captchaKey: captcha.value?.captchaKey ?? '',
      captchaCode: form.captchaCode,
    });
    // 成功后回到来源页或总览(scene=monitor,后端非平台租户返回 400)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/overview';
    await router.replace(redirect);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '登录失败,请稍后重试';
    await refreshCaptcha();
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  void refreshCaptcha();
});
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--app-bg);
}

.login-card {
  width: 400px;
  padding: 32px 36px;
  border-radius: 12px;
  background: var(--panel-bg);
  border: 1px solid var(--border-color);
  box-shadow: var(--shadow-md);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
}

.brand-mark {
  width: 44px;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  background: var(--primary-color);
  color: var(--primary-contrast, #fff);
  font-size: 22px;
  font-weight: 700;
}

.brand h1 {
  font-size: 18px;
  margin: 0;
  color: var(--text-primary);
}

.brand p {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--text-secondary);
}

.captcha-row {
  display: flex;
  gap: 10px;
  width: 100%;
  align-items: center;
}

.captcha-img {
  height: 32px;
  border-radius: 4px;
  cursor: pointer;
  border: 1px solid var(--border-color);
}

.submit-btn {
  width: 100%;
  margin-top: 4px;
}

.error-msg {
  margin: 12px 0 0;
  color: var(--danger-color, #f56c6c);
  font-size: 13px;
}

.hint {
  margin: 16px 0 0;
  color: var(--text-secondary);
  font-size: 12px;
}
</style>
