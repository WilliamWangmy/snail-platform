import { createRouter, createWebHistory } from 'vue-router';
import { getToken, setToken } from '@/stores/auth';
import { ssoCheckIn } from '@/api/auth';

/**
 * 大屏路由:/login 登录页,/ 数据大屏本体(三维地图)。
 * 未匹配路径一律回大屏首页,保证登录后总有落点。
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/',
      name: 'dashboard',
      component: () => import('@/views/DashboardView.vue'),
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
});

/** 全局守卫:除公开页外一律要求登录态,未认证统一重定向登录页并记下来路。 */
router.beforeEach((to) => {
  // 管理端/流程平台免登录跳转:ssotoken 落地为本地会话后去参重走守卫,后续与 F5 恢复会话同路径
  if (typeof to.query.ssotoken === 'string' && to.query.ssotoken) {
    setToken(to.query.ssotoken);
    // 落地即签到:后端补一条 sso 登录日志并记分端统计;失败静默,日志链路故障不挡进入
    void ssoCheckIn().catch(() => {});
    const rest = { ...to.query };
    delete rest.ssotoken;
    return { path: to.path, query: rest, replace: true };
  }

  const authed = Boolean(getToken());
  if (!to.meta.public && !authed) {
    return { name: 'login', query: { redirect: to.fullPath } };
  }
  // 已登录再进登录页没有意义,直接回大屏
  if (to.name === 'login' && authed) {
    return { name: 'dashboard' };
  }
  return true;
});

export default router;
