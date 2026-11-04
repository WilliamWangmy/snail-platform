/**
 * 登录认证：/system/auth 四个端点（AuthController 镜像）。
 */
import { http } from '@/utils/request';
import type {
  CaptchaVO,
  LoginAccountsVO,
  LoginRequest,
  LoginSuccessVO,
  LoginUserVO,
  SmsCaptchaRequest,
  SmsLoginRequest,
  SmsPickRequest,
} from '@/types/auth';

/** 获取图形验证码（图片为 data-uri，img 直接可用） */
export function getCaptcha(): Promise<CaptchaVO> {
  return http<CaptchaVO>({ url: '/system/auth/captcha', method: 'GET' });
}

/** 账号密码 + 验证码登录 */
export function login(data: LoginRequest): Promise<LoginSuccessVO> {
  return http<LoginSuccessVO>({ url: '/system/auth/login', method: 'POST', data });
}

/** 登出（当前用户全部设备立即失效） */
export function logout(): Promise<void> {
  return http<void>({ url: '/system/auth/logout', method: 'POST' });
}

/** 当前登录人（身份 + 组织/租户名称 + 生效角色） */
export function getMe(): Promise<LoginUserVO> {
  return http<LoginUserVO>({ url: '/system/auth/me', method: 'GET' });
}

/* ------------------------- 短信验证码登录 ------------------------- */

/** 发送短信验证码：人机闸（图形码）在先，发送成功后旧图形码即作废 */
export function sendSmsCaptcha(data: SmsCaptchaRequest): Promise<void> {
  return http<void>({ url: '/system/auth/sms-captcha', method: 'POST', data });
}

/** 短信验证码登录：单账号返回 LoginSuccessVO；多账号返回 LoginAccountsVO（pickKey 非空） */
export function loginBySms(data: SmsLoginRequest): Promise<LoginSuccessVO | LoginAccountsVO> {
  return http<LoginSuccessVO | LoginAccountsVO>({ url: '/system/auth/login-sms', method: 'POST', data });
}

/** 选定租户账号换令牌：pickKey 一次一用，失败需重走短信登录 */
export function pickSmsAccount(data: SmsPickRequest): Promise<LoginSuccessVO> {
  return http<LoginSuccessVO>({ url: '/system/auth/login-sms/pick', method: 'POST', data });
}

/** login-sms 出参判别：响应含 pickKey 即多账号候选态 */
export function isLoginAccounts(
  result: LoginSuccessVO | LoginAccountsVO,
): result is LoginAccountsVO {
  return typeof (result as LoginAccountsVO).pickKey === 'string';
}
