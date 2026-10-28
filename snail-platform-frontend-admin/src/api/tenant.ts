import { get, post, put } from './http';
import type { PageParams, PageResult } from './types';

/** 租户行:平台面列表与详情共用。存储配额(storage_limit_mb)不在此列,租户接口既读不到也改不了。 */
export interface TenantVO {
  tenantId: number;
  /** 建后不可改;唯一键不含 deleted,销户租户仍终身占用 */
  tenantCode: string;
  name: string;
  contactUser: string | null;
  contactPhone: string | null;
  /** 到期时间,ISO 字符串;null 为不限 */
  expireTime: string | null;
  /** 账号数上限;null 为不限,0 不是不限 */
  accountLimit: number | null;
  /** 0 正常 1 停用 */
  status: number;
  remark: string | null;
  createTime: string;
}

/** 列表条件:name 模糊匹配,空白后端按不限处理;status 不传即不限。 */
export interface TenantQuery extends PageParams {
  name?: string;
  status?: number | null;
}

/** 开通租户同事务复制根组织/内置角色/管理员模板,故要带管理员口令。 */
export interface TenantCreatePayload {
  /** 全局唯一、建后不可改:小写字母数字与点/下划线/连字符,首尾须是字母或数字 */
  tenantCode: string;
  name: string;
  contactUser?: string | null;
  contactPhone?: string | null;
  expireTime?: string | null;
  accountLimit?: number | null;
  remark?: string | null;
  /** 全站唯一(登录不选租户),重名直接业务拒绝 */
  adminUsername: string;
  /** 8-64 位,后端过密码强度校验后 BCrypt 落库 */
  adminPassword: string;
}

/** 修改是整份覆盖:除 name 外全部可清空,留空即清库;status 不传即不动。 */
export interface TenantUpdatePayload {
  name: string;
  contactUser?: string | null;
  contactPhone?: string | null;
  expireTime?: string | null;
  accountLimit?: number | null;
  status?: number;
  remark?: string | null;
}

/** 平台面跨租户检索(sys_tenant 在租户拦截 ignore 清单),新租户排在最前。 */
export function pageTenants(query: TenantQuery): Promise<PageResult<TenantVO>> {
  return get<PageResult<TenantVO>>('/system/tenant/page', query);
}

/** 开通:租户标识或管理员登录名占用即整单业务拒绝,模板复制与租户行同事务,失败不留半个租户。 */
export function createTenant(payload: TenantCreatePayload): Promise<number> {
  return post<number>('/system/tenant', payload);
}

/** 修改:每次提交都会整体刷新登录快照缓存;只在"转为停用"或"到期时间变更"时作废该租户存量令牌。 */
export function updateTenant(tenantId: number, payload: TenantUpdatePayload): Promise<void> {
  return put<void>(`/system/tenant/${tenantId}`, payload);
}

/** 停用会立即作废该租户全部用户的令牌,重新启用不复活——他们必须重新登录。 */
export function changeTenantStatus(tenantId: number, status: number): Promise<void> {
  return put<void>(`/system/tenant/${tenantId}/status`, undefined, { status });
}
