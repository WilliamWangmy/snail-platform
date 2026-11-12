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
import { ElMessage } from 'element-plus';

import { routes } from '@/router/routes';
import { ssoCheckIn } from '@/api/auth';
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
    // 落地即签到：后端补一条 sso 登录日志并记分端统计；失败静默，日志链路故障不挡进入
    void ssoCheckIn().catch(() => {});
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

  // 开通闸(租户管理增强 T10):租户未开通流程系统 → 登出+提示,ssotoken 免登落地同样被拦。
  // tenantFeatures 是 /me 实时值(不进 JWT/登录快照),平台管理员收回开关后下一次路由即触发;
  // 字段缺失(旧后端)视为不拦,向后兼容。
  if (auth.loginUser?.tenantFeatures && !auth.loginUser.tenantFeatures.workflowEnabled) {
    await auth.logout();
    ElMessage.warning('所在租户未开通流程系统,请联系平台管理员');
    return { path: '/login', query: { redirect: to.fullPath } };
  }

  return true;
});

export default router;
