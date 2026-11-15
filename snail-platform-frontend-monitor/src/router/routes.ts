/** 静态路由表:一级导航取根 Layout 的 children 过滤 meta.title;无动态菜单(D-01)。
 *  alias /monitor/*:菜单种子 path 是 /monitor/overview(权限树载体),浏览器直达命中同一页面(DD-13)。 */

export interface MonitorRouteMeta {
  title: string;
  public?: boolean;
  perm?: string;
  fullBleed?: boolean;
}

export const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/login/index.vue'),
    meta: { title: '登录', public: true },
  },
  {
    path: '/403',
    name: 'forbidden',
    component: () => import('@/views/error/403.vue'),
    meta: { title: '无权限', public: true },
  },
  {
    path: '/404',
    name: 'not-found',
    component: () => import('@/views/error/404.vue'),
    meta: { title: '页面不存在', public: true },
  },
  {
    path: '/',
    component: () => import('@/layout/index.vue'),
    redirect: '/overview',
    children: [
      {
        path: 'overview',
        alias: '/monitor/overview',
        name: 'overview',
        component: () => import('@/views/overview/index.vue'),
        meta: { title: '监控总览', perm: 'platform:monitor:overview' },
      },
      {
        path: 'alert',
        alias: '/monitor/alert',
        name: 'alert',
        component: () => import('@/views/alert/index.vue'),
        meta: { title: '告警中心', perm: 'platform:monitor:alert' },
      },
      {
        path: 'log',
        alias: '/monitor/log',
        name: 'log',
        component: () => import('@/views/log/index.vue'),
        meta: { title: '日志查询', perm: 'platform:monitor:log' },
      },
      {
        path: 'diag',
        alias: '/monitor/diag',
        name: 'diag',
        component: () => import('@/views/diag/index.vue'),
        meta: { title: '系统诊断', perm: 'platform:monitor:diag' },
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/404' },
];
