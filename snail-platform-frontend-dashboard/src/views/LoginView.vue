<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  fetchCaptcha,
  isSmsAccountsResult,
  login as loginRequest,
  loginBySms,
  pickSmsAccount,
  sendSmsCaptcha,
  type Captcha,
  type SmsAccountOption,
  type SmsAccountsResult,
} from '@/api/auth';
import { ApiError } from '@/api/http';
import { applyLoginResult } from '@/stores/auth';

/** 记住的是账号名而非登录态:刷新后只回填登录名,令牌仍由 stores/auth 持有。 */
const REMEMBER_KEY = 'snail_dashboard_login_account_v1';

/** 发送短信验证码的间隔秒数(与后端 60s 间隔闸对齐)。 */
const SMS_RESEND_SECONDS = 60;

type CaptchaState = 'loading' | 'ready' | 'failed';
type Stage = 'password' | 'sms' | 'pick';

const route = useRoute();
const router = useRouter();

/** 面板内三态:账号密码表单 / 短信验证码表单 / 多账号候选(pickKey 挂起时)。 */
const stage = ref<Stage>('password');

const loading = ref(false);
const captcha = ref<Captcha | null>(null);
const captchaState = ref<CaptchaState>('loading');

/* ------------------------- 内联提示(替代弹窗,大屏保持零依赖) ------------------------- */

type NoticeKind = 'error' | 'success' | 'info';
const notice = ref<{ kind: NoticeKind; text: string } | null>(null);

function setNotice(kind: NoticeKind, text: string): void {
  notice.value = { kind, text };
}

/* 守卫回传:大屏顶栏 /me 开通闸未通过时带 denied 参数回登录页,这里落一条内联提示 */
if (route.query.denied === 'dashboard') {
  notice.value = { kind: 'info', text: '所在租户未开通数据大屏,请联系平台管理员开通' };
}

const statusLabel = computed(() => {
  if (captchaState.value === 'ready') return '系统运行正常';
  return captchaState.value === 'failed' ? '认证服务不可用' : '认证服务连接中';
});

/* ------------------------- 账号密码登录 ------------------------- */

const remembered = readRememberedAccount();
const form = ref({ username: remembered, password: '', captchaCode: '' });
const remember = ref(Boolean(remembered));

/* ------------------------- 短信验证码登录 ------------------------- */

const smsForm = ref({ phone: '', captchaCode: '', smsCode: '' });
const sending = ref(false);
const smsLoading = ref(false);
/** 倒计时秒数,0 即可再次发送;计时器在组件卸载时清理。 */
const countdown = ref(0);
let countdownTimer: number | undefined;

function stopCountdown(): void {
  if (countdownTimer !== undefined) {
    window.clearInterval(countdownTimer);
    countdownTimer = undefined;
  }
}

function startCountdown(): void {
  countdown.value = SMS_RESEND_SECONDS;
  countdownTimer = window.setInterval(() => {
    countdown.value -= 1;
    if (countdown.value <= 0) stopCountdown();
  }, 1000);
}

onUnmounted(stopCountdown);

const sendLabel = computed(() =>
  countdown.value > 0 ? `${countdown.value}s 后重发` : '获取验证码',
);

/* ------------------------- 图形验证码:一次一用,失败/登录失败都要换码 ------------------------- */

async function refreshCaptcha(): Promise<void> {
  captchaState.value = 'loading';
  try {
    captcha.value = await fetchCaptcha();
    captchaState.value = 'ready';
  } catch {
    captcha.value = null;
    captchaState.value = 'failed';
  } finally {
    form.value.captchaCode = '';
    smsForm.value.captchaCode = '';
  }
}

/* ------------------------- 提交与跳转 ------------------------- */

/** 只接受站内路径,避免 redirect 参数被拼成外部跳转。 */
function redirectTarget(): string {
  const raw = typeof route.query.redirect === 'string' ? route.query.redirect : '/';
  return raw.startsWith('/') && !raw.startsWith('//') ? raw : '/';
}

function readRememberedAccount(): string {
  try {
    return localStorage.getItem(REMEMBER_KEY) ?? '';
  } catch {
    return '';
  }
}

function writeRememberedAccount(username: string): void {
  try {
    if (username) localStorage.setItem(REMEMBER_KEY, username);
    else localStorage.removeItem(REMEMBER_KEY);
  } catch {
    /* 隐私模式写入失败时仅本次会话生效 */
  }
}

