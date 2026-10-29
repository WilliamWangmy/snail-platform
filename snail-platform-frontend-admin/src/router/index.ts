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
    // 强制改密页:非 public、非菜单路由,不带布局——待改密会话进不了业务菜单
    path: '/force-change-password',
    name: 'force-change-password',
    component: () => import('@/views/force-change-password/index.vue'),
    meta: { title: '修改初始密码' },
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

/** 首个受保护路由先取登录人与导航菜单,再按菜单纯态注册业务路由;令牌失效即退回登录页。
 *  顺序冻结:loadSession → 判待改密标志 → 命中即跳改密页并跳过 loadMenus——
 *  菜单接口属业务接口,会被后端兜底闸 403,先拉后判会 401→登出→再登录→再拉菜单 死循环。 */
router.beforeEach(async (to) => {
  const auth = useAuthStore();
  if (to.meta.public) {
    return to.name === 'login' && auth.token ? { path: '/' } : true;
  }
  if (!auth.token) {
    return { path: '/login', query: { redirect: to.fullPath } };
  }
  if (auth.loaded) {
    if (needsPasswordChange(auth)) {
      return to.name === 'force-change-password' ? true : { path: '/force-change-password' };
    }
    return to.name === 'force-change-password' ? { path: '/' } : true;
  }
  try {
    await auth.loadSession();
    if (needsPasswordChange(auth)) {
      return to.name === 'force-change-password' ? true : { path: '/force-change-password' };
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
