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

/** login-sms 出参判别:响应含 pickKey 即多账号候选态。 */
export function isSmsAccountsResult(
  result: LoginResult | SmsAccountsResult,
): result is SmsAccountsResult {
  return typeof (result as SmsAccountsResult).pickKey === 'string';
}