async function submitPassword(): Promise<void> {
  const username = form.value.username.trim();
  const captchaCode = form.value.captchaCode.trim();
  if (!username) return setNotice('error', '请输入账号');
  if (!form.value.password) return setNotice('error', '请输入密码');
  if (!captcha.value) return setNotice('error', '验证码未就绪，请稍候或点击刷新');
  if (captchaCode.length !== 4) return setNotice('error', '请输入右侧 4 位图形验证码');

  loading.value = true;
  try {
    const result = await loginRequest({
      username,
      password: form.value.password,
      captchaKey: captcha.value.captchaKey,
      captchaCode,
      scene: 'dashboard',
    });
    writeRememberedAccount(remember.value ? username : '');
    await enter(result.token);
  } catch (error) {
    // 文案已带后端原因(密码错/锁定等);验证码一次一用,失败即换新
    setNotice('error', error instanceof ApiError ? error.message : '登录失败，请稍后重试');
    await refreshCaptcha();
  } finally {
    loading.value = false;
  }
}

/* ------------------------- 短信验证码发送与提交 ------------------------- */

async function sendSmsCode(): Promise<void> {
  if (!captcha.value || sending.value || countdown.value > 0) return;
  const phone = smsForm.value.phone.trim();
  const captchaCode = smsForm.value.captchaCode.trim();
  if (!/^1\d{10}$/.test(phone)) return setNotice('error', '请输入正确的 11 位手机号');
  if (captchaCode.length !== 4) return setNotice('error', '请输入右侧 4 位图形验证码');

  sending.value = true;
  try {
    await sendSmsCaptcha({ phone, captchaKey: captcha.value.captchaKey, captchaCode });
    startCountdown();
    setNotice('success', '验证码已发送，请查收短信');
    // 旧图形码随人机闸核销,短信码 5 分钟内有效
    await refreshCaptcha();
  } catch (error) {
    setNotice('error', error instanceof ApiError ? error.message : '发送失败，请稍后重试');
    await refreshCaptcha();
  } finally {
    sending.value = false;
  }
}

/** 多账号命中态:pickKey 2 分钟一次性,非空即切换到候选列表。 */
const smsPick = ref<SmsAccountsResult | null>(null);
const picking = ref<number | null>(null);

async function submitSms(): Promise<void> {
  const phone = smsForm.value.phone.trim();
  const smsCode = smsForm.value.smsCode.trim();
  if (!/^1\d{10}$/.test(phone)) return setNotice('error', '请输入正确的 11 位手机号');
  if (smsCode.length !== 6) return setNotice('error', '请输入 6 位短信验证码');

  smsLoading.value = true;
  try {
    const result = await loginBySms({ phone, smsCode, scene: 'dashboard' });
    if (isSmsAccountsResult(result)) {
      smsPick.value = result;
      stage.value = 'pick';
      notice.value = null;
    } else {
      await enter(result.token);
    }
  } catch (error) {
    // 错码/锁定等文案已带后端原因;短信码 5 分钟内可重试,输入保留
    setNotice('error', error instanceof ApiError ? error.message : '登录失败，请稍后重试');
  } finally {
    smsLoading.value = false;
  }
}

/** 选定租户账号换令牌:pickKey 一次一用,失败即作废,须重走短信登录。 */
async function pickAccount(account: SmsAccountOption): Promise<void> {
  if (!smsPick.value || picking.value !== null) return;
  picking.value = account.userId;
  try {
    const result = await pickSmsAccount({ pickKey: smsPick.value.pickKey, userId: account.userId, scene: 'dashboard' });
    smsPick.value = null;
    await enter(result.token);
  } catch (error) {
    smsPick.value = null;
    smsForm.value.smsCode = '';
    stage.value = 'sms';
    setNotice('error', error instanceof ApiError ? error.message : '选择失败，请重新走短信登录');
  } finally {
    picking.value = null;
  }
}

/** 放弃选择:短信码已随首调核销,清码让用户重新发送。 */
function backToSms(): void {
  smsPick.value = null;
  smsForm.value.smsCode = '';
  stage.value = 'sms';
  notice.value = null;
}

/* ------------------------- 登录成功的统一收口 ------------------------- */

/** 落令牌后进 redirect;大屏无改密页,passwordChangeRequired 不在此消费。 */
async function enter(token: string): Promise<void> {
  applyLoginResult(token);
  await router.replace(redirectTarget());
}

/* ------------------------- 登录方式切换与占位 ------------------------- */

