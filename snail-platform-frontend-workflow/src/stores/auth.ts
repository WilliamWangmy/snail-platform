/**
 * 登录态：token、当前登录人、租户信息。
 * 页面不直接调 auth api，统一走本 store 的 action。
 */
import { computed, ref } from 'vue';
import { defineStore } from 'pinia';

import * as authApi from '@/api/auth';
import { STORAGE_KEY_TOKEN } from '@/constants';
import { getItem, removeItem, setItem } from '@/utils/storage';
import type { LoginRequest, LoginSuccessVO, LoginUserVO } from '@/types/auth';

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(getItem(STORAGE_KEY_TOKEN) ?? '');
  const loginUser = ref<LoginUserVO | null>(null);

  const isLoggedIn = computed(() => token.value !== '');
  const tenantName = computed(() => loginUser.value?.tenantName ?? '');
  const displayName = computed(() => loginUser.value?.nickname ?? loginUser.value?.username ?? '');

  function setToken(value: string): void {
    token.value = value;
    if (value) {
      setItem(STORAGE_KEY_TOKEN, value);
    } else {
      removeItem(STORAGE_KEY_TOKEN);
    }
  }

  /** 权限判定唯一入口：登录人快照里的权限码集合（后端不下发即视为无权限）。 */
  function has(code: string): boolean {
    return loginUser.value?.permissions?.includes(code) === true;
  }

  /** 静默拉取当前登录人（守卫在 token 存在但用户为空时调用） */
  async function fetchMe(): Promise<LoginUserVO> {
    const me = await authApi.getMe();
    loginUser.value = me;
    return me;
  }

  async function login(payload: LoginRequest): Promise<void> {
    const success = await authApi.login(payload);
    setToken(success.token);
  }

  /** 登录成功的统一落点：密码/短信/选租户三链共用（页面不直接调 auth api 的约定保持） */
  function applyLoginSuccess(success: LoginSuccessVO): void {
    setToken(success.token);
  }

  /** 登出：后端失效 + 清本地态；页面跳转由调用方负责 */
  async function logout(): Promise<void> {
    try {
      await authApi.logout();
    } finally {
      setToken('');
      loginUser.value = null;
    }
  }

  /** 仅清本地态（401 时由请求拦截器触发，不再等后端） */
  function clearLocal(): void {
    setToken('');
    loginUser.value = null;
  }

  return { token, loginUser, isLoggedIn, tenantName, displayName, has, setToken, fetchMe, login, applyLoginSuccess, logout, clearLocal };
});
