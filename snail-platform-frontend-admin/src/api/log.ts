import { download, get } from './http';
import type { PageParams, PageResult } from './types';

/** 列表行:param/result 不在这里,全文只在详情端点。 */
export interface OperLogListVO {
  id: number;
  /** 归属租户;跨租户看的前提是持 platform:operlog:all */
  tenantId: number;
  module: string;
  /** 1 新增 2 修改 3 删除 4 导出 5 导入 9 其他 */
  businessType: number;
  method: string;
  requestMethod: string;
  /** 操作人登录名快照:账号删除后日志仍可读 */
  operator: string;
  orgId: number | null;
  ip: string | null;
  url: string;
  /** 1 成功 0 失败 */
  status: number;
  errorMsg: string | null;
  costTime: number | null;
  /** 与应用日志互查的链路 ID */
  traceId: string | null;
  operTime: string;
}

/** 详情补的两段全文:param 落库前已脱敏,导出行不含它们。 */
export interface OperLogDetailVO extends OperLogListVO {
  param: string | null;
  result: string | null;
}

/** tenantId 只对持 platform:operlog:all 者生效,其余租户由后端钉死本租户;时间是 ISO 局部日期时间。 */
export interface OperLogQuery extends PageParams {
  tenantId?: number | null;
  module?: string;
  businessType?: number | null;
  operator?: string;
  status?: number | null;
  beginTime?: string;
  endTime?: string;
}

/** 登录日志行:表体没有大字段,列表即全行。 */
export interface LoginLogVO {
  id: number;
  /** 0=登录链未定位到用户就失败(验证码先行/锁定/账号不存在),租户侧看不到这类行 */
  tenantId: number;
  username: string;
  /** 成功时回填;失败行常为 null */
  userId: number | null;
  ip: string | null;
  browser: string | null;
  os: string | null;
  /** 1 成功 0 失败 */
  status: number;
  msg: string | null;
  loginTime: string;
}

/** 同上:tenantId 仅平台侧生效;username 与 ip 是模糊匹配。 */
export interface LoginLogQuery extends PageParams {
  tenantId?: number | null;
  username?: string;
  ip?: string;
  status?: number | null;
  beginTime?: string;
  endTime?: string;
}

/** 操作日志分页,新记录恒在顶部;org 维度不提供筛选。 */
export function pageOperLogs(query: OperLogQuery): Promise<PageResult<OperLogListVO>> {
  return get<PageResult<OperLogListVO>>('/system/log/oper/page', query);
}

/** 详情:非平台侧取别的租户的行按"日志不存在"处理,存在性本身算租户隐私。 */
export function getOperLog(id: number): Promise<OperLogDetailVO> {
  return get<OperLogDetailVO>(`/system/log/oper/${id}`);
}

/** 导出超 5 万行整体拒绝(不是截断),服务端会回业务失败信封,download 按错误提示。 */
export function exportOperLogs(query: OperLogQuery): Promise<void> {
  return download('/system/log/oper/export', query, '操作日志.xlsx');
}

/** 登录日志分页:写路径在 auth 登录链,这里只读。 */
export function pageLoginLogs(query: LoginLogQuery): Promise<PageResult<LoginLogVO>> {
  return get<PageResult<LoginLogVO>>('/system/log/login/page', query);
}

/** 导出登录日志,行数上限口径与操作日志一致。 */
export function exportLoginLogs(query: LoginLogQuery): Promise<void> {
  return download('/system/log/login/export', query, '登录日志.xlsx');
}