function switchStage(next: Exclude<Stage, 'pick'>): void {
  if (stage.value === next) return;
  stage.value = next;
  notice.value = null;
  // 切表单换一张新图形码,避免上个表单里已提交过的码被复用
  void refreshCaptcha();
}

/** 钉钉/飞书/企业微信登录尚未开通:后端 login_type 已留编码位,接入时换跳转即可。 */
function externalLogin(name: string): void {
  setNotice('info', `${name}登录暂未开通，请使用账号密码或手机验证码登录`);
}

onMounted(() => void refreshCaptcha());
</script>

<template>
  <div class="scr-login">
    <!-- 左:品牌区(静态展示,与 docs/login-example.html 同源) -->
    <aside class="scr-brand">
      <div class="scr-brandrow">
        <span class="scr-mark">
          <svg
            width="24"
            height="24"
            viewBox="0 0 24 24"
            fill="none"
            aria-hidden="true"
          >
            <circle
              cx="12"
              cy="12"
              r="8.2"
              stroke="currentColor"
              stroke-width="1.6"
            />
            <ellipse
              cx="12"
              cy="12"
              rx="8.2"
              ry="3.4"
              stroke="currentColor"
              stroke-width="1.3"
            />
            <path
              d="M12 3.8v16.4"
              stroke="currentColor"
              stroke-width="1.3"
            />
            <circle
              cx="16.4"
              cy="8.2"
              r="1.5"
              fill="currentColor"
            />
          </svg>
        </span>
        <div>
          <div class="scr-name">
            Snail<em>.</em>Screen
          </div>
          <div class="scr-sub">
            DATA SITUATION SCREEN
          </div>
        </div>
      </div>

      <div class="scr-copy">
        <div class="scr-kicker">
          <i></i>三维态势可视化
        </div>
        <h1>让每一寸态势<br />都<em>尽收眼底</em>。</h1>
        <p>三维地球、实时数据流与多源图层在同一块大屏上汇聚；登录后按授权进入对应态势主题，跳转管理端与流程平台全程免登。</p>

        <div class="scr-visual">
          <div class="scr-visual-head">
            <span>LIVE SITUATION MAP · 全域态势</span>
            <span class="live">ONLINE</span>
          </div>
          <div class="radar-wrap">
            <div class="radar-sweep"></div>
            <svg
              width="100%"
              height="150"
              viewBox="0 0 560 150"
              preserveAspectRatio="xMidYMid meet"
              aria-label="雷达态势示意图"
            >
              <g
                stroke="rgba(30,201,165,0.25)"
                fill="none"
              >
                <circle
                  cx="280"
                  cy="75"
                  r="30"
                />
                <circle
                  cx="280"
                  cy="75"
                  r="56"
                />
                <circle
                  cx="280"
                  cy="75"
                  r="82"
                />
                <path
                  d="M168 75h224M280 0v150"
                  stroke-dasharray="4 6"
                />
              </g>
              <g fill="#1ec9a5">
                <circle
                  cx="322"
                  cy="48"
                  r="3.5"
                />
                <circle
                  cx="236"
                  cy="96"
                  r="3"
                />
                <circle
                  cx="345"
                  cy="102"
                  r="2.5"
                />
              </g>
              <circle
                cx="256"
                cy="40"
                r="3"
                fill="#e8ad55"
              />
            </svg>
          </div>
        </div>
      </div>

      <div
        class="scr-stats"
        style="display:flex;gap:42px;max-width:540px;padding-top:20px;border-top:1px solid rgba(214,255,246,0.1)"
      >
        <div><b style="display:block;font-size:21px;letter-spacing:-0.03em">三维地球</b><span style="display:block;margin-top:5px;color:rgba(190,231,222,0.44);font-size:10px">天地图影像底图</span></div>
        <div><b style="display:block;font-size:21px;letter-spacing:-0.03em">实时推送</b><span style="display:block;margin-top:5px;color:rgba(190,231,222,0.44);font-size:10px">数据流秒级刷新</span></div>
        <div><b style="display:block;font-size:21px;letter-spacing:-0.03em">多源图层</b><span style="display:block;margin-top:5px;color:rgba(190,231,222,0.44);font-size:10px">授权主题按需叠加</span></div>
      </div>
    </aside>

    <!-- 右:登录面板 -->
    <section class="scr-panel-wrap">
      <div class="scr-panel">
        <div class="scr-panel-top">
          <span>SECURE ACCESS</span>
          <span
            class="online"
            :class="{ warning: captchaState === 'failed' }"
          ><i></i>{{ statusLabel }}</span>
        </div>

        <h2>欢迎回来</h2>
        <p class="scr-lead">
          登录 <b>Snail Screen</b>，进入三维态势数据大屏。
        </p>

        <div
          v-if="notice"
          class="scr-alert"
          :class="notice.kind"
          role="alert"
        >
          {{ notice.text }}
        </div>

        <!-- 多账号候选:短信登录命中多个租户账号时选定其一 -->
        <template v-if="stage === 'pick'">
          <button
            type="button"
            class="scr-back"
            @click="backToSms"
          >
            ‹ 返回短信验证码登录
          </button>
          <p class="scr-pick-tip">
            该手机号命中多个租户账号，请选择要进入的账号：
          </p>
          <div class="scr-pick-list">
            <button
              v-for="account in smsPick?.accounts ?? []"
              :key="`${account.tenantId}-${account.userId}`"
              type="button"
              class="scr-pick-item"
              :disabled="picking !== null"
              @click="pickAccount(account)"
            >
              <b>{{ account.tenantName }}</b>
              <span>{{ picking === account.userId ? '换发令牌中…' : `${account.username} · ${account.nickname}` }}</span>
            </button>
          </div>
        </template>

        <!-- 账号密码登录 -->
        <form
          v-else-if="stage === 'password'"
          autocomplete="off"
          @submit.prevent="submitPassword"
        >
          <div class="scr-field">
            <label>账号</label>
            <div class="scr-input">
              <svg
                width="16"
                height="16"
                viewBox="0 0 24 24"
                fill="none"
              ><circle
                cx="12"
                cy="8"
                r="3.4"
                stroke="currentColor"
                stroke-width="1.7"
              /><path
                d="M5 19.4c1.6-2.4 4-3.6 7-3.6s5.4 1.2 7 3.6"
                stroke="currentColor"
                stroke-width="1.7"
                stroke-linecap="round"
              /></svg>
              <input
                v-model="form.username"
                type="text"
                placeholder="请输入账号"
              />
            </div>
          </div>
          <div class="scr-field">
            <label>密码</label>
            <div class="scr-input">
              <svg
                width="16"
                height="16"
                viewBox="0 0 24 24"
                fill="none"
              ><rect
                x="5"
                y="10.5"
                width="14"
                height="9"
                rx="2.4"
                stroke="currentColor"
                stroke-width="1.7"
              /><path
                d="M8.4 10.5V8a3.6 3.6 0 0 1 7.2 0v2.5"
                stroke="currentColor"
                stroke-width="1.7"
              /></svg>
              <input
                v-model="form.password"
                type="password"
                placeholder="请输入密码"
              />
            </div>
          </div>
          <div class="scr-field">
            <label>验证码</label>
            <div class="scr-captcha-row">
              <div class="scr-input">
                <svg
                  width="16"
                  height="16"
                  viewBox="0 0 24 24"
                  fill="none"
                ><path
                  d="M12 3.6 18.6 6v5.4c0 4.2-2.8 7.3-6.6 9-3.8-1.7-6.6-4.8-6.6-9V6L12 3.6Z"
                  stroke="currentColor"
                  stroke-width="1.7"
                  stroke-linejoin="round"
                /><path
                  d="m9.4 12.2 1.9 1.9 3.5-3.7"
                  stroke="currentColor"
                  stroke-width="1.7"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                /></svg>
                <input
                  v-model="form.captchaCode"
                  type="text"
                  maxlength="4"
                  placeholder="输入右侧 4 位验证码"
                />
              </div>
              <button
                type="button"
                class="scr-captcha-box"
                title="点击刷新验证码"
                @click="refreshCaptcha"
              >
                <img
                  v-if="captcha"
                  :src="captcha.image"
                  alt="图形验证码"
                />
                <span v-else>····</span>
              </button>
            </div>
            <div style="margin-top:7px;color:rgba(190,231,222,0.44);font-size:11px">
              看不清？<button
                type="button"
                style="border:none;background:none;color:#7be3cd;font-size:11px;cursor:pointer;padding:0"
                @click="refreshCaptcha"
              >
                换一张
              </button>
            </div>
          </div>
          <div class="scr-options">
            <label><input
              v-model="remember"
              type="checkbox"
              style="accent-color:#1ec9a5"
            />记住账号</label>
            <span>SSL 加密传输</span>
          </div>
          <button
            type="submit"
            class="scr-submit"
            :disabled="loading"
          >
            {{ loading ? '登录中…' : '进入数据大屏' }}
            <svg
              v-if="!loading"
              width="15"
              height="15"
              viewBox="0 0 24 24"
              fill="none"
            ><path
              d="M4 12h15m0 0-5.4-5.4M19 12l-5.4 5.4"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
            /></svg>
          </button>
        </form>

        <!-- 手机验证码登录 -->
        <form
          v-else
          autocomplete="off"
          @submit.prevent="submitSms"
        >
          <button
            type="button"
            class="scr-back"
            @click="switchStage('password')"
          >
            ‹ 返回账号密码登录
          </button>
          <div class="scr-field">
            <label>手机号</label>
            <div class="scr-input">
              <svg
                width="16"
                height="16"
                viewBox="0 0 24 24"
                fill="none"
              ><rect
                x="7"
                y="3.6"
                width="10"
                height="16.8"
                rx="2.4"
                stroke="currentColor"
                stroke-width="1.7"
              /><path
                d="M10.7 17.6h2.6"
                stroke="currentColor"
                stroke-width="1.7"
                stroke-linecap="round"
              /></svg>
              <input
                v-model="smsForm.phone"
                type="tel"
                maxlength="11"
                placeholder="请输入 11 位手机号"
              />
            </div>
          </div>
          <div class="scr-field">
            <label>图形验证码</label>
            <div class="scr-captcha-row">
              <div class="scr-input">
                <svg
                  width="16"
                  height="16"
                  viewBox="0 0 24 24"
                  fill="none"
                ><path
                  d="M12 3.6 18.6 6v5.4c0 4.2-2.8 7.3-6.6 9-3.8-1.7-6.6-4.8-6.6-9V6L12 3.6Z"
                  stroke="currentColor"
                  stroke-width="1.7"
                  stroke-linejoin="round"
                /></svg>
                <input
                  v-model="smsForm.captchaCode"
                  type="text"
                  maxlength="4"
                  placeholder="输入右侧 4 位验证码"
                />
              </div>
              <button
                type="button"
                class="scr-captcha-box"
                title="点击刷新验证码"
                @click="refreshCaptcha"
              >
                <img
                  v-if="captcha"
                  :src="captcha.image"
                  alt="图形验证码"
                />
                <span v-else>····</span>
              </button>
            </div>
          </div>
          <div class="scr-field">
            <label>短信验证码</label>
            <div class="scr-sms-row">
              <div class="scr-input">
                <svg
                  width="16"
                  height="16"
                  viewBox="0 0 24 24"
                  fill="none"
                ><path
                  d="M6 10a6 6 0 1 1 12 0c0 4 1.4 5.4 2 6H4c.6-.6 2-2 2-6Z"
                  stroke="currentColor"
                  stroke-width="1.7"
                  stroke-linejoin="round"
                /><path
                  d="M10 19.4a2.2 2.2 0 0 0 4 0"
                  stroke="currentColor"
                  stroke-width="1.7"
                /></svg>
                <input
                  v-model="smsForm.smsCode"
                  type="text"
                  maxlength="6"
                  placeholder="6 位短信验证码"
                />
              </div>
              <button
                type="button"
                class="scr-send"
                :disabled="sending || countdown > 0"
                @click="sendSmsCode"
              >
                {{ sending ? '发送中…' : sendLabel }}
              </button>
            </div>
          </div>
          <div class="scr-options">
            <span>验证码 5 分钟内有效</span>
            <span>SSL 加密传输</span>
          </div>
          <button
            type="submit"
            class="scr-submit"
            :disabled="smsLoading"
          >
            {{ smsLoading ? '登录中…' : '使用短信验证码登录' }}
            <svg
              v-if="!smsLoading"
              width="15"
              height="15"
              viewBox="0 0 24 24"
              fill="none"
            ><path
              d="M4 12h15m0 0-5.4-5.4M19 12l-5.4 5.4"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
            /></svg>
          </button>
        </form>

        <template v-if="stage !== 'pick'">
          <div class="scr-divider">
            <span>其他登录方式</span>
          </div>
          <div class="scr-methods">
            <button
              type="button"
              class="scr-method"
              :class="{ active: stage === 'sms' }"
              @click="switchStage('sms')"
            >
              手机登录
            </button>
            <button
              type="button"
              class="scr-method"
              @click="externalLogin('钉钉')"
            >
              钉钉
            </button>
            <button
              type="button"
              class="scr-method"
              @click="externalLogin('飞书')"
            >
              飞书
            </button>
            <button
              type="button"
              class="scr-method"
              @click="externalLogin('企业微信')"
            >
              企业微信
            </button>
          </div>

          <div class="scr-sso">
            <svg
              width="14"
              height="14"
              viewBox="0 0 24 24"
              fill="none"
              style="flex:none"
            ><circle
              cx="12"
              cy="12"
              r="8.6"
              stroke="currentColor"
              stroke-width="1.7"
            /><path
              d="M12 8v5"
              stroke="currentColor"
              stroke-width="1.7"
              stroke-linecap="round"
            /><circle
              cx="12"
              cy="16"
              r="1"
              fill="currentColor"
            /></svg>
            <span>从管理端或流程平台跳转时将自动携带一次性 ssotoken 免登进入大屏，无需再次输入密码。</span>
          </div>
        </template>

        <div class="scr-foot">
          <span>© 2026 Snail Platform</span>
          <span class="mono">DATA SCREEN</span>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.scr-login {
  --teal: #1ec9a5;
  --teal-strong: #0f9f82;
  --teal-tint: #7be3cd;
  --amber: #e8ad55;
  --bg-0: #050f14;
  --bg-1: #081a22;
  --panel: #0c222c;
  --panel-soft: #0e2833;
  --line: rgba(126, 214, 195, 0.14);
  --line-strong: rgba(126, 214, 195, 0.28);
  --text-1: #e6f7f2;
  --text-2: rgba(214, 247, 240, 0.66);
  --text-3: rgba(190, 231, 222, 0.44);
  --mono: 'JetBrains Mono', 'SFMono-Regular', Consolas, monospace;

  display: grid;
  grid-template-columns: minmax(0, 1.08fr) minmax(440px, 0.92fr);
  min-height: 100vh;
  background: var(--bg-0);
  color: var(--text-1);
  font-family: 'Helvetica Neue', Helvetica, 'PingFang SC', 'Microsoft YaHei', Arial, sans-serif;
}

