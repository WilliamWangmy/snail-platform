import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import {
  fetchCaptcha,
  fetchLoginUser,
  login as loginApi,
  loginBySms as loginBySmsApi,
  logout as logoutApi,
  pickSmsAccount as pickSmsAccountApi,
  sendSmsCaptcha as sendSmsCaptchaApi,
  type LoginUser,
  type SmsAccountsResult,
} from '@/api/auth';
import type { Captcha, LoginPayload, LoginResult } from '@/api/types';
import { clearToken, getToken, setToken } from '@/utils/token';

/** 会话快照:登录成功后由 LoginResult/LoginUser 提炼,供工作台展示。 */
export interface SessionUser {
  userId: number;
  username: string;
  nickname: string;
  tenantId: number;
  tenantName: string;
  passwordChangeRequired: boolean;
}

function toSession(result: LoginResult): SessionUser {
  return {
    userId: result.userId,
    username: result.username,
    nickname: result.nickname,
    tenantId: result.tenantId,
    tenantName: result.tenantName,
    passwordChangeRequired: result.passwordChangeRequired,
  };
}

function fromLoginUser(user: LoginUser): SessionUser {
  return {
    userId: user.userId,
    username: user.username,
    nickname: user.nickname,
    tenantId: user.tenantId,
    tenantName: user.tenantName,
    passwordChangeRequired: user.passwordChangeRequired,
  };
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(getToken());
  const user = ref<SessionUser | null>(null);
  const captcha = ref<Captcha | null>(null);

  const isLoggedIn = computed(() => token.value !== '');

  async function refreshCaptcha(): Promise<void> {
    captcha.value = await fetchCaptcha();
  }

  /** 登录成功(密码/短信/选号)统一落会话。 */
  function applySession(result: LoginResult): LoginResult {
    token.value = result.token;
    user.value = toSession(result);
    setToken(result.token);
    return result;
  }

  async function login(payload: Omit<LoginPayload, 'captchaKey'>): Promise<LoginResult> {
    if (!captcha.value) throw new Error('验证码未就绪');
    return applySession(await loginApi({ ...payload, captchaKey: captcha.value.captchaKey }));
  }

  async function logout(): Promise<void> {
    try {
      await logoutApi();
    } finally {
      token.value = '';
      user.value = null;
      clearToken();
    }
  }

  /* ------------------------- 短信登录 ------------------------- */

  /** 发送短信验证码(与后端 60s 间隔闸对齐,倒计时归页面管)。 */
  async function sendSmsCaptcha(phone: string, captchaKey: string, captchaCode: string): Promise<void> {
    await sendSmsCaptchaApi({ phone, captchaKey, captchaCode });
  }

  /** 短信验证码登录:出参两态,多账号(pickKey)需再调 pickSmsAccount。 */
  async function loginSms(payload: { phone: string; smsCode: string }): Promise<LoginResult | SmsAccountsResult> {
    return loginBySmsApi(payload);
  }

  /** 选定租户账号换正式令牌:pickKey 一次一用,成功即落会话。 */
  async function pickSmsAccount(pickKey: string, userId: number): Promise<LoginResult> {
    return applySession(await pickSmsAccountApi({ pickKey, userId }));
  }

  /* ------------------------- 会话 ------------------------- */

  /** 冷启动恢复:token 还有效时拉一次富快照,失败(过期)由请求层 401 统一回登录页。 */
  async function loadSession(): Promise<void> {
    if (!isLoggedIn.value || user.value) return;
    try {
      user.value = fromLoginUser(await fetchLoginUser());
    } catch {
      /* 快照失败不阻断进入,工作台显示"未登录"兜底 */
    }
  }

  return {
    token,
    user,
    captcha,
    isLoggedIn,
    refreshCaptcha,
    applySession,
    sendSmsCaptcha,
    loginSms,
    pickSmsAccount,
    login,
    logout,
    loadSession,
  };
});
