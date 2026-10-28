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

export function fetchCaptcha(): Promise<Captcha> {
  return get<Captcha>('/system/auth/captcha');
}

export function login(payload: LoginPayload): Promise<LoginResult> {
  return post<LoginResult>('/system/auth/login', payload);
}

export function fetchLoginUser(): Promise<LoginUser> {
  return get<LoginUser>('/system/auth/me');
}

/** 服务端登出:令牌版本自增,当前用户全部设备立即失效。 */
export function logout(): Promise<void> {
  return post<void>('/system/auth/logout');
}