/* ------------------------- 左:品牌区 ------------------------- */

.scr-brand {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 26px;
  padding: 48px 58px;
  overflow: hidden;
  background:
    radial-gradient(900px 540px at 12% 10%, rgba(30, 201, 165, 0.16), transparent 62%),
    radial-gradient(760px 520px at 96% 96%, rgba(232, 173, 85, 0.08), transparent 58%),
    linear-gradient(150deg, #071820, var(--bg-0) 58%, #0a2831);
}

.scr-brand::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(126, 231, 210, 0.05) 1px, transparent 1px),
    linear-gradient(90deg, rgba(126, 231, 210, 0.05) 1px, transparent 1px);
  background-size: 56px 56px;
  -webkit-mask-image: radial-gradient(circle at 28% 30%, #000, transparent 74%);
  mask-image: radial-gradient(circle at 28% 30%, #000, transparent 74%);
}

.scr-brand::after {
  content: '';
  position: absolute;
  right: -220px;
  top: -200px;
  width: 520px;
  height: 520px;
  border: 1px solid rgba(30, 201, 165, 0.18);
  border-radius: 50%;
  box-shadow:
    0 0 0 74px rgba(30, 201, 165, 0.05),
    0 0 0 148px rgba(30, 201, 165, 0.03);
}

.scr-brand > * {
  position: relative;
  z-index: 2;
}

.scr-brandrow {
  display: flex;
  align-items: center;
  gap: 13px;
}

.scr-mark {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border-radius: 13px;
  color: #04231c;
  background: linear-gradient(145deg, var(--teal-tint), var(--teal));
  box-shadow: 0 8px 26px rgba(30, 201, 165, 0.32);
}

.scr-name {
  font-size: 19px;
  font-weight: 700;
  letter-spacing: -0.02em;
}

.scr-name em {
  font-style: normal;
  color: var(--teal);
}

.scr-sub {
  margin-top: 3px;
  color: var(--text-3);
  font-family: var(--mono);
  font-size: 10px;
  letter-spacing: 0.22em;
}

.scr-kicker {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  width: fit-content;
  padding: 7px 13px;
  border: 1px solid rgba(30, 201, 165, 0.3);
  border-radius: 99px;
  background: rgba(30, 201, 165, 0.08);
  color: var(--teal-tint);
  font-family: var(--mono);
  font-size: 10.5px;
  letter-spacing: 0.14em;
}

.scr-kicker i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--teal);
  box-shadow: 0 0 10px var(--teal);
}

