import { del, get, post, put } from './http';
import type { PageParams, PageResult } from './types';

export interface RoleVO {
  roleId: number;
  roleName: string;
  roleCode: string;
  /** 1 全部 2 本组织 3 本组织及以下 4 本人;数字越小越宽 */
  dataScope: number;
  /** 1 内置:由开通模板维护,管理端只读(改/停/删一律被后端拒) */
  isSystem: number;
  sort: number;
  /** 0 正常 1 停用;停用角色的权限码与数据档位同时失效 */
  status: number;
  remark: string | null;
  createTime: string;
}

export interface RoleQuery extends PageParams {
  keyword?: string;
  status?: number | null;
}

export interface RoleCreatePayload {
  roleName: string;
  roleCode: string;
  dataScope: number;
  sort?: number;
  remark?: string | null;
}

/** 修改契约里没有 roleCode:建后不可改由收不到这个字段保证,前端禁用输入框只是同形表达。 */
export interface RoleUpdatePayload {
  roleName: string;
  dataScope: number;
  sort?: number;
  status?: number;
  remark?: string | null;
}

/**
 * 授权树节点:后端建节点时就按当前操作者筛过了——无 platform:role:assign-menu 者拿不到 scope=1 节点,
 * 不是"拿到但置灰"。checked 只反映 sys_role_menu 显式授权行,内置超管隐式持有的租户面菜单不算勾选。
 */
export interface RoleMenuNode {
  menuId: number;
  parentId: number;
  menuName: string;
  menuType: number;
  target: number;
  scope: number;
  permCode: string | null;
  status: number;
  checked: boolean;
  children: RoleMenuNode[];
}

/** 本租户内的角色全集:后端不给角色列表开数据权限窗口,可见性由 system:role:list 权限码管 */
export function pageRoles(query: RoleQuery): Promise<PageResult<RoleVO>> {
  return get<PageResult<RoleVO>>('/system/role/page', query);
}

/** 新增:同租户内 roleCode 唯一;档位宽于操作者自身最宽档会被拒;返回新角色 ID */
export function createRole(payload: RoleCreatePayload): Promise<number> {
  return post<number>('/system/role', payload);
}

/** 修改:内置角色拒;成功后 userPerms 与 userSession 整体清空(全清,不按用户点清) */
export function updateRole(roleId: number, payload: RoleUpdatePayload): Promise<void> {
  return put<void>(`/system/role/${roleId}`, payload);
}

/** 停用/启用:status 走查询串而非请求体;内置角色拒;停用即时收走该角色的全部权限码 */
export function changeRoleStatus(roleId: number, status: number): Promise<void> {
  return put<void>(`/system/role/${roleId}/status`, undefined, { status });
}

/** 删除:内置拒、角色下还挂着用户拒;逻辑删后 roleCode 在同租户内终身占用 */
export function deleteRole(roleId: number): Promise<void> {
  return del<void>(`/system/role/${roleId}`);
}

export function fetchRoleMenus(roleId: number): Promise<RoleMenuNode[]> {
  return get<RoleMenuNode[]>(`/system/role/${roleId}/menus`);
}

/**
 * 整包授权:提交的这组菜单即该角色的最终授权集(全量替换,非增量)。
 * 无平台授权码时后端只替换租户面,已授出去的平台面授权原样保留;
 * 但提交内容里出现 scope=1 菜单仍会被 403 拒掉,不静默丢弃。
 */
export function assignRoleMenus(roleId: number, menuIds: number[]): Promise<void> {
  return put<void>(`/system/role/${roleId}/menus`, { menuIds });
}
