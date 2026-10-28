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
  /** 站内/大屏为路由;外链为完整 URL,前端只把它挂在新标签上,不注册路由 */
  path: string | null;
  /** views 下的相对组件路径;外链与目录为空 */
  component: string | null;
  icon: string | null;
  sort: number;
  /** 0 显示 1 隐藏;后端照样返回,是否入侧栏由导航自己决定 */
  visible: number;
  children: MenuRoute[];
}

/** 平台菜单目录节点:管理页面用,含按钮与启停状态。 */
export interface MenuNode {
  menuId: number;
  parentId: number;
  menuName: string;
  /** 1 目录 2 菜单 3 按钮;建后不可改 */
  menuType: number;
  /** 1 站内 2 数据大屏 3 外链;建后不可改 */
  target: number;
  /** 1 平台 2 租户;建后不可改,决定谁能看见与授予 */
  scope: number;
  path: string | null;
  component: string | null;
  /** 全局唯一且含已删记录,建后不可改;目录与外链为 null */
  permCode: string | null;
  icon: string | null;
  sort: number;
  /** 0 显示 1 隐藏;按钮恒 0 */
  visible: number;
  /** 0 正常 1 停用;停用后节点连同子树退出导航与权限解析 */
  status: number;
  /** 1 内置:种子写入,不可删、不可停用 */
  isSystem: number;
  remark: string | null;
  children: MenuNode[];
}

/** 新增载荷:parentId 不传即根 0;三元组与 permCode 只能在这里给,修改载荷里没有它们。 */
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

/** 导航树:来自角色授权解析(role 子域),只含本人被授到的目录与菜单;空目录与停用链已被后端剪掉,按钮不出现。 */
export function fetchCurrentMenus(target: number): Promise<MenuRoute[]> {
  return get<MenuRoute[]>('/system/menu/current', { target });
}

/** 平台全量目录:含平台面、停用节点与按钮,不分页;后端有 menuTree 缓存,任一写操作整体失效。 */
export function fetchMenuTree(): Promise<MenuNode[]> {
  return get<MenuNode[]>('/system/menu/tree');
}

/**
 * 新增:三元组(类型/展示端/归属面)必填且建后不可改,搭配口径由后端校验(目录不配码与组件、菜单必配两者、按钮只配码)。
 * 父约束同样是后端判:根只能是目录,按钮与外链不能挂下级,导航节点子承父面,大屏树与站内树不互嵌。
 */
export function createMenu(payload: MenuCreatePayload): Promise<number> {
  return post<number>('/system/menu', payload);
}

/** 修改:path/component/icon/remark 不传即清空,sort/visible/status 不传不改;内置节点不可停用 */
export function updateMenu(menuId: number, payload: MenuUpdatePayload): Promise<void> {
  return put<void>(`/system/menu/${menuId}`, payload);
}

/** 删除:内置拒、有下级拒;已被角色授权不拒,后端同事务清掉那些授权行。逻辑删后权限码终身占用 */
export function deleteMenu(menuId: number): Promise<void> {
  return del<void>(`/system/menu/${menuId}`);
}
