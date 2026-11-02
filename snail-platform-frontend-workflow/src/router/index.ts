/**
 * 路由装配 + 全局守卫。
 * 守卫规则：
 * 1. meta.public 路由直接放行；
 * 2. 无 token → /login?redirect=原地址；
 * 3. 有 token 但无 loginUser → 静默 GET /system/auth/me 填充（失败视同未认证）；
 * 4. 已登录访问 /login → /dashboard。
 */
import { createRouter, createWebHistory } from 'vue-router';

import { routes } from '@/router/routes';
import { useAuthStore } from '@/stores/auth';

const router = createRouter({
  history: createWebHistory(),
  routes,
});

router.beforeEach(async (to) => {
  document.title = to.meta.title ? `${String(to.meta.title)} · Snail 流程管理平台` : 'Snail 流程管理平台';

  const auth = useAuthStore();

  if (to.meta.public) {
    return auth.isLoggedIn ? { path: '/dashboard' } : true;
  }

  if (!auth.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } };
  }

  if (!auth.loginUser) {
    try {
      await auth.fetchMe();
    } catch {
      // fetchMe 401 时拦截器已清态跳登录；其他错误同样按未认证处理
      return { path: '/login', query: { redirect: to.fullPath } };
    }
  }

  return true;
});

export default router;
