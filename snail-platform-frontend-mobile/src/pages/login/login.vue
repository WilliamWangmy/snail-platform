<template>
  <view class="page">
    <!-- 品牌区 -->
    <view class="brand">
      <view class="logo">
        <wd-icon name="secured" size="60rpx" color="#ffffff" />
      </view>
      <text class="title">Snail 蜗牛平台</text>
      <text class="subtitle">AI 时代的全能开发者综合平台</text>
    </view>

    <!-- 双模分段 Tab -->
    <view class="tabs">
      <text class="tab" :class="{ on: mode === 'pwd' }" @click="switchMode('pwd')">密码登录</text>
      <text class="tab" :class="{ on: mode === 'sms' }" @click="switchMode('sms')">短信登录</text>
    </view>

    <!-- 密码登录 -->
    <view v-if="mode === 'pwd'">
      <view class="field">
        <wd-icon name="user" size="36rpx" color="#b4b2a9" />
        <input v-model="pwdForm.username" class="input" placeholder="请输入账号" :placeholder-style="placeholderStyle" />
      </view>
      <view class="field">
        <wd-icon name="lock-on" size="36rpx" color="#b4b2a9" />
        <input
          v-model="pwdForm.password"
          class="input"
          :password="!showPassword"
          placeholder="请输入密码"
          :placeholder-style="placeholderStyle"
        />
        <wd-icon
          :name="showPassword ? 'view' : 'eye-close'"
          size="36rpx"
          color="#b4b2a9"
          custom-class="eye"
          @click="showPassword = !showPassword"
        />
      </view>
      <view class="field">
        <wd-icon name="secured" size="36rpx" color="#b4b2a9" />
        <input
          v-model="pwdForm.captchaCode"
          class="input"
          placeholder="4 位验证码"
          maxlength="4"
          :placeholder-style="placeholderStyle"
        />
        <image
          v-if="auth.captcha"
          class="captcha-img"
          :src="auth.captcha.image"
          @click="refreshCaptcha()"
        />
        <view v-else class="captcha-img captcha-refresh" @click="refreshCaptcha()">
          <wd-icon name="refresh" size="32rpx" color="#888780" />
        </view>
      </view>

      <view class="row-between">
        <view class="remember" @click="remember = !remember">
          <view class="checkbox" :class="{ on: remember }">
            <wd-icon v-if="remember" name="check" size="22rpx" color="#ffffff" />
          </view>
          <text class="remember-text">记住账号</text>
        </view>
        <text class="forgot" @click="hint('请联系管理员在后台重置密码')">忘记密码？</text>
      </view>

      <view class="btn-primary" :class="{ loading: submitting }" @click="onPwdLogin">
        {{ submitting ? '登录中…' : '登 录' }}
      </view>
    </view>

    <!-- 短信登录 -->
    <view v-else>
      <view class="field">
        <wd-icon name="mobile" size="36rpx" color="#b4b2a9" />
        <input
          v-model="smsForm.phone"
          class="input"
          type="number"
          maxlength="11"
          placeholder="请输入手机号"
          :placeholder-style="placeholderStyle"
        />
      </view>
      <view class="field">
        <wd-icon name="secured" size="36rpx" color="#b4b2a9" />
        <input
          v-model="smsForm.captchaCode"
          class="input"
          placeholder="4 位图形验证码"
          maxlength="4"
          :placeholder-style="placeholderStyle"
        />
        <image
          v-if="auth.captcha"
          class="captcha-img"
          :src="auth.captcha.image"
          @click="refreshCaptcha()"
        />
        <view v-else class="captcha-img captcha-refresh" @click="refreshCaptcha()">
          <wd-icon name="refresh" size="32rpx" color="#888780" />
        </view>
      </view>
      <view class="field">
        <wd-icon name="notification" size="36rpx" color="#b4b2a9" />
        <input
          v-model="smsForm.smsCode"
          class="input"
          type="number"
          maxlength="6"
          placeholder="6 位短信验证码"
          :placeholder-style="placeholderStyle"
        />
        <text class="sms-btn" :class="{ disabled: smsCooldown > 0 }" @click="onSendSms">
          {{ smsCooldown > 0 ? `${smsCooldown}s 后重发` : '获取验证码' }}
        </text>
      </view>

      <view class="row-between">
        <view></view>
        <text class="forgot" @click="hint('收不到短信？请检查手机号或稍后重试')">收不到验证码？</text>
      </view>

      <view class="btn-primary" :class="{ loading: submitting }" @click="onSmsLogin">
        {{ submitting ? '登录中…' : '登 录' }}
      </view>
    </view>

    <!-- SSO -->
    <view class="divider">
      <text class="divider-text">其他登录方式</text>
    </view>
    <view class="sso-row">
      <view class="sso-item" @click="hint('钉钉 OAuth 登录接入中')">
        <view class="sso-ico ding">钉</view>
        <text class="sso-label">钉钉</text>
      </view>
      <view class="sso-item" @click="hint('飞书 OAuth 登录接入中')">
        <view class="sso-ico fei">飞</view>
        <text class="sso-label">飞书</text>
      </view>
      <view class="sso-item" @click="hint('企业微信 OAuth 登录接入中')">
        <view class="sso-ico wecom">企</view>
        <text class="sso-label">企业微信</text>
      </view>
    </view>

    <!-- 协议 -->
    <view class="agreement">
      <view class="checkbox" :class="{ on: agreed }" @click="agreed = !agreed">
        <wd-icon v-if="agreed" name="check" size="22rpx" color="#ffffff" />
      </view>
      <text class="agreement-text">
        我已阅读并同意
        <text class="agreement-link" @click="hint('《服务协议》占位')">《服务协议》</text>
        与
        <text class="agreement-link" @click="hint('《隐私政策》占位')">《隐私政策》</text>
      </text>
    </view>

    <wd-toast />
  </view>
