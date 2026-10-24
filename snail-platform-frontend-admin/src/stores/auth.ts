import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { fetchLoginUser, login as loginRequest, type LoginPayload, type LoginUser } from '@/api/auth';
import { fetchCurrentMenus, type MenuRoute } from '@/api/menu';
import { clearToken, getToken, setToken } from '@/utils/token';

/** 会话状态:令牌、登录人快照与站内导航菜单;权限判定唯一入口 has()。 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref(getToken());
  const user = ref<LoginUser | null>(null);
  const menus = ref<MenuRoute[]>([]);

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

  async function signIn(payload: LoginPayload): Promise<void> {
    const result = await loginRequest(payload);
    token.value = result.token;
    setToken(result.token);
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
    clearToken();
  }

  return { token, user, menus, loaded, roleNames, has, signIn, loadSession, loadMenus, signOut };
});
