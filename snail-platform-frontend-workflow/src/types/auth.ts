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
  /** 登录端坐标(租户管理增强 §5.1):本端固定 workflow;非法值后端 400「非法的登录端」 */
  scene?: string;
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

/** 租户五系统开通态(/me 实时值,不进 JWT/登录快照):对应开关 false 即本端登录被拒 */
export interface TenantFeatures {
  adminEnabled: boolean;
  workflowEnabled: boolean;
  mobileEnabled: boolean;
  dashboardEnabled: boolean;
  portalEnabled: boolean;
}

/** GET /system/auth/me 出参：身份 + 组织/租户名称 + 生效角色 + 租户五系统开通态 */
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
  /** 路由守卫的消费面;旧后端不下发时为 null(守卫视为不拦,向后兼容) */
  tenantFeatures: TenantFeatures | null;
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

/** POST /system/auth/login-sms 入参：手机号 + 短信码 + 登录端,不含租户（跨租户定位） */
export interface SmsLoginRequest {
  phone: string;
  smsCode: string;
  /** 登录端坐标:本端固定 workflow;多账号命中时由 pick 请求重传(v1.3 补口) */
  scene?: string;
}

/** POST /system/auth/login-sms/pick 入参：pickKey 一次性凭证 + 选定账号 + 登录端(pick 是独立二次调用,scene 须重传) */
export interface SmsPickRequest {
  pickKey: string;
  userId: number;
  scene?: string;
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
