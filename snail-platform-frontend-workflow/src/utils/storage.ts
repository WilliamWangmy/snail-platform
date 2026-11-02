/**
 * 本地存储统一出口：只允许本模块直接触碰 localStorage。
 */

export function getItem(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

export function setItem(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
  } catch {
    // 存储不可用（隐私模式等）时静默，登录态由 Pinia 内存兜底
  }
}

export function removeItem(key: string): void {
  try {
    localStorage.removeItem(key);
  } catch {
    // 同上
  }
}
