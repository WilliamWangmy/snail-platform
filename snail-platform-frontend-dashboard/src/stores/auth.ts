import { logout as logoutRequest } from '@/api/auth';

/** 记住的是令牌而非明文口令:刷新后守卫凭它放行,过期由 http 层 401 兜底回登录页。 */
const TOKEN_KEY = 'snail_dashboard_token';

export function getToken(): string {
  try {
    return localStorage.getItem(TOKEN_KEY) ?? '';
  } catch {
    return '';
  }
}

export function setToken(token: string): void {
  try {
    localStorage.setItem(TOKEN_KEY, token);
  } catch {
    /* 隐私模式写入失败时会话仅存内存 */
  }
}

export function clearToken(): void {
  try {
    localStorage.removeItem(TOKEN_KEY);
  } catch {
    /* ignore */
  }
}

/** 登录成功的统一落点:密码/短信/选租户三链共用,只落令牌。 */
export function applyLoginResult(token: string): void {
  setToken(token);
}

/** 先吊销服务端令牌,再清本地;网络失败不阻断本地登出。 */
export async function signOutEverywhere(): Promise<void> {
  try {
    if (getToken()) await logoutRequest();
  } catch {
    /* 令牌可能已失效,本地清理继续 */
  } finally {
    clearToken();
  }
}
