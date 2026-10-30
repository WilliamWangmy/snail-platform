<script setup lang="ts">
import { onMounted, ref, useTemplateRef, watch } from 'vue';
import type { FormInstance } from 'element-plus';
import AppIcon from '@/components/AppIcon.vue';
import { useAuthStore } from '@/stores/auth';
import ForceChangePasswordDialog from './ForceChangePasswordDialog.vue';
import { useLogin } from './useLogin';

const auth = useAuthStore();
const formRef = useTemplateRef<FormInstance>('formRef');
const {
  form,
  rules,
  loading,
  remember,
  captcha,
  captchaState,
  statusLabel,
  forcePasswordChange,
  submit,
  refreshCaptcha,
} = useLogin(formRef);

const forceChangeVisible = ref(false);

watch(forcePasswordChange, (required) => {
  if (required) forceChangeVisible.value = true;
});

/** 刷新恢复的待改密会话:守卫已把它收拢到登录页,这里按会话标志补弹窗 */
onMounted(() => {
  if (auth.token && (auth.pendingPasswordChange || auth.user?.passwordChangeRequired === true)) {
    forceChangeVisible.value = true;
  }
});

/** 改密成功或退出登录后关窗,清掉已提交过的旧密码并换码 */
function onForceChangeClosed(): void {
  forceChangeVisible.value = false;
  form.password = '';
  void refreshCaptcha();
}
</script>

<template>
  <div class="login-page">
    <aside class="login-brand">
      <div class="brand-row">
        <span class="brand-mark"><AppIcon
          name="shell"
          :size="26"
        /></span>
        <div>
          <div class="brand-name">
            Snail<span>.</span>Admin
          </div>
          <div class="brand-sub">
            OPERATIONS CONSOLE
          </div>
        </div>
      </div>

      <div class="login-copy">
        <div class="login-kicker">
          <i></i>多租户运营控制台
        </div>
        <h1>让每一层组织<br />都<em>可控</em>可审。</h1>
        <p>租户、组织、角色与菜单授权统一收口；登录后按授权渲染导航与操作，页面权限与按钮权限同源。</p>
      </div>

      <div class="login-stats">
        <div><b>多租户</b><span>数据隔离在库层</span></div>
        <div><b>组织树</b><span>数据权限四档并集</span></div>
        <div><b>全留痕</b><span>增删改逐条落档</span></div>
      </div>
    </aside>

    <section class="login-panel-wrap">
      <div class="login-panel">
        <div class="login-mobile-brand">
          <span class="brand-mark"><AppIcon
            name="shell"
            :size="23"
          /></span>
          <div>
            <div class="brand-name">
              Snail<span>.</span>Admin
            </div>
            <div class="brand-sub">
              OPERATIONS CONSOLE
            </div>
          </div>
        </div>

        <div class="login-panel-top">
          <span>SECURE ACCESS</span>
          <span
            class="online"
            :class="{ warning: captchaState !== 'ready' }"
          ><i></i>{{ statusLabel }}</span>
        </div>

        <h2>欢迎回来</h2>
        <p class="login-lead">
          登录 <b>Snail Admin</b>，按你的授权进入工作台。
        </p>

        <ElForm
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          @submit.prevent="submit"
        >
          <ElFormItem
            label="账号"
            prop="username"
          >
            <ElInput
              v-model="form.username"
              size="large"
              autocomplete="username"
              placeholder="请输入账号"
              @keydown.enter.prevent="submit"
            >
              <template #prefix>
                <AppIcon
                  name="user"
                  :size="16"
                />
              </template>
            </ElInput>
          </ElFormItem>
          <ElFormItem
            label="密码"
            prop="password"
          >
            <ElInput
              v-model="form.password"
              type="password"
              show-password
              size="large"
              autocomplete="current-password"
              placeholder="请输入密码"
              @keydown.enter.prevent="submit"
            >
              <template #prefix>
                <AppIcon
                  name="lock"
                  :size="16"
                />
              </template>
            </ElInput>
          </ElFormItem>
          <ElFormItem
            label="验证码"
            prop="captchaCode"
          >
            <div class="captcha-row">
              <ElInput
                v-model="form.captchaCode"
                size="large"
                maxlength="4"
                autocomplete="off"
                placeholder="输入右侧 4 位验证码"
                @keydown.enter.prevent="submit"
              >
                <template #prefix>
                  <AppIcon
                    name="shield"
                    :size="16"
                  />
                </template>
              </ElInput>
              <button
                type="button"
                class="captcha-box"
                title="点击刷新验证码"
                @click="refreshCaptcha"
              >
                <img
                  v-if="captcha"
                  :src="captcha.image"
                  alt="验证码"
                />
                <span
                  v-else
                  class="captcha-loading"
                >{{ captchaState === 'failed' ? '点击重试' : '加载中' }}</span>
              </button>
            </div>
            <div class="captcha-hint">
              看不清？<button
                type="button"
                @click="refreshCaptcha"
              >
                换一张
              </button>
            </div>
          </ElFormItem>

          <div class="login-options">
            <ElCheckbox v-model="remember">
              记住账号
            </ElCheckbox>
            <span>SSL 加密传输</span>
          </div>

          <ElButton
            type="primary"
            size="large"
            native-type="button"
            class="login-submit"
            :loading="loading"
            :disabled="!captcha"
            @click="submit"
          >
            进入管理后台 <AppIcon
              name="arrow-right"
              :size="15"
            />
          </ElButton>
        </ElForm>

        <div class="login-foot">
          © 2026 Snail Platform
        </div>
      </div>
    </section>

    <ForceChangePasswordDialog
      :visible="forceChangeVisible"
      @closed="onForceChangeClosed"
    />
  </div>
</template>

<style src="./login.css" scoped></style>
