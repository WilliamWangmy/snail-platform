import { createRouter, createWebHistory } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { ssoCheckIn } from '@/api/auth';
import { setToken, clearToken } from '@/utils/token';
import { routes } from './routes';

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
});

/**
 * 守卫:无 token → /login;有 token 无会话快照 → getMe();meta.perm 且无码 → /403;
 * ssotoken 查询参数 → 存 token、清 URL、ssoCheckIn() 后放行(失败清 token 回登录页并透传后端 message)。
 * 无租户开关守卫:监控端只允许平台租户登录,租户闸在后端 scene=monitor 登录/签到时执行。
 */
router.beforeEach(async (to) => {
  const auth = useAuthStore();

  // 跨端免登:一次性 ssotoken 落地,签到成功进页,失败清会话回登录页
  if (typeof to.query.ssotoken === 'string' && to.query.ssotoken) {
    const token = to.query.ssotoken;
    setToken(token);
    auth.token = token;
    const rest = { ...to.query };
    delete rest.ssotoken;
    try {
      await ssoCheckIn();
      await auth.loadSession();
      return { path: to.path, query: rest, replace: true };
    } catch (error) {
      clearToken();
      auth.signOut();
      const message = error instanceof Error && error.message ? error.message : '签到失败，请重新登录';
      return { path: '/login', query: { message }, replace: true };
    }
  }

  if (to.meta.public) {
    return true;
  }
  if (!auth.token) {
    return { path: '/login', query: { redirect: to.fullPath } };
  }
  if (!auth.loaded) {
    try {
      await auth.loadSession();
    } catch {
      auth.signOut();
      return { path: '/login', query: { redirect: to.fullPath } };
    }
  }
  if (to.meta.perm && !auth.has(to.meta.perm as string)) {
    return { path: '/403', query: { from: to.fullPath } };
  }
  return true;
});

router.afterEach((to) => {
  const title = to.meta.title as string | undefined;
  document.title = title ? `${title} · Snail Monitor` : 'Snail Monitor';
});

export default router;