.scr-copy h1 {
  margin: 18px 0 14px;
  font-size: clamp(36px, 3.8vw, 52px);
  line-height: 1.16;
  letter-spacing: -0.045em;
}

.scr-copy h1 em {
  font-style: normal;
  color: var(--teal-tint);
}

.scr-copy p {
  max-width: 470px;
  margin: 0;
  color: var(--text-2);
  font-size: 14px;
  line-height: 1.85;
}

/* 态势示意卡(雷达扫掠动效) */

.scr-visual {
  max-width: 560px;
  padding: 14px 16px 16px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: rgba(12, 34, 44, 0.72);
  backdrop-filter: blur(6px);
}

.scr-visual-head {
  display: flex;
  justify-content: space-between;
  margin-bottom: 12px;
  color: var(--text-3);
  font-family: var(--mono);
  font-size: 10px;
  letter-spacing: 0.12em;
}

.scr-visual-head .live {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--teal);
}

.scr-visual-head .live::before {
  content: '';
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
  box-shadow: 0 0 8px currentColor;
  animation: scr-pulse 1.8s ease-in-out infinite;
}

@keyframes scr-pulse {
  50% { opacity: 0.35; }
}

.radar-wrap {
  position: relative;
  height: 150px;
  overflow: hidden;
  border-radius: 10px;
  background: var(--bg-0);
}

