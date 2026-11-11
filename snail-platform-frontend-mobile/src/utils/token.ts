/** 令牌存取:uni 各端统一的 storage 封装(小程序/App/H5 同名 API)。 */
const TOKEN_KEY = 'snail_mobile_token_v1';

export function getToken(): string {
  return (uni.getStorageSync(TOKEN_KEY) as string) || '';
}

export function setToken(token: string): void {
  uni.setStorageSync(TOKEN_KEY, token);
}

export function clearToken(): void {
  uni.removeStorageSync(TOKEN_KEY);
}
