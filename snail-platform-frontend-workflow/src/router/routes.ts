/**
 * 静态路由表。顶部一级菜单由 Layout 子路由 + meta.title 驱动渲染，增删菜单只改这里。
 * meta.perm = 进入/展示该页所需的权限码（与 sys_menu 菜单种子的 perm_code 一致）：
 * TopNav 按 auth.has(perm) 过滤导航，无码用户不出入口；不配 perm 的页面（首页大屏）登录即可见。
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
        meta: { title: '流程建模', perm: 'system:workflow-model:list' },
      },
      {
        path: 'modeler/:modelId/edit',
        name: 'modeler-designer',
        component: () => import('@/views/modeler/designer/DesignerPage.vue'),
        meta: { title: '流程建模', fullBleed: true, perm: 'system:workflow-model:list' },
      },
      {
        // 流程定义页与建模页同码(system:workflow-model:list),菜单目录里不单设节点
        path: 'definition',
        name: 'definition',
        component: () => import('@/views/definition/index.vue'),
        meta: { title: '流程定义', perm: 'system:workflow-model:list' },
      },
      {
        path: 'instance',
        name: 'instance',
        component: () => import('@/views/instance/index.vue'),
        meta: { title: '流程实例', perm: 'system:workflow-instance:list' },
      },
      {
        path: 'task',
        name: 'task',
        component: () => import('@/views/task/index.vue'),
        meta: { title: '任务中心', perm: 'system:workflow-task:list' },
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
];
