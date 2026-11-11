import { get, post } from '@/adapter/request';
import type { Captcha, LoginPayload, LoginResult } from './types';

/** 图形验证码:image 为 PNG data-uri,提交时回填 captchaKey。 */
export function fetchCaptcha(): Promise<Captcha> {
  return get<Captcha>('/system/auth/captcha');
}

export function login(payload: LoginPayload): Promise<LoginResult> {
  return post<LoginResult>('/system/auth/login', payload);
}

/** 服务端登出:令牌版本自增,当前用户全部设备立即失效。 */
export function logout(): Promise<void> {
  return post<void>('/system/auth/logout');
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

/** 发送短信验证码(与后端 60s 间隔闸对齐)。 */
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

/** login-sms 出参判别:响应含 pickKey 即多账号候选态。 */
export function isSmsAccountsResult(
  result: LoginResult | SmsAccountsResult,
): result is SmsAccountsResult {
  return typeof (result as SmsAccountsResult).pickKey === 'string';
}

/* ------------------------- 会话快照 ------------------------- */

export interface RoleBrief {
  roleId: number;
  roleCode: string;
  roleName: string;
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
}

/** 当前登录人富快照:冷启动恢复会话信息用。 */
export function fetchLoginUser(): Promise<LoginUser> {
  return get<LoginUser>('/system/auth/me');
}
