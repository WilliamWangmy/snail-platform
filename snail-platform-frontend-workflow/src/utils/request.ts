/**
 * axios 单实例 + 拦截器（骨架核心，全站唯一 HTTP 出口）。
 * 约定：
 * - 请求拦截器：注入 Authorization / X-Trace-Id；租户由后端从 JWT 解析，前端不显式传。
 * - 响应拦截器：业务代码永远拿到"剥壳后"的 data；
 *   code!==0 弹业务错误并 reject；401/未认证清登录态跳登录；5xx/网络错误统一提示并去重。
 */
import axios, { AxiosError, type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios';
import { ElMessage } from 'element-plus';

import { API_BASE, REQUEST_TIMEOUT, STORAGE_KEY_TOKEN } from '@/constants';
import { getItem, removeItem } from '@/utils/storage';

declare module 'axios' {
  export interface AxiosRequestConfig {
    /** 跳过错误提示（如静默探测类请求） */
    silent?: boolean;
  }
}

/** 后端业务码：未认证（以 GlobalExceptionHandler 实际映射为准，401 兜底） */
const CODE_UNAUTHORIZED = 401;

function createTraceId(): string {
  return crypto.randomUUID().replace(/-/g, '');
}

const request = axios.create({
  baseURL: API_BASE,
  timeout: REQUEST_TIMEOUT,
});

request.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getItem(STORAGE_KEY_TOKEN);
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  config.headers['X-Trace-Id'] = createTraceId();
  return config;
});

// 同一时刻重复错误只弹一次
let errorMessageVisible = false;

function showError(message: string): void {
  if (errorMessageVisible) return;
  errorMessageVisible = true;
  ElMessage.error(message);
  setTimeout(() => {
    errorMessageVisible = false;
  }, 1500);
}

/** 清空登录态并跳登录页（携带回跳地址） */
function redirectToLogin(): void {
  removeItem(STORAGE_KEY_TOKEN);
  const { pathname, search } = window.location;
  if (!pathname.startsWith('/login')) {
    const redirect = encodeURIComponent(pathname + search);
    window.location.href = `/login?redirect=${redirect}`;
  }
}

request.interceptors.response.use(
  (response) => {
    const payload = response.data as { code?: number; message?: string; data?: unknown };
    if (typeof payload?.code !== 'number') {
      // 非平台信封（如文件流），原样透出
      return response;
    }
    if (payload.code === 0) {
      // 统一剥壳：调用方直接拿到 data
      return payload.data as unknown as typeof response;
    }
    if (payload.code === CODE_UNAUTHORIZED) {
      redirectToLogin();
    } else if (!response.config.silent) {
      showError(payload.message || '操作失败');
    }
    return Promise.reject(new Error(payload.message || '操作失败'));
  },
  (error: AxiosError) => {
    if (error.response?.status === 401) {
      redirectToLogin();
      return Promise.reject(error);
    }
    if (!error.config?.silent) {
      showError(error.response ? '服务异常，请稍后重试' : '网络异常，请检查连接');
    }
    return Promise.reject(error);
  },
);

/** 发起请求：返回值即后端 data（剥壳后），泛型 T 为业务数据类型 */
export function http<T>(config: AxiosRequestConfig): Promise<T> {
  // 拦截器已在响应侧剥壳，运行时 data 即业务数据；此处断言抹平 axios 的信封类型
  return request.request(config) as unknown as Promise<T>;
}

export default request;
