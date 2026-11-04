<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, type FormInstance, type FormRules } from 'element-plus';

import {
  getCaptcha,
  isLoginAccounts,
  loginBySms,
  pickSmsAccount,
  sendSmsCaptcha,
} from '@/api/auth';
import type { LoginSuccessVO, SmsAccountOption } from '@/types/auth';
import AppIcon from '@/components/AppIcon.vue';
import { useAuthStore } from '@/stores/auth';

/** 发送短信验证码的间隔秒数（与后端 60s 间隔闸对齐） */
const SMS_RESEND_SECONDS = 60;

const router = useRouter();
const route = useRoute();
const auth = useAuthStore();

const formRef = ref<FormInstance>();
const smsFormRef = ref<FormInstance>();
const submitting = ref(false);
const captchaLoading = ref(false);

/** 登录方式：账号密码 / 手机验证码双模 */
const mode = ref<'password' | 'sms'>('password');

const form = reactive({
  username: '',
  password: '',
  captchaKey: '',
  captchaCode: '',
});

/* ------------------------- 短信验证码登录 ------------------------- */

const smsForm = reactive({
  phone: '',
  captchaKey: '',
  captchaCode: '',
  smsCode: '',
});

const countdown = ref(0);
const sending = ref(false);
const smsLoading = ref(false);
/** 多账号命中态：非空即弹租户选择对话框（pickKey 2 分钟一次性） */
const smsPick = ref<{ pickKey: string; accounts: SmsAccountOption[] } | null>(null);
const pickingUserId = ref<number | null>(null);

let countdownTimer: number | undefined;

function stopCountdown(): void {
  if (countdownTimer !== undefined) {
    window.clearInterval(countdownTimer);
    countdownTimer = undefined;
  }
}

function startCountdown(): void {
  countdown.value = SMS_RESEND_SECONDS;
  stopCountdown();
  countdownTimer = window.setInterval(() => {
    countdown.value -= 1;
    if (countdown.value <= 0) stopCountdown();
  }, 1000);
}

onUnmounted(stopCountdown);

const captchaImage = ref('');
const year = new Date().getFullYear();

const rules: FormRules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  captchaCode: [{ required: true, message: '请输入验证码', trigger: 'blur' }],
};

const smsRules: FormRules = {
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1\d{10}$/, message: '手机号格式不正确', trigger: 'blur' },
  ],
  captchaCode: [{ required: true, message: '请输入验证码', trigger: 'blur' }],
  smsCode: [
    { required: true, message: '请输入短信验证码', trigger: 'blur' },
    { len: 6, message: '短信验证码为 6 位', trigger: 'blur' },
  ],
};

/** 验证码一次即失效：换码并清空两个表单已输入的图形码 */
async function refreshCaptcha(): Promise<void> {
  captchaLoading.value = true;
  try {
    const captcha = await getCaptcha();
    captchaImage.value = captcha.image;
    form.captchaKey = captcha.captchaKey;
    form.captchaCode = '';
    smsForm.captchaKey = captcha.captchaKey;
    smsForm.captchaCode = '';
  } finally {
    captchaLoading.value = false;
  }
}

/** 切登录方式：换一张新图形码，避免上个表单里已提交过的码被复用 */
async function switchMode(next: 'password' | 'sms'): Promise<void> {
  if (mode.value === next) return;
  mode.value = next;
  await refreshCaptcha();
}

function redirectTarget(): string {
  const raw = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard';
  return raw.startsWith('/') && !raw.startsWith('//') ? raw : '/dashboard';
}

/** 登录成功的统一收口：落令牌进 redirect */
async function handleLoginSuccess(success: LoginSuccessVO): Promise<void> {
  auth.applyLoginSuccess(success);
  await router.push(redirectTarget());
}

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) return;

  submitting.value = true;
  try {
    await auth.login({ ...form });
    await router.push(redirectTarget());
  } finally {
    submitting.value = false;
  }
}

/** 发送短信验证码：人机闸（图形码）在先，成功后旧图形码即作废 */
async function sendSmsCode(): Promise<void> {
  if (countdown.value > 0 || !smsForm.captchaKey) return;
  const valid = await smsFormRef.value?.validateField(['phone', 'captchaCode']).catch(() => false);
  if (!valid) return;
  sending.value = true;
  try {
    await sendSmsCaptcha({
      phone: smsForm.phone.trim(),
      captchaKey: smsForm.captchaKey,
      captchaCode: smsForm.captchaCode.trim(),
    });
    startCountdown();
    ElMessage.success('验证码已发送，请查收短信');
    await refreshCaptcha();
  } catch {
    // 失败文案由请求拦截器统一弹出（锁定/日额/账号不存在等）；图形码可能已被消费，换新
    await refreshCaptcha();
  } finally {
    sending.value = false;
  }
}