.radar-sweep {
  position: absolute;
  inset: -50%;
  background: conic-gradient(from 0deg, rgba(30, 201, 165, 0.28), transparent 18%);
  animation: scr-spin 5.5s linear infinite;
}

@keyframes scr-spin {
  to { transform: rotate(360deg); }
}

/* ------------------------- 右:登录面板 ------------------------- */

.scr-panel-wrap {
  display: grid;
  place-items: center;
  padding: 44px 40px;
  background: var(--bg-1);
}

.scr-panel {
  width: 100%;
  max-width: 412px;
  padding: 34px 36px 30px;
  border: 1px solid var(--line);
  border-radius: 18px;
  background: var(--panel);
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.45);
}

.scr-panel-top {
  display: flex;
  justify-content: space-between;
  margin-bottom: 24px;
  color: var(--text-3);
  font-family: var(--mono);
  font-size: 9.5px;
  letter-spacing: 0.12em;
}

.scr-panel-top .online {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--teal);
  letter-spacing: 0;
  font-family: inherit;
}

.scr-panel-top .online i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
  box-shadow: 0 0 7px currentColor;
}

.scr-panel-top .online.warning { color: var(--amber); }

.scr-panel h2 {
  margin: 0;
  font-size: 28px;
  letter-spacing: -0.03em;
}

