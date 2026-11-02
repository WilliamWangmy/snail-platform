/**
 * 登录认证：/system/auth 四个端点（AuthController 镜像）。
 */
import { http } from '@/utils/request';
import type { CaptchaVO, LoginRequest, LoginSuccessVO, LoginUserVO } from '@/types/auth';

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
