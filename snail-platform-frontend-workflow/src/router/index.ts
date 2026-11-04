/**
 * 路由装配 + 全局守卫。
 * 守卫规则：
 * 1. 携带 ssotoken（管理端免登录跳转）→ 落地为本地 token 并去参重走守卫；
 * 2. meta.public 路由直接放行；
 * 3. 无 token → /login?redirect=原地址；
 * 4. 有 token 但无 loginUser → 静默 GET /system/auth/me 填充（失败视同未认证）；
 * 5. 已登录访问 /login → /dashboard。
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

  // 管理端免登录跳转：一次性 ssotoken 落地后去掉参数重走守卫（fetchMe 校验，失败按未认证处理）
  if (typeof to.query.ssotoken === 'string' && to.query.ssotoken) {
    auth.setToken(to.query.ssotoken);
    const rest = { ...to.query };
    delete rest.ssotoken;
    return { path: to.path, query: rest, replace: true };
  }

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