/** 短信登录：单命中直接签发；多命中挂起 pickKey 弹租户选择对话框 */
async function handleSubmitSms(): Promise<void> {
  const valid = await smsFormRef.value?.validate().catch(() => false);
  if (!valid) return;
  smsLoading.value = true;
  try {
    const result = await loginBySms({ phone: smsForm.phone.trim(), smsCode: smsForm.smsCode.trim() });
    if (isLoginAccounts(result)) {
      smsPick.value = result;
    } else {
      await handleLoginSuccess(result);
    }
  } catch {
    // 错码/锁定等文案已由拦截器弹出；短信码 5 分钟内可重试，输入保留
  } finally {
    smsLoading.value = false;
  }
}

/** 选定租户账号换令牌：pickKey 一次一用，失败即作废，须重走短信登录 */
async function pickAccount(account: SmsAccountOption): Promise<void> {
  if (!smsPick.value || pickingUserId.value !== null) return;
  pickingUserId.value = account.userId;
  try {
    const success = await pickSmsAccount({ pickKey: smsPick.value.pickKey, userId: account.userId });
    smsPick.value = null;
    await handleLoginSuccess(success);
  } catch {
    // pickKey 可能已过期或被用掉：关对话框，回短信表单重新走一遍
    smsPick.value = null;
    smsForm.smsCode = '';
  } finally {
    pickingUserId.value = null;
  }
}

/** 放弃选择：短信码已随首调核销，清码让用户重新发送 */
function onPickDialogClosed(): void {
  smsPick.value = null;
  smsForm.smsCode = '';
}

/* ------------------------- 其他登录方式（占位） ------------------------- */

/** 钉钉/飞书/企业微信登录尚未开通：后端 login_type 已留编码位，接入时换跳转即可 */
function externalLogin(name: string): void {
  ElMessage.info(`${name}登录暂未开通，请使用账号密码或手机验证码登录`);
}

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

/** 候选账号头像字：昵称首字，退回登录名首字 */
function accountInitial(name: string): string {
  return (name ?? '').trim().charAt(0) || '账';
}

onMounted(() => {
  void refreshCaptcha();
});
</script>

