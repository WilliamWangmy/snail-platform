import type { Component } from 'vue';
import type { RouteRecordRaw, Router } from 'vue-router';
import type { MenuRoute } from '@/api/menu';
import { menuIcon } from '@/utils/icons';

/** 组件注册表单点:菜单表的 component 值即 views 下相对路径,新增页面不必改路由表。 */
const registry = import.meta.glob<{ default: Component }>('../views/**/index.vue');
/** 组件路径配错时的兜底:让页面显示 404 占位,好过白屏或路由解析抛错。 */
const missing = () => import('@/views/error/NotFound.vue');

/** 布局路由名,动态菜单全部挂它下面。 */
export const WORKSPACE = 'workspace';

const dynamicNames: string[] = [];

/** 目录不建路由,外链走侧栏新标签;菜单按 path 直接注册为绝对路径子路由。 */
export function installMenuRoutes(router: Router, menus: MenuRoute[]): void {
  walk(menus, [], (node, breadcrumb) => {
    // 目录不出现在路由表里(它只是侧栏分组),外链交给侧栏开新标签,两者都不占路由
    if (node.menuType !== 2 || node.target === 3 || !node.path) return;
    const name = `menu-${node.menuId}`;
    // 路由名按 menuId 生成,同名即同节点:重复进来(换账号再登录)时跳过,不叠出第二份路由
    if (router.hasRoute(name)) return;
    // 拼出的键必须与 glob 键同形(../views/<component>.vue);对不上就退回兜底组件,不抛错
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
