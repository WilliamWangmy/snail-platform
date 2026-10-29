import { computed, onMounted, reactive, ref, type ShallowRef } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import type { FormInstance, FormRules } from 'element-plus';
import { fetchCaptcha, type Captcha } from '@/api/auth';
import { useAuthStore } from '@/stores/auth';

/** 记住的是账号名而非登录态:刷新后只回填登录名,令牌仍由 auth store 持有。 */
const REMEMBER_KEY = 'snail_admin_login_account_v1';

type CaptchaState = 'loading' | 'ready' | 'failed';

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

/** 登录页状态机:验证码生命周期、表单校验、登录提交与跳转回填。表单 ref 由视图持有并注入。 */
export function useLogin(formRef: Readonly<ShallowRef<FormInstance | null>>) {
  const route = useRoute();
  const router = useRouter();
  const auth = useAuthStore();

  const loading = ref(false);
  const captcha = ref<Captcha | null>(null);
  const captchaState = ref<CaptchaState>('loading');

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

  const statusLabel = computed(() => {
    if (captchaState.value === 'ready') return '认证服务正常';
    return captchaState.value === 'failed' ? '认证服务不可用' : '认证服务连接中';
  });

  /** 验证码一次即失效:加载失败或登录失败都要换码,并清空已输入的 4 位。 */
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
    }
  }

  /** 只接受站内路径,避免 redirect 参数被拼成外部跳转。 */
  function redirectTarget(): string {
    const raw = typeof route.query.redirect === 'string' ? route.query.redirect : '/';
    return raw.startsWith('/') && !raw.startsWith('//') ? raw : '/';
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
      writeRememberedAccount(remember.value ? form.username.trim() : '');
      // 待强制改密的会话进不了业务页,redirect 参数让位
      if (result.passwordChangeRequired) {
        await router.replace('/force-change-password');
      } else {
        await router.replace(redirectTarget());
      }
    } catch {
      await refreshCaptcha();
    } finally {
      loading.value = false;
    }
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
    submit,
    refreshCaptcha,
  };
}