<template>
  <main class="login-page">
    <section
      class="login-brand"
      aria-label="产品介绍"
    >
      <div class="brand">
        <span class="brand-mark">
          <svg
            width="23"
            height="23"
            viewBox="0 0 24 24"
            fill="none"
            aria-hidden="true"
          >
            <path
              d="M17.8 6.2A7.2 7.2 0 1 0 18 17.4"
              stroke="currentColor"
              stroke-width="1.7"
              stroke-linecap="round"
            />
            <circle
              cx="7.6"
              cy="9.2"
              r="2.1"
              stroke="currentColor"
              stroke-width="1.5"
            />
            <circle
              cx="14.4"
              cy="14.6"
              r="1.6"
              fill="currentColor"
            />
          </svg>
        </span>
        <span class="brand-copy">
          <span class="brand-name">Snail<span>.</span>Flow</span>
          <span class="brand-sub">FLOW ORCHESTRATION</span>
        </span>
      </div>

      <div class="login-copy">
        <div class="login-kicker">
          PROCESS CONTROL ROOM / 01
        </div>
        <h1>让每一次流转<br />都<em>有迹可循</em>。</h1>
        <p>从模型设计、版本发布到实例执行与任务办理，流程资产在同一张图上被看见、被校验、被追溯。</p>

        <div
          class="login-visual"
          aria-label="流程节点示意图"
        >
          <div class="login-visual-head">
            <span>LIVE PROCESS MAP · 采购审批</span>
            <span class="live-dot">RUNNING</span>
          </div>
          <svg
            class="flow-preview"
            viewBox="0 0 580 132"
            role="img"
            aria-label="开始、部门审批、金额判断、财务复核、归档的流程示意"
          >
            <defs>
              <marker
                id="lpArrow"
                viewBox="0 0 8 8"
                refX="7"
                refY="4"
                markerWidth="6"
                markerHeight="6"
                orient="auto"
              >
                <path
                  d="M0 0 8 4 0 8Z"
                  fill="#5b7c7e"
                />
              </marker>
            </defs>
            <path
              class="lp-line hot"
              d="M54 66 H92"
            />
            <path
              class="lp-line"
              d="M202 66 H252"
              marker-end="url(#lpArrow)"
            />
            <path
              class="lp-line"
              d="M316 66 H362"
              marker-end="url(#lpArrow)"
            />
            <path
              class="lp-line"
              d="M472 66 H518"
              marker-end="url(#lpArrow)"
            />
            <circle
              cx="38"
              cy="66"
              r="13"
              fill="#0c222c"
              stroke="#1ec9a5"
              stroke-width="1.6"
            />
            <circle
              cx="38"
              cy="66"
              r="4"
              fill="#1ec9a5"
            />
            <rect
              x="92"
              y="43"
              width="110"
              height="46"
              rx="8"
              fill="#0c222c"
              stroke="#607f80"
              stroke-width="1.2"
            />
            <text
              x="147"
              y="63"
              text-anchor="middle"
              fill="#d8e8e5"
              font-size="10"
              font-family="'PingFang SC','Microsoft YaHei',sans-serif"
            >部门审批</text>
            <text
              x="147"
              y="77"
              text-anchor="middle"
              fill="#718d8a"
              font-size="8"
              font-family="'JetBrains Mono',Consolas,monospace"
            >zhang.li</text>
            <path
              d="M284 42 316 66 284 90 252 66Z"
              fill="rgba(232,173,85,.08)"
              stroke="#e8ad55"
              stroke-width="1.3"
            />
            <text
              x="284"
              y="70"
              text-anchor="middle"
              fill="#e8c891"
              font-size="10"
              font-family="'PingFang SC','Microsoft YaHei',sans-serif"
            >金额</text>
            <text
              x="284"
              y="104"
              text-anchor="middle"
              fill="#8ea6a3"
              font-size="8"
              font-family="'JetBrains Mono',Consolas,monospace"
            >amount &gt; 5000</text>
            <rect
              x="362"
              y="43"
              width="110"
              height="46"
              rx="8"
              fill="rgba(30,201,165,.07)"
              stroke="#1ec9a5"
              stroke-width="1.4"
            />
            <text
              x="417"
              y="63"
              text-anchor="middle"
              fill="#eafffb"
              font-size="10"
              font-family="'PingFang SC','Microsoft YaHei',sans-serif"
            >财务复核</text>
            <text
              x="417"
              y="77"
              text-anchor="middle"
              fill="#78bdb1"
              font-size="8"
              font-family="'JetBrains Mono',Consolas,monospace"
            >wang.min</text>
            <circle
              cx="532"
              cy="66"
              r="13"
              fill="#0c222c"
              stroke="#607f80"
              stroke-width="1.5"
            />
            <circle
              cx="532"
              cy="66"
              r="5"
              fill="none"
              stroke="#607f80"
              stroke-width="1.4"
            />
            <text
              x="147"
              y="112"
              text-anchor="middle"
              fill="#617d7b"
              font-size="8"
              font-family="'JetBrains Mono',Consolas,monospace"
            >STEP 01</text>
            <text
              x="284"
              y="118"
              text-anchor="middle"
              fill="#617d7b"
              font-size="8"
              font-family="'JetBrains Mono',Consolas,monospace"
            >GATEWAY</text>
            <text
              x="417"
              y="112"
              text-anchor="middle"
              fill="#617d7b"
              font-size="8"
              font-family="'JetBrains Mono',Consolas,monospace"
            >STEP 03</text>
          </svg>
        </div>
      </div>

      <div
        class="login-stats"
        aria-label="平台能力"
      >
        <div class="login-stat">
          <b>01</b><span>模型设计</span>
        </div>
        <div class="login-stat">
          <b>02</b><span>版本发布</span>
        </div>
        <div class="login-stat">
          <b>03</b><span>实例执行</span>
        </div>
        <div class="login-stat">
          <b>04</b><span>任务办理</span>
        </div>
      </div>
    </section>

    <section class="login-panel-wrap">
      <div class="login-panel">
        <div class="login-panel-top">
          <span>SECURE ACCESS</span>
          <span class="login-status">系统运行正常</span>
        </div>

        <h2>欢迎回来</h2>
        <p class="login-lead">
          登录 Snail Flow，继续处理你的流程资产与待办任务。
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
        <el-form
          v-if="mode === 'password'"
          ref="formRef"
          class="login-form"
          :model="form"
          :rules="rules"
          label-position="top"
          @submit.prevent="handleSubmit"
        >
          <el-form-item
            prop="username"
            class="login-field"
          >
            <template #label>
              <span class="field-label">账号</span>
            </template>
            <el-input
              v-model="form.username"
              placeholder="请输入账号"
              autocomplete="username"
            >
              <template #prefix>
                <AppIcon name="user" />
              </template>
            </el-input>
          </el-form-item>

          <el-form-item
            prop="password"
            class="login-field"
          >
            <template #label>
              <span class="field-label">密码</span>
            </template>
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              show-password
              autocomplete="current-password"
            >
              <template #prefix>
                <AppIcon name="lock" />
              </template>
            </el-input>
          </el-form-item>

          <el-form-item
            prop="captchaCode"
            class="login-field"
          >
            <template #label>
              <span class="field-label">验证码</span>
            </template>
            <div class="captcha-row">
              <el-input
                v-model="form.captchaCode"
                maxlength="6"
                placeholder="请输入验证码"
                autocomplete="off"
              >
                <template #prefix>
                  <AppIcon name="shield" />
                </template>
              </el-input>
              <button
                class="captcha-box"
                type="button"
                title="点击刷新验证码"
                aria-label="验证码图片，点击刷新"
                :disabled="captchaLoading"
                @click="refreshCaptcha"
              >
                <img
                  v-if="captchaImage"
                  :src="captchaImage"
                  alt="验证码"
                />
                <span
                  v-else
                  class="captcha-loading"
                >加载中</span>
              </button>
            </div>
          </el-form-item>

          <el-button
            class="login-submit"
            type="primary"
            size="large"
            native-type="submit"
            :loading="submitting"
            :disabled="submitting"
          >
            {{ submitting ? '正在验证…' : '登录控制台' }}
            <AppIcon
              v-if="!submitting"
              name="arrow-right"
            />
          </el-button>
        </el-form>

        <!-- 手机验证码登录 -->
        <el-form
          v-else
          ref="smsFormRef"
          class="login-form"
          :model="smsForm"
          :rules="smsRules"
          label-position="top"
          @submit.prevent="handleSubmitSms"
        >
          <el-form-item
            prop="phone"
            class="login-field"
          >
            <template #label>
              <span class="field-label">手机号</span>
            </template>
            <el-input
              v-model="smsForm.phone"
              maxlength="11"
              placeholder="请输入 11 位手机号"
              autocomplete="tel"
            >
              <template #prefix>
                <AppIcon name="phone" />
              </template>
            </el-input>
          </el-form-item>

          <el-form-item
            prop="captchaCode"
            class="login-field"
          >
            <template #label>
              <span class="field-label">图形验证码</span>
            </template>
            <div class="captcha-row">
              <el-input
                v-model="smsForm.captchaCode"
                maxlength="6"
                placeholder="请输入验证码"
                autocomplete="off"
                @keydown.enter.prevent="sendSmsCode"
              >
                <template #prefix>
                  <AppIcon name="shield" />
                </template>
              </el-input>
              <button
                class="captcha-box"
                type="button"
                title="点击刷新验证码"
                aria-label="验证码图片，点击刷新"
                :disabled="captchaLoading"
                @click="refreshCaptcha"
              >
                <img
                  v-if="captchaImage"
                  :src="captchaImage"
                  alt="验证码"
                />
                <span
                  v-else
                  class="captcha-loading"
                >加载中</span>
              </button>
            </div>
          </el-form-item>

          <el-form-item
            prop="smsCode"
            class="login-field"
          >
            <template #label>
              <span class="field-label">短信验证码</span>
            </template>
            <div class="sms-code-row">
              <el-input
                v-model="smsForm.smsCode"
                maxlength="6"
                placeholder="6 位短信验证码"
                autocomplete="one-time-code"
              >
                <template #prefix>
                  <AppIcon name="bell" />
                </template>
              </el-input>
              <el-button
                class="send-btn"
                size="large"
                native-type="button"
                :loading="sending"
                :disabled="countdown > 0 || !smsForm.captchaKey"
                @click="sendSmsCode"
              >
                {{ countdown > 0 ? `${countdown}s 后重发` : '获取验证码' }}
              </el-button>
            </div>
          </el-form-item>

          <div class="login-hint-row">
            <span>验证码 5 分钟内有效</span>
            <span class="mono">SMS OTP</span>
          </div>

          <el-button
            class="login-submit"
            type="primary"
            size="large"
            native-type="submit"
            :loading="smsLoading"
            :disabled="smsLoading"
          >
            {{ smsLoading ? '正在验证…' : '使用短信验证码登录' }}
            <AppIcon
              v-if="!smsLoading"
              name="arrow-right"
            />
          </el-button>
        </el-form>

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
          <span>© {{ year }} Snail Flow</span>
          <span class="mono">WORKFLOW PLATFORM</span>
        </div>
      </div>
    </section>

    <!-- 多租户账号选择：短信登录多命中时出现，pickKey 2 分钟一次性 -->
    <el-dialog
      :model-value="smsPick !== null"
      width="392px"
      align-center
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
    </el-dialog>
  </main>