.scr-lead {
  margin: 8px 0 22px;
  color: var(--text-2);
  font-size: 13px;
}

.scr-lead b { color: var(--teal-tint); font-weight: 600; }

/* 内联提示:校验与接口错误/成功回执 */

.scr-alert {
  margin: -6px 0 16px;
  padding: 9px 12px;
  border: 1px solid rgba(255, 122, 105, 0.35);
  border-radius: 9px;
  background: rgba(255, 122, 105, 0.08);
  color: #ff9d8f;
  font-size: 12px;
  line-height: 1.6;
}

.scr-alert.success {
  border-color: rgba(30, 201, 165, 0.35);
  background: rgba(30, 201, 165, 0.08);
  color: var(--teal-tint);
}

.scr-alert.info {
  border-color: rgba(232, 173, 85, 0.35);
  background: rgba(232, 173, 85, 0.06);
  color: rgba(232, 173, 85, 0.9);
}

.scr-field { margin-bottom: 16px; }

.scr-field label {
  display: block;
  margin-bottom: 7px;
  color: var(--text-2);
  font-size: 12px;
}

.scr-input {
  display: flex;
  align-items: center;
  gap: 9px;
  height: 46px;
  padding: 0 13px;
  border: 1px solid var(--line-strong);
  border-radius: 10px;
  background: var(--bg-1);
  transition: border-color 0.15s, background 0.15s;
}

.scr-input:focus-within {
  border-color: var(--teal);
  background: var(--panel-soft);
  box-shadow: 0 0 0 3px rgba(30, 201, 165, 0.12);
}

.scr-input svg { flex: none; color: var(--text-3); }

.scr-input input {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  background: none;
  color: var(--text-1);
  font-size: 14px;
}

.scr-input input::placeholder { color: var(--text-3); }

.scr-captcha-row { display: flex; gap: 10px; }

.scr-captcha-row .scr-input { flex: 1; }

.scr-captcha-box {
  display: grid;
  place-items: center;
  width: 104px;
  height: 46px;
  flex: none;
  overflow: hidden;
  padding: 0;
  border: 1px solid var(--line-strong);
  border-radius: 10px;
  background: var(--panel-soft);
  cursor: pointer;
}

