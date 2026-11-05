/**
 * 与后端 snail-function-auth 的 DTO 镜像（LoginRequest / LoginSuccessVO / LoginUserVO / CaptchaVO）。
 */

/** GET /system/auth/captcha 出参：图片为 data-uri，img 直接可用 */
export interface CaptchaVO {
  captchaKey: string;
  /** data:image/png;base64,... */
  image: string;
}

/** POST /system/auth/login 入参 */
export interface LoginRequest {
  username: string;
  password: string;
  captchaKey: string;
  captchaCode: string;
}

/** POST /system/auth/login 出参 */
export interface LoginSuccessVO {
  token: string;
  tokenType: string;
  userId: number;
  username: string;
  nickname: string;
  tenantId: number;
  tenantName: string;
}

/** GET /system/auth/me 出参：身份 + 组织/租户名称 + 生效角色 */
export interface LoginUserVO {
  userId: number;
  username: string;
  nickname: string;
  tenantId: number;
  tenantName: string;
  orgId: number;
  orgName: string;
  roles: RoleInfo[];
  permissions: string[];
}

export interface RoleInfo {
  roleId: number;
  roleCode: string;
  roleName: string;
}

/* ------------------------- 短信验证码登录 ------------------------- */

/** POST /system/auth/sms-captcha 入参：手机号 + 图形验证码（发送前人机闸） */
export interface SmsCaptchaRequest {
  phone: string;
  captchaKey: string;
  captchaCode: string;
}

/** POST /system/auth/login-sms 入参：手机号 + 短信码，不含租户（跨租户定位） */
export interface SmsLoginRequest {
  phone: string;
  smsCode: string;
}

/** POST /system/auth/login-sms/pick 入参：pickKey 一次性凭证 + 选定账号 */
export interface SmsPickRequest {
  pickKey: string;
  userId: number;
}

/** 多命中候选账号：最小暴露面，只有租户名/登录名/昵称 */
export interface SmsAccountOption {
  userId: number;
  tenantId: number;
  tenantName: string;
  username: string;
  nickname: string;
}

/** login-sms 多命中态：pickKey 2 分钟一次性凭证 + 可选账号集（按用户 ID 升序） */
export interface LoginAccountsVO {
  pickKey: string;
  accounts: SmsAccountOption[];
}

/* ------------------------- 第三方扫码登录 ------------------------- */

/** 第三方扫码来源编码，与后端 login_type 同口径 */
export type OAuthSource = 'dingtalk' | 'feishu' | 'wecom';

/** GET /system/auth/oauth/authorize 出参：厂商授权页地址 + 一次性 state */
export interface OAuthAuthorizeVO {
  authorizeUrl: string;
  state: string;
}

/** POST /system/auth/oauth/login 入参：state 重定向流必带，免登态缺省 */
export interface OAuthLoginRequest {
  source: OAuthSource;
  code: string;
  state?: string;
  redirectUri?: string;
}
