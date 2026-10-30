import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import DefaultLayout from '@/layouts/DefaultLayout.vue';
import { useAuthStore } from '@/stores/auth';
import { installMenuRoutes, resetMenuRoutes, WORKSPACE } from './menuRoutes';

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/login/index.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/403',
    name: 'forbidden',
    component: () => import('@/views/error/ForbiddenView.vue'),
    meta: { public: true, title: '无权限' },
  },
  {
    path: '/',
    name: WORKSPACE,
    component: DefaultLayout,
    redirect: '/home',
    children: [
      {
        path: '/home',
        name: 'home',
        component: () => import('@/views/home/index.vue'),
        meta: { title: '工作台', icon: 'grid', breadcrumb: ['工作台'] },
      },
      {
        path: '/profile',
        name: 'profile',
        component: () => import('@/views/profile/index.vue'),
        meta: { title: '个人中心', icon: 'user', breadcrumb: ['个人中心'] },
      },
      {
        path: '/:pathMatch(.*)*',
        name: 'not-found',
        component: () => import('@/views/error/NotFound.vue'),
        meta: { title: '页面不存在' },
      },
    ],
  },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
});

/** 待改密判定:刷新恢复后以 /me 的 user.passwordChangeRequired 为准,登录响应信号兜底。 */
function needsPasswordChange(auth: ReturnType<typeof useAuthStore>): boolean {
  return auth.user?.passwordChangeRequired === true || auth.pendingPasswordChange;
}

/** 待改密会话没有独立路由:守卫把带令牌的待改密访问一律收拢到登录页,由页面弹出强制改密窗。
 *  顺序冻结:loadSession → 判待改密标志 → 命中即留在登录页并跳过 loadMenus——
 *  菜单接口属业务接口,会被后端兜底闸 403,先拉后判会 401→登出→再登录→再拉菜单 死循环。 */
router.beforeEach(async (to) => {
  const auth = useAuthStore();
  if (to.meta.public) {
    if (to.name !== 'login' || !auth.token) return true;
    // 带令牌访问登录页:先恢复会话,待改密则留在登录页弹改密窗,其余回工作台
    try {
      if (!auth.loaded) await auth.loadSession();
      if (needsPasswordChange(auth)) return true;
      if (!auth.menus.length) {
        await auth.loadMenus();
        installMenuRoutes(router, auth.menus);
      }
    } catch {
      // 半途失败也要清:上一次成功注册的业务路由还挂在实例上,不清就是"换个账号仍能直达上一页"
      resetMenuRoutes(router);
      auth.signOut();
      return true;
    }
    return { path: '/' };
  }
  if (!auth.token) {
    return { path: '/login', query: { redirect: to.fullPath } };
  }
  if (auth.loaded) {
    return needsPasswordChange(auth) ? { path: '/login' } : true;
  }
  try {
    await auth.loadSession();
    if (needsPasswordChange(auth)) {
      return { path: '/login' };
    }
    await auth.loadMenus();
  } catch {
    // 半途失败也要清:上一次成功注册的业务路由还挂在实例上,不清就是"换个账号仍能直达上一页"
    resetMenuRoutes(router);
    auth.signOut();
    return { path: '/login', query: { redirect: to.fullPath } };
  }
  installMenuRoutes(router, auth.menus);
  return to.fullPath;
});

router.afterEach((to) => {
  const title = to.meta.title as string | undefined;
  document.title = title ? `${title} · Snail Admin` : 'Snail Admin';
});

export default router;
