import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { fetchLoginUser, login as loginRequest, logout as logoutRequest, type LoginPayload, type LoginResult, type LoginUser } from '@/api/auth';
import { fetchCurrentMenus, type MenuRoute } from '@/api/menu';
import { clearToken, getToken, setToken } from '@/utils/token';

/** 会话状态:令牌、登录人快照与站内导航菜单;权限判定唯一入口 has()。 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref(getToken());
  const user = ref<LoginUser | null>(null);
  const menus = ref<MenuRoute[]>([]);
  /** 登录响应带来的一次性跳转信号;刷新恢复会话后以 user.passwordChangeRequired 为准 */
  const pendingPasswordChange = ref(false);

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

  async function signIn(payload: LoginPayload): Promise<LoginResult> {
    const result = await loginRequest(payload);
    token.value = result.token;
    setToken(result.token);
    pendingPasswordChange.value = result.passwordChangeRequired;
    return result;
  }

  async function loadSession(): Promise<void> {
    user.value = await fetchLoginUser();
  }

  async function loadMenus(): Promise<void> {
    menus.value = await fetchCurrentMenus(1);
  }

  function signOut(): void {
    token.value = '';
    user.value = null;
    menus.value = [];
    pendingPasswordChange.value = false;
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

  return {
    token,
    user,
    menus,
    pendingPasswordChange,
    loaded,
    roleNames,
    has,
    signIn,
    loadSession,
    loadMenus,
    signOut,
    signOutEverywhere,
  };
});