</template>

<style scoped lang="scss">
.lp-line {
  fill: none;
  stroke: #3e5d61;
  stroke-width: 1.3;
}

.lp-line.hot {
  stroke: var(--teal);
  stroke-dasharray: 5 5;
  animation: dash-flow 16s linear infinite;
}

.login-field {
  margin-bottom: 0;

  :deep(.el-form-item__label) {
    height: auto;
    padding: 0 0 6px;
    line-height: 1.4;
  }

  :deep(.el-form-item__content) {
    line-height: 1.4;
  }

  :deep(.el-input__wrapper) {
    padding: 0 12px;
    border-radius: 9px;
  }

  :deep(.el-input__inner) {
    height: 40px;
    font-size: 12.5px;
  }

  :deep(.el-input__prefix) {
    margin-right: 8px;
  }

  :deep(.el-input__prefix .ic) {
    width: 15px;
    height: 15px;
  }

  :deep(.el-form-item__error) {
    padding-top: 3px;
    font-size: 10.5px;
  }
}

.captcha-row {
  display: flex;
  width: 100%;
  gap: 10px;

  .el-input {
    min-width: 0;
    flex: 1;
  }
}

.captcha-box {
  display: grid;
  width: 104px;
  height: 42px;
  flex: none;
  place-items: center;
  overflow: hidden;
  padding: 0;
  border: 1px solid var(--line-strong);
  border-radius: 9px;
  background: var(--panel-solid);
  cursor: pointer;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.captcha-loading {
  font-size: 10.5px;
  color: var(--text-3);
}

/* ------------------------- 短信登录 ------------------------- */

.login-back {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  margin: 0 0 12px;
  padding: 0;
  border: none;
  background: none;
  color: var(--text-3);
  font-size: 11.5px;
  cursor: pointer;

  &:hover {
    color: var(--teal);
  }
}

.sms-code-row {
  display: flex;
  gap: 10px;
  width: 100%;

  .el-input {
    min-width: 0;
    flex: 1;
  }
}

.send-btn {
  width: 118px;
  flex: none;
  border-radius: 9px;
  font-size: 12.5px;
}

.login-hint-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 2px 0 14px;
  color: var(--text-3);
  font-size: 10.5px;
}

