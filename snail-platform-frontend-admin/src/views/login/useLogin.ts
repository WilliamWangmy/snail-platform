import { computed, onMounted, onUnmounted, reactive, ref, type ShallowRef } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
import {
  fetchCaptcha,
  isSmsAccountsResult,
  loginBySms,
  pickSmsAccount,
  sendSmsCaptcha,
  type Captcha,
  type LoginResult,
  type SmsAccountOption,
  type SmsAccountsResult,
} from '@/api/auth';
import { useAuthStore } from '@/stores/auth';

/** 记住的是账号名而非登录态:刷新后只回填登录名,令牌仍由 auth store 持有。 */
const REMEMBER_KEY = 'snail_admin_login_account_v1';

type CaptchaState = 'loading' | 'ready' | 'failed';
type LoginMode = 'password' | 'sms';

/** 发送短信验证码的间隔秒数(与后端 60s 间隔闸对齐)。 */
const SMS_RESEND_SECONDS = 60;

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

/** 登录页状态机:密码/短信双模、验证码生命周期、表单校验、登录提交与跳转回填。
 *  表单 ref 由视图持有并注入(password/sms 两个)。 */
export function useLogin(
  formRef: Readonly<ShallowRef<FormInstance | null>>,
  smsFormRef: Readonly<ShallowRef<FormInstance | null>>,
) {
  const route = useRoute();
  const router = useRouter();
  const auth = useAuthStore();

  const loading = ref(false);
  const captcha = ref<Captcha | null>(null);
  const captchaState = ref<CaptchaState>('loading');
  /** 登录成功但待强制改密:不进业务页,由视图在登录页上弹强制改密窗 */
  const forcePasswordChange = ref(false);

  const mode = ref<LoginMode>('password');

  const remembered = readRememberedAccount();
  const form = reactive({ username: remembered, password: '', captchaCode: '' });
  const remember = ref(Boolean(remembered));

  const rules: FormRules = {
    username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
    password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
    captchaCode: [
      { required: true, message: '请输入验证码', trigger: 'blur' },
      { len: 4, message: '验证码为 4 位', trigger: 'blur' },
    ],
  };

  /* ------------------------- 短信验证码登录 ------------------------- */

  const smsForm = reactive({ phone: '', captchaCode: '', smsCode: '' });

  const smsRules: FormRules = {
    phone: [
      { required: true, message: '请输入手机号', trigger: 'blur' },
      { pattern: /^1\d{10}$/, message: '手机号格式不正确', trigger: 'blur' },
    ],
    captchaCode: [
      { required: true, message: '请输入图形验证码', trigger: 'blur' },
      { len: 4, message: '验证码为 4 位', trigger: 'blur' },
    ],
    smsCode: [
      { required: true, message: '请输入短信验证码', trigger: 'blur' },
      { len: 6, message: '短信验证码为 6 位', trigger: 'blur' },
    ],
  };

  /** 倒计时秒数,0 即可再次发送;计时器在组件卸载时清理。 */
  const countdown = ref(0);
  const sending = ref(false);
  const smsLoading = ref(false);
  /** 多账号命中态:非空即弹租户选择对话框(pickKey 2 分钟一次性)。 */
  const smsPick = ref<SmsAccountsResult | null>(null);
  /** 正在选定的账号 ID,用于对话框按钮 loading。 */
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

  const statusLabel = computed(() => {
    if (captchaState.value === 'ready') return '认证服务正常';
    return captchaState.value === 'failed' ? '认证服务不可用' : '认证服务连接中';
  });

  /** 验证码一次即失效:加载失败或登录失败都要换码,并清空两个表单已输入的 4 位。 */
  async function refreshCaptcha(): Promise<void> {
    captchaState.value = 'loading';
    try {
      captcha.value = await fetchCaptcha();
      captchaState.value = 'ready';
    } catch {
      captcha.value = null;
      captchaState.value = 'failed';
    } finally {
      form.captchaCode = '';
      smsForm.captchaCode = '';
    }
  }

  /** 切登录方式:换一张新图形码,避免上个表单里已提交过的码被复用。 */
  async function switchMode(next: LoginMode): Promise<void> {
    if (mode.value === next) return;
    mode.value = next;
    await refreshCaptcha();
  }

  /** 发送短信验证码:人机闸(图形码)在先,发送成功后旧图形码即作废。 */
  async function sendSmsCode(): Promise<void> {
    if (!captcha.value || countdown.value > 0) return;
    const valid = await smsFormRef.value?.validateField(['phone', 'captchaCode']).catch(() => false);
    if (!valid) return;
    sending.value = true;
    try {
      await sendSmsCaptcha({
        phone: smsForm.phone.trim(),
        captchaKey: captcha.value.captchaKey,
        captchaCode: smsForm.captchaCode.trim(),
      });
      startCountdown();
      ElMessage.success('验证码已发送,请查收短信');
      await refreshCaptcha();
    } catch {
      // 失败文案由 http 拦截器统一弹出(锁定/日额/账号不存在等);图形码可能已被消费,换新
      await refreshCaptcha();
    } finally {
      sending.value = false;
    }
  }

  /** 只接受站内路径,避免 redirect 参数被拼成外部跳转。 */
  function redirectTarget(): string {
    const raw = typeof route.query.redirect === 'string' ? route.query.redirect : '/';
    return raw.startsWith('/') && !raw.startsWith('//') ? raw : '/';
  }

  /** 登录成功的统一收口:落令牌,待改密留登录页弹窗,否则进 redirect。 */
  async function handleLoginResult(result: LoginResult): Promise<void> {
    auth.applyLoginResult(result);
    if (mode.value === 'password') {
      writeRememberedAccount(remember.value ? form.username.trim() : '');
    }
    if (result.passwordChangeRequired) {
      forcePasswordChange.value = true;
    } else {
      await router.replace(redirectTarget());
    }
  }

  async function submit(): Promise<void> {
    const valid = await formRef.value?.validate().catch(() => false);
    if (!valid || !captcha.value) return;
    loading.value = true;
    try {
      const result = await auth.signIn({
        username: form.username.trim(),
        password: form.password,
        captchaKey: captcha.value.captchaKey,
        captchaCode: form.captchaCode.trim(),
      });
      await handleLoginResult(result);
    } catch {
      await refreshCaptcha();
    } finally {
      loading.value = false;
    }
  }

  /** 短信登录:单命中直接签发;多命中挂起 pickKey 弹租户选择对话框。 */
  async function submitSms(): Promise<void> {
    const valid = await smsFormRef.value?.validate().catch(() => false);
    if (!valid) return;
    smsLoading.value = true;
    try {
      const result = await loginBySms({ phone: smsForm.phone.trim(), smsCode: smsForm.smsCode.trim() });
      if (isSmsAccountsResult(result)) {
        smsPick.value = result;
      } else {
        await handleLoginResult(result);
      }
    } catch {
      // 错码/锁定等文案已由拦截器弹出;短信码 5 分钟内可重试,输入保留
    } finally {
      smsLoading.value = false;
    }
  }

  /** 选定租户账号换令牌:pickKey 一次一用,失败即作废,须重走短信登录。 */
  async function pickAccount(account: SmsAccountOption): Promise<void> {
    if (!smsPick.value || pickingUserId.value !== null) return;
    pickingUserId.value = account.userId;
    try {
      const result = await pickSmsAccount({ pickKey: smsPick.value.pickKey, userId: account.userId });
      smsPick.value = null;
      await handleLoginResult(result);
    } catch {
      // pickKey 可能已过期或被用掉:关对话框,回短信表单重新走一遍
      smsPick.value = null;
      smsForm.smsCode = '';
    } finally {
      pickingUserId.value = null;
    }
  }

  /** 放弃选择:短信码已随首调核销,清码让用户重新发送。 */
  function onPickDialogClosed(): void {
    smsPick.value = null;
    smsForm.smsCode = '';
  }

  /* ------------------------- 其他登录方式(占位) ------------------------- */

  /** 钉钉/飞书/企业微信登录尚未开通:后端 login_type 已留编码位,接入时换跳转即可。 */
  function externalLogin(name: string): void {
    ElMessage.info(`${name}登录暂未开通,请使用账号密码或手机验证码登录`);
  }

  onMounted(() => void refreshCaptcha());

  return {
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
  };
}
