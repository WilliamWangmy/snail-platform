import { get, post } from './http';

/** 验证码:image 为 PNG data-uri,提交时回填 captchaKey。 */
export interface Captcha {
  captchaKey: string;
  image: string;
}

export interface LoginPayload {
  username: string;
  password: string;
  captchaKey: string;
  captchaCode: string;
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
  passwordChangeRequired: boolean;
}

export interface RoleBrief {
  roleId: number;
  roleCode: string;
  roleName: string;
}

/** 租户五系统开通态(/me 实时值,不进 JWT/登录快照):顶栏系统切换按此过滤入口,旧后端不下发时视为全量开通 */
export interface TenantFeatures {
  adminEnabled: boolean;
  workflowEnabled: boolean;
  mobileEnabled: boolean;
  dashboardEnabled: boolean;
  portalEnabled: boolean;
}

/** 当前登录人富快照:身份 + 组织/角色/租户名称 + 生效权限码。 */
export interface LoginUser {
  userId: number;
  username: string;
  nickname: string;
  tenantId: number;
  tenantName: string;
  orgId: number | null;
  orgName: string | null;
  roles: RoleBrief[];
  permissions: string[];
  passwordChangeRequired: boolean;
  tenantFeatures: TenantFeatures | null;
}

export function fetchCaptcha(): Promise<Captcha> {
  return get<Captcha>('/system/auth/captcha');
}

export function login(payload: LoginPayload): Promise<LoginResult> {
  // 登录端坐标显式自报:后端缺省也是 admin,显式传不依赖缺省口径,分端统计不受后端契约变动影响
  return post<LoginResult>('/system/auth/login', { ...payload, scene: 'admin' });
}

/* ------------------------- 短信验证码登录 ------------------------- */

export interface SmsCaptchaPayload {
  phone: string;
  captchaKey: string;
  captchaCode: string;
}

export interface SmsLoginPayload {
  phone: string;
  smsCode: string;
}

export interface SmsPickPayload {
  pickKey: string;
  userId: number;
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

/** 短信码已消耗,验证码缓存已核销(验证码是 GETDEL 语义,发送成功即换新)。 */
export function sendSmsCaptcha(payload: SmsCaptchaPayload): Promise<void> {
  return post<void>('/system/auth/sms-captcha', payload);
}

/**
 * 短信验证码登录,出参两态:
 * 单账号直接返回 LoginResult;多账号返回 SmsAccountsResult(pickKey 非空),需再调 pick。
 */
export function loginBySms(payload: SmsLoginPayload): Promise<LoginResult | SmsAccountsResult> {
  return post<LoginResult | SmsAccountsResult>('/system/auth/login-sms', { ...payload, scene: 'admin' });
}

/** 选定租户账号换正式令牌:pickKey 一次一用,失败需从头重走短信登录。 */
export function pickSmsAccount(payload: SmsPickPayload): Promise<LoginResult> {
  // pick 是独立二次调用,scene 与 login-sms 保持一致(后端 v1.3 口径)
  return post<LoginResult>('/system/auth/login-sms/pick', { ...payload, scene: 'admin' });
}

/** login-sms 出参判别:响应含 pickKey 即多账号候选态。 */
export function isSmsAccountsResult(
  result: LoginResult | SmsAccountsResult,
): result is SmsAccountsResult {
  return typeof (result as SmsAccountsResult).pickKey === 'string';
}

export function fetchLoginUser(): Promise<LoginUser> {
  return get<LoginUser>('/system/auth/me');
}

/** 服务端登出:令牌版本自增,当前用户全部设备立即失效。 */
export function logout(): Promise<void> {
  return post<void>('/system/auth/logout');
}

/* ------------------------- 第三方扫码登录 ------------------------- */

/** 第三方扫码来源编码,与后端 login_type 同口径。 */
export type OAuthSource = 'dingtalk' | 'feishu' | 'wecom';

/** 第三方登录跳转前的站内目标暂存键:发起时写入,回调页消费后清除。 */
export const OAUTH_REDIRECT_KEY = 'snail_admin_oauth_redirect_v1';

/** 发起授权出参:厂商授权页地址 + 一次性 state(回调时随 code 原样带回)。 */
export interface OAuthAuthorize {
  authorizeUrl: string;
  state: string;
}

export interface OAuthLoginPayload {
  source: OAuthSource;
  code: string;
  /** 重定向流必带;免登态缺省(二期)。 */
  state?: string;
  /** 前端回调地址回显,state 存在时须与服务端登记一致。 */
  redirectUri?: string;
}

/** 开放的第三方来源;空数组即登录页隐藏第三方入口行。 */
export function fetchOAuthOptions(): Promise<OAuthSource[]> {
  return get<string[]>('/system/auth/oauth/options') as Promise<OAuthSource[]>;
}

/** 拿厂商授权页地址:redirectUri 须与来源白名单精确匹配,否则报"回调地址未登记"。 */
export function fetchOAuthAuthorize(source: OAuthSource, redirectUri: string): Promise<OAuthAuthorize> {
  return get<OAuthAuthorize>('/system/auth/oauth/authorize', { source, redirectUri });
}

/**
 * 第三方授权码登录,出参两态(与 login-sms 同构):
 * 单账号直接返回 LoginResult;多账号返回 SmsAccountsResult(pickKey 非空),需再调 pick。
 */
export function loginByOauth(payload: OAuthLoginPayload): Promise<LoginResult | SmsAccountsResult> {
  return post<LoginResult | SmsAccountsResult>('/system/auth/oauth/login', payload);
}

/** 选定租户账号换正式令牌:pickKey 一次一用,失败须重走扫码。 */
export function pickOauthAccount(payload: SmsPickPayload): Promise<LoginResult> {
  return post<LoginResult>('/system/auth/oauth/pick', payload);
}
