import { get } from './http';
import type { PageParams, PageResult } from './types';

/**
 * 第三方调用流水行:列表即全行,后端无详情端点(与登录日志同口径)。
 * request/response 是落库前已脱敏+截断的快照,前端原样透出不二次加工;
 * 流水是只增不改的账本,页面只读,无删除/导出动作。
 */
export interface ExternalCallLogVO {
  callId: number;
  /** 厂商标识:smtp-mail/mock-sms/dingtalk/feishu/wecom/mock-sso(各 client 自报) */
  vendor: string;
  /** 动作标识:send-mail/send-sms/geo-ip/oauth-token/oauth-userinfo 等 */
  action: string;
  /** 调用方自报标识:notify/auth/system,缺省 unknown */
  caller: string;
  /** 业务域标签,调用方自填,可为空 */
  bizType: string | null;
  /** 关联业务 ID(如公告消息 ID),排查时与 bizType 联用 */
  bizId: number | null;
  /** 请求快照(脱敏+截断),可为空 */
  request: string | null;
  /** 响应摘要(脱敏+截断),可为空 */
  response: string | null;
  /** 调用终态:0 成功 1 失败(注意与操作日志的 1 成功 0 失败相反) */
  status: number;
  /** 失败原因,截断;成功行为空 */
  error: string | null;
  /** 耗时毫秒 */
  costMs: number;
  /** 链路 ID,MDC 透传,无链路体系时为空 */
  traceId: string | null;
  /** 触发人用户 ID 快照,系统触发为空 */
  operatorId: number | null;
  /** 租户快照,平台级调用(如短信验证码)为空 */
  tenantId: number | null;
  /** 调用时间,落账线程显式写入 */
  createTime: string;
}

/** 流水分页查询契约:厂商/调用方精确匹配,终态 0/1,时间范围是 ISO 局部日期时间。 */
export interface ExternalCallQuery extends PageParams {
  vendor?: string;
  caller?: string;
  status?: number | null;
  beginTime?: string;
  endTime?: string;
}

/** 第三方调用流水分页,后端 ExternalCallController 镜像,平台面只读。 */
export function pageExternalCalls(query: ExternalCallQuery): Promise<PageResult<ExternalCallLogVO>> {
  return get<PageResult<ExternalCallLogVO>>('/platform/external/call-log', query);
}
