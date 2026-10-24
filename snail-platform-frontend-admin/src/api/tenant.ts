import { get, post, put } from './http';
import type { PageParams, PageResult } from './types';

export interface TenantVO {
  tenantId: number;
  tenantCode: string;
  name: string;
  contactUser: string | null;
  contactPhone: string | null;
  expireTime: string | null;
  accountLimit: number | null;
  status: number;
  remark: string | null;
  createTime: string;
}

export interface TenantQuery extends PageParams {
  name?: string;
  status?: number | null;
}

/** 开通租户同事务复制根组织/内置角色/管理员模板,故要带管理员口令。 */
export interface TenantCreatePayload {
  tenantCode: string;
  name: string;
  contactUser?: string | null;
  contactPhone?: string | null;
  expireTime?: string | null;
  accountLimit?: number | null;
  remark?: string | null;
  adminUsername: string;
  adminPassword: string;
}

export interface TenantUpdatePayload {
  name: string;
  contactUser?: string | null;
  contactPhone?: string | null;
  expireTime?: string | null;
  accountLimit?: number | null;
  status?: number;
  remark?: string | null;
}

export function pageTenants(query: TenantQuery): Promise<PageResult<TenantVO>> {
  return get<PageResult<TenantVO>>('/system/tenant/page', query);
}

export function createTenant(payload: TenantCreatePayload): Promise<number> {
  return post<number>('/system/tenant', payload);
}

export function updateTenant(tenantId: number, payload: TenantUpdatePayload): Promise<void> {
  return put<void>(`/system/tenant/${tenantId}`, payload);
}

export function changeTenantStatus(tenantId: number, status: number): Promise<void> {
  return put<void>(`/system/tenant/${tenantId}/status`, undefined, { status });
}
