<script setup lang="ts">
import { computed, onMounted, ref, useTemplateRef, watch } from 'vue';
import type { FormInstance } from 'element-plus';
import AppIcon from '@/components/AppIcon.vue';
import { useAuthStore } from '@/stores/auth';
import ForceChangePasswordDialog from './ForceChangePasswordDialog.vue';
import { useLogin } from './useLogin';

const auth = useAuthStore();
const formRef = useTemplateRef<FormInstance>('formRef');
const smsFormRef = useTemplateRef<FormInstance>('smsFormRef');
const {
  form,
  rules,
  loading,
  remember,
  captcha,
  captchaState,
  statusLabel,
  forcePasswordChange,
  mode,
  switchMode,
  smsForm,
  smsRules,
  countdown,
  sending,
  smsLoading,
  smsPick,
  pickingUserId,
  submit,
  submitSms,
  sendSmsCode,
  pickAccount,
  onPickDialogClosed,
  externalLogin,
  refreshCaptcha,
} = useLogin(formRef, smsFormRef);

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

/** 其他登录方式:手机登录切换双模;三个 IM 渠道占位,接入时换成各自的 OAuth 跳转。 */
const otherMethods = computed(() => [
  {
    key: 'sms',
    label: mode.value === 'sms' ? '手机登录中' : '手机登录',
    icon: 'phone',
    active: mode.value === 'sms',
    action: () => void switchMode('sms'),
  },
  { key: 'dingtalk', label: '钉钉', icon: 'zap', active: false, action: () => externalLogin('钉钉') },
  { key: 'feishu', label: '飞书', icon: 'send', active: false, action: () => externalLogin('飞书') },
  { key: 'wecom', label: '企业微信', icon: 'chat', active: false, action: () => externalLogin('企业微信') },
]);

/** 候选账号头像字:昵称首字,退回登录名首字。 */
function accountInitial(name: string): string {
  return (name ?? '').trim().charAt(0) || '账';
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

        <button
          v-if="mode === 'sms'"
          type="button"
          class="login-back"
          @click="switchMode('password')"
        >
          <AppIcon
            name="chevron-left"
            :size="13"
          />返回账号密码登录
        </button>

        <!-- 账号密码登录 -->
        <ElForm
          v-if="mode === 'password'"
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

        <!-- 手机验证码登录 -->
        <ElForm
          v-else
          ref="smsFormRef"
          :model="smsForm"
          :rules="smsRules"
          label-position="top"
          @submit.prevent="submitSms"
        >
          <ElFormItem
            label="手机号"
            prop="phone"
          >
            <ElInput
              v-model="smsForm.phone"
              size="large"
              maxlength="11"
              autocomplete="tel"
              placeholder="请输入 11 位手机号"
              @keydown.enter.prevent="submitSms"
            >
              <template #prefix>
                <AppIcon
                  name="phone"
                  :size="16"
                />
              </template>
            </ElInput>
          </ElFormItem>
          <ElFormItem
            label="图形验证码"
            prop="captchaCode"
          >
            <div class="captcha-row">
              <ElInput
                v-model="smsForm.captchaCode"
                size="large"
                maxlength="4"
                autocomplete="off"
                placeholder="输入右侧 4 位验证码"
                @keydown.enter.prevent="sendSmsCode"
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
          </ElFormItem>
          <ElFormItem
            label="短信验证码"
            prop="smsCode"
          >
            <div class="sms-code-row">
              <ElInput
                v-model="smsForm.smsCode"
                size="large"
                maxlength="6"
                autocomplete="one-time-code"
                placeholder="6 位短信验证码"
                @keydown.enter.prevent="submitSms"
              >
                <template #prefix>
                  <AppIcon
                    name="bell"
                    :size="16"
                  />
                </template>
              </ElInput>
              <ElButton
                size="large"
                class="send-btn"
                native-type="button"
                :loading="sending"
                :disabled="countdown > 0 || !captcha"
                @click="sendSmsCode"
              >
                {{ countdown > 0 ? `${countdown}s 后重发` : '获取验证码' }}
              </ElButton>
            </div>
          </ElFormItem>

          <div class="login-options">
            <span>验证码 5 分钟内有效</span>
            <span>SSL 加密传输</span>
          </div>

          <ElButton
            type="primary"
            size="large"
            native-type="button"
            class="login-submit"
            :loading="smsLoading"
            @click="submitSms"
          >
            使用短信验证码登录 <AppIcon
              name="arrow-right"
              :size="15"
            />
          </ElButton>
        </ElForm>

        <div class="login-divider">
          <span>其他登录方式</span>
        </div>
        <div class="login-methods">
          <button
            v-for="item in otherMethods"
            :key="item.key"
            type="button"
            class="login-method"
            :class="{ active: item.active }"
            @click="item.action"
          >
            <AppIcon
              :name="item.icon"
              :size="15"
            />
            <span>{{ item.label }}</span>
          </button>
        </div>

        <div class="login-foot">
          © 2026 Snail Platform
        </div>
      </div>
    </section>

    <!-- 多租户账号选择:短信登录多命中时出现,pickKey 2 分钟一次性 -->
    <ElDialog
      :model-value="smsPick !== null"
      width="392px"
      align-center
      class="pick-dialog"
      append-to-body
      @update:model-value="(visible: boolean) => { if (!visible) onPickDialogClosed(); }"
    >
      <template #header>
        <div class="pick-head">
          <b>选择要登录的账号</b>
          <span>该手机号在多个租户有账号</span>
        </div>
      </template>
      <div class="account-list">
        <button
          v-for="account in smsPick?.accounts ?? []"
          :key="account.userId"
          type="button"
          class="account-item"
          :disabled="pickingUserId !== null"
          @click="pickAccount(account)"
        >
          <span class="account-avatar">{{ accountInitial(account.nickname || account.username) }}</span>
          <span class="account-main">
            <b>{{ account.nickname || account.username }}</b>
            <i>{{ account.tenantName }} · {{ account.username }}</i>
          </span>
          <span
            v-if="pickingUserId === account.userId"
            class="account-picking"
          >登录中…</span>
          <AppIcon
            v-else
            name="chevron-right"
            :size="14"
          />
        </button>
      </div>
      <template #footer>
        <span class="pick-hint">选择凭证 2 分钟内有效，超时或放弃后需重新获取短信验证码</span>
      </template>
    </ElDialog>

    <ForceChangePasswordDialog
      :visible="forceChangeVisible"
      @closed="onForceChangeClosed"
    />
  </div>
</template>

<style src="./login.css" scoped></style>
