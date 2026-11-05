/**
 * 静态路由表。顶部一级菜单由 Layout 子路由 + meta.title 驱动渲染，增删菜单只改这里。
 */
import type { RouteRecordRaw } from 'vue-router';

import Layout from '@/layout/index.vue';

export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/login/index.vue'),
    meta: { title: '登录', public: true },
  },
  {
    // 第三方扫码回调页:厂商回跳带 code/state 落在这里,与登录页无共享状态
    path: '/oauth-callback/:source',
    name: 'oauth-callback',
    component: () => import('@/views/login/OAuthCallback.vue'),
    meta: { title: '第三方登录', public: true },
  },
  {
    path: '/',
    component: Layout,
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: { title: '首页大屏' },
      },
      {
        path: 'modeler',
        name: 'modeler',
        component: () => import('@/views/modeler/index.vue'),
        meta: { title: '流程建模' },
      },
      {
        path: 'modeler/:modelId/edit',
        name: 'modeler-designer',
        component: () => import('@/views/modeler/designer/DesignerPage.vue'),
        meta: { title: '流程建模', fullBleed: true },
      },
      {
        path: 'definition',
        name: 'definition',
        component: () => import('@/views/definition/index.vue'),
        meta: { title: '流程定义' },
      },
      {
        path: 'instance',
        name: 'instance',
        component: () => import('@/views/instance/index.vue'),
        meta: { title: '流程实例' },
      },
      {
        path: 'task',
        name: 'task',
        component: () => import('@/views/task/index.vue'),
        meta: { title: '任务中心' },
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
];
