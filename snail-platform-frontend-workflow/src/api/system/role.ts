/**
 * 角色数据（只读消费）：snail-function-system 提供，平台不维护。
 * 设计器候选组下拉数据源。
 */
import { http } from '@/utils/request';
import type { PageQueryParam, PageResult } from '@/types/api';

export interface RoleVO {
  roleId: number;
  roleCode: string;
  roleName: string;
  status?: number;
}

export function pageRoles(query: PageQueryParam & Record<string, unknown>): Promise<PageResult<RoleVO>> {
  return http<PageResult<RoleVO>>({ url: '/system/role/page', method: 'GET', params: query });
}
