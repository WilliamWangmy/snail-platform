<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import AppIcon from '@/components/AppIcon.vue';
import {
  isLoginAccounts,
  loginByOauth,
  OAUTH_REDIRECT_KEY,
  pickOauthAccount,
} from '@/api/auth';
import type { LoginSuccessVO, OAuthSource, SmsAccountOption } from '@/types/auth';
import { useAuthStore } from '@/stores/auth';

/**
 * 第三方扫码回调页：厂商授权后重定向回 /oauth-callback/:source?code=..&state=..，
 * 本页把 code 回传后端换登录态——单账号直接进站，多账号弹候选选择；state 过期/失效时
 * 给明确提示与返回登录入口。整页无菜单依赖，meta.public 放行。
 */
const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

const loading = ref(true);
const failed = ref(false);
/** 多账号候选态：非空即弹租户选择（pickKey 2 分钟一次性） */
const pick = ref<{ pickKey: string; accounts: SmsAccountOption[] } | null>(null);
const pickingUserId = ref<number | null>(null);

const OAUTH_SOURCES: readonly string[] = ['dingtalk', 'feishu', 'wecom'];

function sourceOf(): OAuthSource | null {
  const raw = String(route.params.source ?? '');
  return OAUTH_SOURCES.includes(raw) ? (raw as OAuthSource) : null;
}

/** 只接受站内路径，与登录页 redirectTarget 同口径；取完即清，避免二次进入复用旧目标 */
function redirectTarget(): string {
  let raw = '/dashboard';
  try {
    raw = sessionStorage.getItem(OAUTH_REDIRECT_KEY) ?? '/dashboard';
  } catch {
    /* 隐私模式读取失败按默认处理 */
  }
  const target = raw.startsWith('/') && !raw.startsWith('//') ? raw : '/dashboard';
  try {
    sessionStorage.removeItem(OAUTH_REDIRECT_KEY);
  } catch {
    /* 清理失败无碍，目标只是导航用途 */
  }
  return target;
}

/** 登录成功的统一收口：落令牌进站（待改密信号本端契约未承载，与密码/短信登录同口径） */
async function handleLoginSuccess(success: LoginSuccessVO): Promise<void> {
  auth.applyLoginSuccess(success);
  await router.push(redirectTarget());
}

onMounted(() => {
  const source = sourceOf();
  const code = typeof route.query.code === 'string' ? route.query.code : '';
  const state = typeof route.query.state === 'string' ? route.query.state : '';
  if (!source || !code) {
    failed.value = true;
    loading.value = false;
    return;
  }
  void (async () => {
    try {
      const result = await loginByOauth({
        source,
        code,
        state: state || undefined,
        redirectUri: `${window.location.origin}/oauth-callback/${source}`,
      });
      if (isLoginAccounts(result)) {
        pick.value = result;
        loading.value = false;
      } else {
        await handleLoginSuccess(result);
      }
    } catch {
      // 失败文案（登录已过期/未找到关联账号等）已由请求拦截器弹出，这里收敛到失败态给出口
      failed.value = true;
      loading.value = false;
    }
  })();
});

/** 选定租户账号换令牌：pickKey 一次一用，失败作废回失败态 */
async function pickAccount(account: SmsAccountOption): Promise<void> {
  if (!pick.value || pickingUserId.value !== null) return;
  pickingUserId.value = account.userId;
  try {
    const success = await pickOauthAccount({ pickKey: pick.value.pickKey, userId: account.userId });
    pick.value = null;
    await handleLoginSuccess(success);
  } catch {
    pick.value = null;
    failed.value = true;
  } finally {
    pickingUserId.value = null;
  }
}

function backToLogin(): void {
  void router.replace('/login');
}
</script>

<template>
  <div class="oauth-callback">
    <div class="callback-card">
      <template v-if="loading">
        <div class="callback-state">
          <AppIcon name="zap" :size="34" />
          <p>正在完成第三方登录…</p>
        </div>
      </template>

      <template v-else-if="pick">
        <h2>选择要进入的账号</h2>
        <p class="callback-hint">该第三方身份命中多个账号，请选定本次登录的租户账号。</p>
        <ul class="callback-accounts">
          <li v-for="account in pick.accounts" :key="account.userId">
            <button
              class="account-item"
              :disabled="pickingUserId !== null"
              @click="pickAccount(account)"
            >
              <span class="account-main">
                <b>{{ account.nickname || account.username }}</b>
                <span>{{ account.tenantName }}</span>
              </span>
              <span class="account-action">
                {{ pickingUserId === account.userId ? '进入中…' : '进入' }}
              </span>
            </button>
          </li>
        </ul>
      </template>

      <template v-else>
        <div class="callback-state">
          <AppIcon name="info" :size="34" />
          <p>第三方登录未完成</p>
          <p class="callback-hint">授权状态可能已过期，请返回登录页重新发起。</p>
          <button class="callback-back" @click="backToLogin">返回登录</button>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.oauth-callback {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--el-bg-color-page, #f2f3f5);
}

.callback-card {
  width: 360px;
  padding: 32px 28px;
  border-radius: 12px;
  background: var(--el-bg-color, #fff);
  box-shadow: 0 8px 30px rgb(0 0 0 / 8%);
}

.callback-card h2 {
  margin: 0 0 6px;
  font-size: 17px;
}

.callback-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  text-align: center;
}

.callback-hint {
  margin: 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.callback-accounts {
  list-style: none;
  margin: 14px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.account-item {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
  text-align: left;
}

.account-item:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.account-item:not(:disabled):hover {
  border-color: var(--el-color-primary);
}

.account-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 14px;
}

.account-main span {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.account-action {
  font-size: 13px;
  color: var(--el-color-primary);
}

.callback-back {
  margin-top: 6px;
  padding: 8px 22px;
  border: none;
  border-radius: 6px;
  background: var(--el-color-primary);
  color: #fff;
  cursor: pointer;
}
</style>
