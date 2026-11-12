import { get, post } from './http';

/** 图形验证码:image 为 PNG data-uri,提交时回填 captchaKey,一次一用。 */
export interface Captcha {
  captchaKey: string;
  image: string;
}

export interface LoginPayload {
  username: string;
  password: string;
  captchaKey: string;
  captchaCode: string;
  /** 登录端坐标(租户管理增强 §5.1):本端固定 dashboard;非法值后端 400「非法的登录端」 */
  scene?: string;
}

export interface LoginResult {
  token: string;
  tokenType: string;
  expiresIn: number;
  userId: number;
  username: string;
  nickname: string;
  tenantId: number;
  tenantName: string;
  /** 大屏无改密页,该信号仅透传不消费(强制改密引导在管理端) */
  passwordChangeRequired: boolean;
}

export interface SmsCaptchaPayload {
  phone: string;
  captchaKey: string;
  captchaCode: string;
}

export interface SmsLoginPayload {
  phone: string;
  smsCode: string;
  /** 登录端坐标:本端固定 dashboard;多账号命中时由 pick 请求重传(v1.3 补口) */
  scene?: string;
}

export interface SmsPickPayload {
  pickKey: string;
  userId: number;
  /** 登录端坐标:pick 是独立二次调用,scene 须重传(v1.3 补口) */
  scene?: string;
}

/** 多命中候选账号:最小暴露面,只有租户名/登录名/昵称(与后端 LoginAccountsVO.Account 同口径)。 */
export interface SmsAccountOption {
  userId: number;
  tenantId: number;
  tenantName: string;
  username: string;
  nickname: string;
}

/** login-sms 多命中态:pickKey 2 分钟一次性凭证 + 可选账号集(按用户 ID 升序)。 */
export interface SmsAccountsResult {
  pickKey: string;
  accounts: SmsAccountOption[];
}

/** 租户五系统开通态(/me 实时值,不进 JWT/登录快照):dashboardEnabled=false 即本端登录被顶栏守卫拦下 */
export interface TenantFeatures {
  adminEnabled: boolean;
  workflowEnabled: boolean;
  mobileEnabled: boolean;
  dashboardEnabled: boolean;
  portalEnabled: boolean;
}

/** 当前登录人:顶栏账号区只取展示所需字段(与后端 LoginUserVO 同口径,权限码大屏不消费)。 */
export interface Me {
  userId: number;
  username: string;
  nickname: string;
  tenantId: number;
  tenantName: string;
  orgId: number;
  orgName: string;
  roles: { roleId: number; roleCode: string; roleName: string }[];
  permissions: string[];
  passwordChangeRequired: boolean;
  /** 顶栏守卫消费面;旧后端不下发时缺失(守卫视为不拦,向后兼容) */
  tenantFeatures?: TenantFeatures;
}

export function fetchCaptcha(): Promise<Captcha> {
  return get<Captcha>('/system/auth/captcha');
}

export function login(payload: LoginPayload): Promise<LoginResult> {
  return post<LoginResult>('/system/auth/login', payload);
}

/** 短信码已消耗,验证码缓存已核销(GETDEL 语义),发送成功即换新图形码。 */
export function sendSmsCaptcha(payload: SmsCaptchaPayload): Promise<void> {
  return post<void>('/system/auth/sms-captcha', payload);
}

/**
 * 短信验证码登录,出参两态:
 * 单账号直接返回 LoginResult;多账号返回 SmsAccountsResult(pickKey 非空),需再调 pick。
 */
export function loginBySms(payload: SmsLoginPayload): Promise<LoginResult | SmsAccountsResult> {
  return post<LoginResult | SmsAccountsResult>('/system/auth/login-sms', payload);
}

/** 选定租户账号换正式令牌:pickKey 一次一用,失败需从头重走短信登录。 */
export function pickSmsAccount(payload: SmsPickPayload): Promise<LoginResult> {
  return post<LoginResult>('/system/auth/login-sms/pick', payload);
}

/** 服务端登出:令牌版本自增,当前用户全部设备立即失效。 */
export function logout(): Promise<void> {
  return post<void>('/system/auth/logout');
}

/** 当前登录人:令牌失效时 http 层 401 直接踢回登录页,因此该调用同时充当会话有效性校验。 */
export function fetchMe(): Promise<Me> {
  return get<Me>('/system/auth/me');
}

/**
 * 单点跳转签到:管理端/流程端以 ssotoken 共用会话免登跳转,落地后上报本端坐标,
 * 后端按 scene 复核租户开通态并补一条 login_type=sso 的登录日志(此前共用会话跳转在日志里无痕)。
 * 签到失败不影响进入大屏——日志/统计链路故障不该反压业务,调用方静默兜底。
 */
export function ssoCheckIn(): Promise<void> {
  return post<void>('/system/auth/sso', { scene: 'dashboard' });
}

/** login-sms 出参判别:响应含 pickKey 即多账号候选态。 */
export function isSmsAccountsResult(
  result: LoginResult | SmsAccountsResult,
): result is SmsAccountsResult {
  return typeof (result as SmsAccountsResult).pickKey === 'string';
}
