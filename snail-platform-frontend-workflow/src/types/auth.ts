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
