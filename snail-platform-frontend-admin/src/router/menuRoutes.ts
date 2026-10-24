import type { Component } from 'vue';
import type { RouteRecordRaw, Router } from 'vue-router';
import type { MenuRoute } from '@/api/menu';
import { menuIcon } from '@/utils/icons';

/** 组件注册表单点:菜单表的 component 值即 views 下相对路径,新增页面不必改路由表。 */
const registry = import.meta.glob<{ default: Component }>('../views/**/index.vue');
const missing = () => import('@/views/error/NotFound.vue');

/** 布局路由名,动态菜单全部挂它下面。 */
export const WORKSPACE = 'workspace';

const dynamicNames: string[] = [];

/** 目录不建路由,外链走侧栏新标签;菜单按 path 直接注册为绝对路径子路由。 */
export function installMenuRoutes(router: Router, menus: MenuRoute[]): void {
  walk(menus, [], (node, breadcrumb) => {
    if (node.menuType !== 2 || node.target === 3 || !node.path) return;
    const name = `menu-${node.menuId}`;
    if (router.hasRoute(name)) return;
    const loader = node.component ? registry[`../views/${node.component}.vue`] : undefined;
    const route: RouteRecordRaw = {
      path: node.path,
      name,
      component: loader ?? missing,
      meta: { title: node.menuName, icon: menuIcon(node.icon), breadcrumb },
    };
    router.addRoute(WORKSPACE, route);
    dynamicNames.push(name);
  });
}

/** 退出登录要清干净,否则换租户后旧路由仍可直达。 */
export function resetMenuRoutes(router: Router): void {
  for (const name of dynamicNames.splice(0)) {
    if (router.hasRoute(name)) router.removeRoute(name);
  }
}

function walk(
  nodes: MenuRoute[],
  parents: string[],
  visit: (node: MenuRoute, breadcrumb: string[]) => void,
): void {
  for (const node of nodes) {
    const breadcrumb = [...parents, node.menuName];
    visit(node, breadcrumb);
    if (node.children?.length) walk(node.children, breadcrumb, visit);
  }
}
