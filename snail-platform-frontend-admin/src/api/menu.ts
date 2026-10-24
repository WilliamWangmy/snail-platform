import { del, get, post, put } from './http';

/** 导航节点:只含目录与菜单,按钮不进这里。 */
export interface MenuRoute {
  menuId: number;
  parentId: number;
  menuName: string;
  /** 1 目录 2 菜单 */
  menuType: number;
  /** 1 站内 2 数据大屏 3 外链 */
  target: number;
  path: string | null;
  component: string | null;
  icon: string | null;
  sort: number;
  /** 0 显示 1 隐藏 */
  visible: number;
  children: MenuRoute[];
}

/** 平台菜单目录节点:管理页面用,含按钮与启停状态。 */
export interface MenuNode {
  menuId: number;
  parentId: number;
  menuName: string;
  menuType: number;
  target: number;
  scope: number;
  path: string | null;
  component: string | null;
  permCode: string | null;
  icon: string | null;
  sort: number;
  visible: number;
  status: number;
  isSystem: number;
  remark: string | null;
  children: MenuNode[];
}

export interface MenuCreatePayload {
  parentId?: number;
  menuName: string;
  menuType: number;
  target: number;
  scope: number;
  path?: string | null;
  component?: string | null;
  permCode?: string | null;
  icon?: string | null;
  sort?: number;
  visible?: number;
  remark?: string | null;
}

/** 建后不可改:menuType/target/scope/permCode 不在修改载荷里。 */
export interface MenuUpdatePayload {
  menuName: string;
  path?: string | null;
  component?: string | null;
  icon?: string | null;
  sort?: number;
  visible?: number;
  status?: number;
  remark?: string | null;
}

export function fetchCurrentMenus(target: number): Promise<MenuRoute[]> {
  return get<MenuRoute[]>('/system/menu/current', { target });
}

export function fetchMenuTree(): Promise<MenuNode[]> {
  return get<MenuNode[]>('/system/menu/tree');
}

export function createMenu(payload: MenuCreatePayload): Promise<number> {
  return post<number>('/system/menu', payload);
}

export function updateMenu(menuId: number, payload: MenuUpdatePayload): Promise<void> {
  return put<void>(`/system/menu/${menuId}`, payload);
}

export function deleteMenu(menuId: number): Promise<void> {
  return del<void>(`/system/menu/${menuId}`);
}
