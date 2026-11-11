import { get, post, put } from './http';
import type { PageParams, PageResult } from './types';

/** 租户行:平台面列表、详情与修改回显共用。五系统开关与存储配额随行(租户管理增强 §4)。 */
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
  /** 存储配额 MB;null 为不限;展示层按 GB(MB÷1024)换算 */
  storageLimitMb: number | null;
  /** 管理系统开通:0 未开通 1 已开通;未开通则登录管理系统被拒 */
  adminEnabled: number;
  /** 流程系统开通:0 未开通 1 已开通 */
  workflowEnabled: number;
  /** 移动端(app 与小程序)开通:0 未开通 1 已开通 */
  mobileEnabled: number;
  /** 数据大屏开通:0 未开通 1 已开通 */
  dashboardEnabled: number;
  /** 企业门户开通:0 未开通 1 已开通;门户立项前为配置先行 */
  portalEnabled: number;
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

/** 五系统开通开关的表单口径:boolean 与后端 0/1 的换算收口在页面层提交处 */
export interface TenantFeatureFlags {
  /** 管理系统(scene=admin) */
  adminEnabled: boolean;
  /** 流程系统(scene=workflow) */
  workflowEnabled: boolean;
  /** 移动端(app 与小程序共用一开关) */
  mobileEnabled: boolean;
  /** 数据大屏(scene=dashboard) */
  dashboardEnabled: boolean;
  /** 企业门户(门户立项前为配置先行) */
  portalEnabled: boolean;
}

/** 开关的后端载荷口径:与表列 Integer 0/1 逐字对应;表单 boolean 的换算收口在页面层提交处 */
export type FeatureBit = 0 | 1;

/** 开通/修改载荷共用的五开关段:0 未开通 1 已开通 */
export interface TenantFeatureBits {
  /** 管理系统(scene=admin) */
  adminEnabled: FeatureBit;
  /** 流程系统(scene=workflow) */
  workflowEnabled: FeatureBit;
  /** 移动端(app 与小程序共用一开关) */
  mobileEnabled: FeatureBit;
  /** 数据大屏(scene=dashboard) */
  dashboardEnabled: FeatureBit;
  /** 企业门户(门户立项前为配置先行) */
  portalEnabled: FeatureBit;
}

/** 开通租户同事务复制根组织/内置角色/管理员模板;管理员口令由平台管理员自输。 */
export interface TenantCreatePayload extends TenantFeatureBits {
  /** 全局唯一、建后不可改:小写字母数字与点/下划线/连字符,首尾须是字母或数字 */
  tenantCode: string;
  name: string;
  contactUser?: string | null;
  contactPhone?: string | null;
  expireTime?: string | null;
  accountLimit?: number | null;
  /** 存储配额 MB,null 为不限;前端 GB×1024 换算提交 */
  storageLimitMb?: number | null;
  remark?: string | null;
  /** 全站唯一(登录不选租户),重名直接业务拒绝 */
  adminUsername: string;
  /** 首任管理员初始口令(P2-3):平台管理员自输,走 HTTPS 请求体;服务端按 PasswordPolicy 校验 */
  adminPassword: string;
}

/** 开通出参:只有新租户ID,响应不再回传口令 */
export interface TenantCreated {
  tenantId: number;
}

/** 修改是整份覆盖:除 name 外全部可清空,留空即清库;status 不传即不动。开关不传按关闭落库。 */
export interface TenantUpdatePayload extends TenantFeatureBits {
  name: string;
  contactUser?: string | null;
  contactPhone?: string | null;
  expireTime?: string | null;
  accountLimit?: number | null;
  /** 存储配额 MB,null 为不限;前端 GB×1024 换算提交 */
  storageLimitMb?: number | null;
  status?: number;
  remark?: string | null;
}

/** 用户区:租户内用户五项计数(usage-stats 契约 §5.4) */
export interface UsageStatsUsers {
  total: number;
  enabled: number;
  disabled: number;
  used: number;
  online: number;
}

/** 单端登录统计:loggedIn=去重登录用户数;rate=loggedIn/total 两位小数 */
export interface UsageSceneStat {
  loggedIn: number;
  rate: number;
}

/** 企业门户访问统计:门户立项前恒 null,前端渲染占位 */
export interface UsagePortalStat {
  visitors: number | null;
  ipCount: number | null;
}

/** 分端区:五端统计(mobile 为 app/miniapp 合并去重) */
export interface UsageStatsScenes {
  admin: UsageSceneStat;
  workflow: UsageSceneStat;
  mobile: UsageSceneStat;
  dashboard: UsageSceneStat;
  portal: UsagePortalStat;
}

/** 租户使用统计出参(usage-stats 接口) */
export interface UsageStatsVO {
  users: UsageStatsUsers;
  scenes: UsageStatsScenes;
}

/** 租户存储用量出参(storage-usage 接口):剩余与使用率由前端按 limitMb/usedBytes 计算 */
export interface StorageUsageVO {
  /** 存储配额 MB,null 为不限(前端显示"不限",使用率与剩余不渲染) */
  limitMb: number | null;
  usedBytes: number;
  fileCount: number;
}

/** 平台面跨租户检索(sys_tenant 在租户拦截 ignore 清单),新租户排在最前。 */
export function pageTenants(query: TenantQuery): Promise<PageResult<TenantVO>> {
  return get<PageResult<TenantVO>>('/system/tenant/page', query);
}

/** 租户详情(五系统开关/存储配额随行):详情抽屉与修改回显共用,权限同列表读面 */
export function getTenantDetail(tenantId: number): Promise<TenantVO> {
  return get<TenantVO>(`/system/tenant/${tenantId}`);
}

/** 租户使用统计:用户五项计数+五端登录/使用率;门户区 null 占位 */
export function getTenantUsageStats(tenantId: number): Promise<UsageStatsVO> {
  return get<UsageStatsVO>(`/system/tenant/${tenantId}/usage-stats`);
}

/** 租户存储用量:总配额/已用字节数/文件数,与上传配额校验同口径 */
export function getTenantStorageUsage(tenantId: number): Promise<StorageUsageVO> {
  return get<StorageUsageVO>(`/platform/file/tenant-usage/${tenantId}`);
}

/** 管理员登录名占用查询(全站口径,含已删行);失焦即查用,保存时后端仍会再拦一次防并发抢占 */
export function checkAdminUsername(username: string): Promise<boolean> {
  return get<boolean>('/system/tenant/admin-username-exists', { username });
}

/** 开通:租户标识或管理员登录名占用即整单业务拒绝,模板复制与租户行同事务,失败不留半个租户 */
export function createTenant(payload: TenantCreatePayload): Promise<TenantCreated> {
  return post<TenantCreated>('/system/tenant', payload);
}

/** 修改:每次提交都会整体刷新登录快照缓存;只在"转为停用/到期变更/开关收回"时作废该租户存量令牌 */
export function updateTenant(tenantId: number, payload: TenantUpdatePayload): Promise<void> {
  return put<void>(`/system/tenant/${tenantId}`, payload);
}

/** 停用会立即作废该租户全部用户的令牌,重新启用不复活——他们必须重新登录。 */
export function changeTenantStatus(tenantId: number, status: number): Promise<void> {
  return put<void>(`/system/tenant/${tenantId}/status`, undefined, { status });
}
