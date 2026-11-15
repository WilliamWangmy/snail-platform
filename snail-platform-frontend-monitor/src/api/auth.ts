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

/** 租户五系统开通态(/me 实时值):顶栏跨端入口按此过滤;监控端不设第六开关。 */
export interface TenantFeatures {
  adminEnabled: boolean;
  workflowEnabled: boolean;
  mobileEnabled: boolean;
  dashboardEnabled: boolean;
  portalEnabled: boolean;
}

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

/** 监控端登录:scene=monitor 显式自报;非平台租户后端 400「监控系统仅限平台管理员使用」。 */
export function login(payload: LoginPayload): Promise<LoginResult> {
  return post<LoginResult>('/system/auth/login', { ...payload, scene: 'monitor' });
}

export function fetchLoginUser(): Promise<LoginUser> {
  return get<LoginUser>('/system/auth/me');
}

/** 跨端免登签到:请求体固定 scene=monitor;失败(非平台租户)透传后端 message。 */
export function ssoCheckIn(): Promise<LoginResult> {
  return post<LoginResult>('/system/auth/sso', { scene: 'monitor' });
}

export function logout(): Promise<void> {
  return post<void>('/system/auth/logout');
}
