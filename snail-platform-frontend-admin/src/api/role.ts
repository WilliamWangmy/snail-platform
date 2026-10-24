import { del, get, post, put } from './http';
import type { PageParams, PageResult } from './types';

export interface RoleVO {
  roleId: number;
  roleName: string;
  roleCode: string;
  /** 1 全部 2 本组织 3 本组织及以下 4 本人 */
  dataScope: number;
  isSystem: number;
  sort: number;
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

export interface RoleUpdatePayload {
  roleName: string;
  dataScope: number;
  sort?: number;
  status?: number;
  remark?: string | null;
}

/** 授权树节点:checked 由后端算好,scope=1 节点无平台授权码时不可勾选。 */
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

export function pageRoles(query: RoleQuery): Promise<PageResult<RoleVO>> {
  return get<PageResult<RoleVO>>('/system/role/page', query);
}

export function createRole(payload: RoleCreatePayload): Promise<number> {
  return post<number>('/system/role', payload);
}

export function updateRole(roleId: number, payload: RoleUpdatePayload): Promise<void> {
  return put<void>(`/system/role/${roleId}`, payload);
}

export function changeRoleStatus(roleId: number, status: number): Promise<void> {
  return put<void>(`/system/role/${roleId}/status`, undefined, { status });
}

export function deleteRole(roleId: number): Promise<void> {
  return del<void>(`/system/role/${roleId}`);
}

export function fetchRoleMenus(roleId: number): Promise<RoleMenuNode[]> {
  return get<RoleMenuNode[]>(`/system/role/${roleId}/menus`);
}

export function assignRoleMenus(roleId: number, menuIds: number[]): Promise<void> {
  return put<void>(`/system/role/${roleId}/menus`, { menuIds });
}