</template>

<script setup lang="ts">
import { onMounted } from 'vue';
import { onUnload } from '@dcloudio/uni-app';
import { reactive, ref } from 'vue';
import { useToast } from 'wot-design-uni';
import { isSmsAccountsResult, type SmsAccountOption } from '@/api/auth';
import { useAuthStore } from '@/stores/auth';

/** 记住的是账号名而非登录态(与 admin 同口径)。 */
const REMEMBER_KEY = 'snail_mobile_login_account_v1';
/** 发送短信验证码的间隔秒数(与后端 60s 间隔闸对齐)。 */
const SMS_RESEND_SECONDS = 60;

const auth = useAuthStore();
const toast = useToast();

const mode = ref<'pwd' | 'sms'>('pwd');
const submitting = ref(false);
const showPassword = ref(false);
const agreed = ref(false);
const remember = ref(false);
const smsCooldown = ref(0);

const pwdForm = reactive({ username: '', password: '', captchaCode: '' });
const smsForm = reactive({ phone: '', captchaCode: '', smsCode: '' });

const placeholderStyle = 'color: #b4b2a9';

onMounted(() => {
  const remembered = uni.getStorageSync(REMEMBER_KEY) as string;
  if (remembered) {
    pwdForm.username = remembered;
    remember.value = true;
  }
  refreshCaptcha().catch(() =>
    toast.show({ msg: '验证码加载失败，请检查网络', iconName: 'error' }),
  );
});
onUnload(() => stopCooldownTimer());

/* ------------------------- 密码登录 ------------------------- */

async function onPwdLogin(): Promise<void> {
  if (submitting.value) return;
  if (!agreed.value) {
    toast.show({ msg: '请先阅读并同意服务协议与隐私政策', iconName: 'warning' });
    return;
  }
  if (!pwdForm.username || !pwdForm.password) {
    toast.show({ msg: '请输入账号与密码', iconName: 'warning' });
    return;
  }
  if (pwdForm.captchaCode.length !== 4) {
    toast.show({ msg: '请输入 4 位验证码', iconName: 'warning' });
    return;
  }
  submitting.value = true;
  try {
    await auth.login(pwdForm);
    if (remember.value) uni.setStorageSync(REMEMBER_KEY, pwdForm.username);
    else uni.removeStorageSync(REMEMBER_KEY);
    afterLogin(auth.user?.passwordChangeRequired === true);
  } catch (error) {
    failHint(error);
  } finally {
    submitting.value = false;
  }
}

