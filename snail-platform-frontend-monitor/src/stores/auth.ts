import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import {
  fetchLoginUser,
  login as loginRequest,
  logout as logoutRequest,
  type LoginResult,
  type LoginUser,
} from '@/api/auth';
import { clearToken, getToken, setToken } from '@/utils/token';

/** 会话状态:令牌、登录人快照与权限码;权限判定唯一入口 has()。
 *  无动态菜单:监控端导航来自静态路由 meta.perm,不调 /system/menu/current。 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref(getToken());
  const user = ref<LoginUser | null>(null);

  const loaded = computed(() => user.value !== null);
  const permissionSet = computed(() => new Set(user.value?.permissions ?? []));
  const roleNames = computed(() => {
    const names = (user.value?.roles ?? []).map((role) => role.roleName);
    return names.length ? names.join(' / ') : '未分配角色';
  });

  /** 传入数组按"任一命中"判定,对应按钮级 v-permission。 */
  function has(code: string | string[]): boolean {
    const codes = Array.isArray(code) ? code : [code];
    return codes.some((item) => permissionSet.value.has(item));
  }

  async function signIn(payload: { username: string; password: string; captchaKey: string; captchaCode: string }):
    Promise<LoginResult> {
    const result = await loginRequest(payload);
    token.value = result.token;
    setToken(result.token);
    return result;
  }

  async function loadSession(): Promise<void> {
    user.value = await fetchLoginUser();
  }

  function signOut(): void {
    token.value = '';
    user.value = null;
    clearToken();
  }

  /** 先吊销服务端令牌,再清本地;网络失败不阻断本地登出。 */
  async function signOutEverywhere(): Promise<void> {
    try {
      if (token.value) await logoutRequest();
    } catch {
      /* 令牌可能已失效,本地清理继续 */
    } finally {
      signOut();
    }
  }

  return { token, user, loaded, roleNames, has, signIn, loadSession, signOut, signOutEverywhere };
});