.scr-captcha-box img {
  width: 100%;
  height: 100%;
  object-fit: fill;
  user-select: none;
}

.scr-captcha-box span {
  color: var(--text-3);
  font-family: var(--mono);
  font-size: 15px;
  font-style: italic;
  letter-spacing: 0.3em;
  user-select: none;
}

.scr-sms-row { display: flex; gap: 10px; }

.scr-sms-row .scr-input { flex: 1; }

.scr-send {
  width: 118px;
  flex: none;
  height: 46px;
  border: 1px solid var(--line-strong);
  border-radius: 10px;
  background: transparent;
  color: var(--teal-tint);
  font-size: 12.5px;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}

.scr-send:hover:not(:disabled) { border-color: var(--teal); background: rgba(30, 201, 165, 0.08); }
.scr-send:disabled { color: var(--text-3); cursor: not-allowed; }

.scr-options {
  display: flex;
  justify-content: space-between;
  margin: 2px 0 20px;
  color: var(--text-3);
  font-size: 10.5px;
}

.scr-options label { display: inline-flex; align-items: center; gap: 6px; cursor: pointer; }

.scr-submit {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  height: 50px;
  border: none;
  border-radius: 12px;
  background: linear-gradient(135deg, var(--teal-strong), var(--teal));
  color: #03211a;
  font-size: 15px;
  font-weight: 700;
  cursor: pointer;
  box-shadow: 0 10px 26px rgba(30, 201, 165, 0.28);
  transition: filter 0.15s;
}

.scr-submit:hover:not(:disabled) { filter: brightness(1.08); }
.scr-submit:disabled { cursor: wait; filter: saturate(0.6); }

.scr-back {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  margin: -6px 0 14px;
  padding: 0;
  border: none;
  background: none;
  color: var(--text-3);
  font-size: 11.5px;
  cursor: pointer;
}

.scr-back:hover { color: var(--teal); }

/* 多账号候选列表 */

.scr-pick-tip {
  margin: 0 0 12px;
  color: var(--text-2);
  font-size: 12.5px;
}

.scr-pick-list {
  display: grid;
  gap: 8px;
}

.scr-pick-item {
  display: grid;
  gap: 3px;
  padding: 12px 14px;
  border: 1px solid var(--line-strong);
  border-radius: 10px;
  background: var(--bg-1);
  color: var(--text-1);
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}

.scr-pick-item:hover:not(:disabled) {
  border-color: var(--teal);
  background: var(--panel-soft);
}

.scr-pick-item:disabled { color: var(--text-3); cursor: wait; }

.scr-pick-item b { font-size: 13.5px; }

.scr-pick-item span {
  color: var(--text-3);
  font-size: 11.5px;
  font-family: var(--mono);
}

.scr-divider {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 20px 0 12px;
  color: var(--text-3);
  font-family: var(--mono);
  font-size: 10px;
  letter-spacing: 0.16em;
}

.scr-divider::before,
.scr-divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: var(--line);
}

.scr-methods { display: flex; gap: 8px; }

.scr-method {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 38px;
  border: 1px solid var(--line-strong);
  border-radius: 10px;
  background: transparent;
  color: var(--text-3);
  font-size: 12px;
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s, background 0.15s;
}

.scr-method:hover { border-color: var(--teal); color: var(--text-1); background: var(--panel-soft); }
.scr-method.active { border-color: var(--teal); color: var(--teal); background: rgba(30, 201, 165, 0.08); }

.scr-sso {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 18px;
  padding: 10px 12px;
  border: 1px dashed rgba(232, 173, 85, 0.35);
  border-radius: 10px;
  background: rgba(232, 173, 85, 0.06);
  color: rgba(232, 173, 85, 0.85);
  font-size: 11px;
  line-height: 1.6;
}

.scr-foot {
  display: flex;
  justify-content: space-between;
  margin-top: 26px;
  padding-top: 16px;
  border-top: 1px solid var(--line);
  color: var(--text-3);
  font-size: 10px;
}

.scr-foot .mono { font-family: var(--mono); letter-spacing: 0.12em; }

@media (max-width: 1024px) {
  .scr-login { grid-template-columns: 1fr; }
  .scr-brand { display: none; }
}

@media (max-width: 760px) {
  .scr-panel-wrap { padding: 30px 16px; }
  .scr-panel { padding: 26px 20px 22px; }
}
</style>
