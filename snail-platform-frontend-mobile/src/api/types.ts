/**
 * 后端统一响应信封(与 snail-platform-backend 对齐,见 admin/src/api/http.ts)。
 * code === 0 表示成功;400 校验 / 401 未认证 / 403 无权限 / 500 服务端。
 */
export interface Envelope<T> {
  code: number;
  message: string;
  data: T;
}

/** 分页信封(对齐 snail-framework-core 的 PageResult)。 */
export interface PageResult<T> {
  list: T[];
  total: number;
}

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
