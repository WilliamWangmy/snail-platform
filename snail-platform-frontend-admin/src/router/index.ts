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

/** 首个受保护路由先取登录人与导航菜单,再按菜单纯态注册业务路由;令牌失效即退回登录页。 */
router.beforeEach(async (to) => {
  const auth = useAuthStore();
  if (to.meta.public) {
    return to.name === 'login' && auth.token ? { path: '/' } : true;
  }
  if (!auth.token) {
    return { path: '/login', query: { redirect: to.fullPath } };
  }
  if (auth.loaded) return true;
  try {
    await auth.loadSession();
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