/* ------------------------- 短信登录 ------------------------- */

let cooldownTimer: ReturnType<typeof setInterval> | null = null;

function stopCooldownTimer(): void {
  if (cooldownTimer) {
    clearInterval(cooldownTimer);
    cooldownTimer = null;
  }
}

async function onSendSms(): Promise<void> {
  if (smsCooldown.value > 0) return;
  if (!/^1\d{10}$/.test(smsForm.phone)) {
    toast.show({ msg: '请输入正确的手机号', iconName: 'warning' });
    return;
  }
  if (smsForm.captchaCode.length !== 4) {
    toast.show({ msg: '请输入 4 位图形验证码', iconName: 'warning' });
    return;
  }
  if (!auth.captcha) {
    toast.show({ msg: '图形验证码未就绪，请点击刷新', iconName: 'warning' });
    return;
  }
  try {
    await auth.sendSmsCaptcha(smsForm.phone, auth.captcha.captchaKey, smsForm.captchaCode);
    toast.show({ msg: '验证码已发送' });
    smsCooldown.value = SMS_RESEND_SECONDS;
    cooldownTimer = setInterval(() => {
      smsCooldown.value -= 1;
      if (smsCooldown.value <= 0) stopCooldownTimer();
    }, 1000);
  } catch (error) {
    failHint(error);
  }
}

async function onSmsLogin(): Promise<void> {
  if (submitting.value) return;
  if (!agreed.value) {
    toast.show({ msg: '请先阅读并同意服务协议与隐私政策', iconName: 'warning' });
    return;
  }
  if (!/^1\d{10}$/.test(smsForm.phone)) {
    toast.show({ msg: '请输入正确的手机号', iconName: 'warning' });
    return;
  }
  if (smsForm.smsCode.length !== 6) {
    toast.show({ msg: '请输入 6 位短信验证码', iconName: 'warning' });
    return;
  }
  submitting.value = true;
  try {
    const result = await auth.loginSms({ phone: smsForm.phone, smsCode: smsForm.smsCode });
    if (isSmsAccountsResult(result)) {
      await pickAccount(result.pickKey, result.accounts);
    } else {
      afterLogin(result.passwordChangeRequired);
    }
  } catch (error) {
    failHint(error);
  } finally {
    submitting.value = false;
  }
}

/** 多账号命中:选租户账号换正式令牌(pickKey 一次一用)。 */
async function pickAccount(pickKey: string, accounts: SmsAccountOption[]): Promise<void> {
  const labels = accounts.map((a) => `${a.nickname}（${a.tenantName}）`);
  const picked = await new Promise<SmsAccountOption>((resolve, reject) => {
    uni.showActionSheet({
      itemList: labels.slice(0, 6),
      success: ({ tapIndex }) => resolve(accounts[tapIndex]),
      fail: () => reject(new Error('已取消选择')),
    });
  });
  try {
    const result = await auth.pickSmsAccount(pickKey, picked.userId);
    afterLogin(result.passwordChangeRequired);
  } catch (error) {
    failHint(error);
    smsForm.smsCode = '';
  }
}

function afterLogin(passwordChangeRequired: boolean): void {
  if (passwordChangeRequired) {
    // 强制改密:只录新密码,成功后引导重登(对齐 admin ForceChangePasswordDialog 语义)
    uni.reLaunch({ url: '/pages/profile/change-password?mode=force' });
    return;
  }
  uni.reLaunch({ url: '/pages/index/index' });
}

/* ------------------------- 公共 ------------------------- */

function switchMode(target: 'pwd' | 'sms'): void {
  mode.value = target;
}

async function refreshCaptcha(): Promise<void> {
  pwdForm.captchaCode = '';
  smsForm.captchaCode = '';
  await auth.refreshCaptcha();
}

function failHint(error: unknown): void {
  const message = error instanceof Error ? error.message : '登录失败';
  toast.show({ msg: message, iconName: 'error' });
  pwdForm.captchaCode = '';
  smsForm.captchaCode = '';
  refreshCaptcha().catch(() => {});
}

function hint(msg: string): void {
  toast.show({ msg, iconName: 'warning' });
}
</script>


<style lang="scss" src="./login.scss" scoped></style>