/* ------------------------- 其他登录方式 ------------------------- */

.login-divider {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 18px 0 12px;
  color: var(--text-3);
  font-size: 10px;
  letter-spacing: 0.16em;

  &::before,
  &::after {
    content: '';
    flex: 1;
    height: 1px;
    background: var(--line);
  }
}

.login-methods {
  display: flex;
  gap: 8px;
}

.login-method {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 36px;
  border: 1px solid var(--line-strong);
  border-radius: 9px;
  background: transparent;
  color: var(--text-3);
  font-size: 12px;
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s, background 0.15s;

  &:hover {
    border-color: var(--teal);
    color: var(--text-1);
    background: var(--panel-soft);
  }

  &.active {
    border-color: var(--teal);
    color: var(--teal);
    background: rgba(30, 201, 165, 0.08);
  }
}

/* ------------------------- 租户账号选择对话框 ------------------------- */

.pick-head {
  display: flex;
  flex-direction: column;
  gap: 3px;

  span {
    color: var(--text-3);
    font-size: 12px;
    font-weight: normal;
  }
}

.account-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.account-item {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--line-strong);
  border-radius: 10px;
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;

  &:hover:not(:disabled) {
    border-color: var(--teal);
    background: var(--panel-soft);
  }

  &:disabled {
    opacity: 0.6;
    cursor: wait;
  }
}

.account-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  flex: none;
  border-radius: 50%;
  color: #fff;
  background: linear-gradient(140deg, var(--teal), #2dd4bf);
  font-size: 14px;
  font-weight: 650;
}

.account-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;

  b {
    color: var(--text-1);
    font-size: 13.5px;
  }

  i {
    overflow: hidden;
    color: var(--text-3);
    font-size: 11.5px;
    font-style: normal;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.account-picking {
  color: var(--teal);
  font-size: 11.5px;
}

.pick-hint {
  color: var(--text-3);
  font-size: 11px;
}

.login-submit {
  width: 100%;
  margin-top: 5px;

  .ic {
    width: 15px;
    height: 15px;
  }
}

@media (max-width: 560px) {
  .captcha-box {
    width: 100%;
  }
}
</style>
