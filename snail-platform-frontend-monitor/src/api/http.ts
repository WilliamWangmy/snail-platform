import axios, { AxiosError, type AxiosResponse } from 'axios';
import { ElMessage } from 'element-plus';
import type { Envelope } from './types';
import { clearToken, getToken } from '@/utils/token';

/** 业务失败统一异常:code 为后端信封码,400 校验/401 未认证/403 无权限/500 服务端。 */
export class ApiError extends Error {
  readonly code: number;

  constructor(code: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
  }
}

/** 全端唯一 axios 出口:单实例、Bearer 注入、信封剥壳、401 清会话跳登录(不 resetMenuRoutes——静态路由)。 */
const http = axios.create({ timeout: 20000 });

http.interceptors.request.use((config) => {
  const token = getToken();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

http.interceptors.response.use(
  (response: AxiosResponse) => {
    if (response.config.responseType === 'blob') return response;
    const body = response.data as Envelope<unknown>;
    if (body.code !== 0) {
      report(body.code, body.message);
      return Promise.reject(new ApiError(body.code, body.message));
    }
    return response;
  },
  async (error: AxiosError<Envelope<unknown>>) => {
    const body = await envelopeOf(error);
    if (body && typeof body.code === 'number') {
      report(body.code, body.message);
      return Promise.reject(new ApiError(body.code, body.message));
    }
    ElMessage.error('网络异常，请稍后重试');
    return Promise.reject(new ApiError(-1, '网络异常，请稍后重试'));
  },
);

/** 取错误响应里的信封;blob(下载)响应体也先读出来,不吞服务端拒绝理由。 */
async function envelopeOf(error: AxiosError<Envelope<unknown>>): Promise<Envelope<unknown> | undefined> {
  const data = error.response?.data;
  if (data instanceof Blob) {
    try {
      return JSON.parse(await data.text()) as Envelope<unknown>;
    } catch {
      return undefined;
    }
  }
  return data;
}

/** 并发多个请求同时 401 时只提示与跳转一次 */
let redirecting = false;

function report(code: number, message: string): void {
  const onLoginPage = window.location.pathname.startsWith('/login');
  if (code === 401 && !onLoginPage) {
    clearToken();
    if (!redirecting) {
      redirecting = true;
      ElMessage.warning('登录已过期，请重新登录');
      const target = window.location.pathname + window.location.search;
      window.location.assign(`/login?redirect=${encodeURIComponent(target)}`);
    }
    return;
  }
  ElMessage.error(message || '请求失败');
}

function prune(params?: object): Record<string, unknown> | undefined {
  if (!params) return undefined;
  const result: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') result[key] = value;
  }
  return result;
}

export async function get<T>(url: string, params?: object): Promise<T> {
  const response = await http.get<Envelope<T>>(url, { params: prune(params) });
  return response.data.data;
}

export async function post<T>(url: string, body?: object, params?: object): Promise<T> {
  const response = await http.post<Envelope<T>>(url, body ?? {}, { params: prune(params) });
  return response.data.data;
}

export async function put<T>(url: string, body?: object): Promise<T> {
  const response = await http.put<Envelope<T>>(url, body ?? {});
  return response.data.data;
}

export async function del<T>(url: string): Promise<T> {
  const response = await http.delete<Envelope<T>>(url);
  return response.data.data;
}

async function requestBlob(url: string, params?: object): Promise<{ blob: Blob; name?: string }> {
  const response = await http.get<Blob>(url, { params: prune(params), responseType: 'blob' });
  const blob = response.data;
  if (blob.type.includes('application/json')) {
    const body = JSON.parse(await blob.text()) as Envelope<unknown>;
    report(body.code, body.message);
    throw new ApiError(body.code, body.message);
  }
  return { blob, name: fileName(response.headers['content-disposition']) };
}

/** 只取字节不落盘:线程 dump 等下载共用同一套信封判定。 */
export async function getBlob(url: string, params?: object): Promise<Blob> {
  const { blob } = await requestBlob(url, params);
  return blob;
}

/** 导出端点返回流。 */
export async function download(url: string, params: object, fallbackName: string): Promise<void> {
  const { blob, name } = await requestBlob(url, params);
  const href = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = href;
  link.download = name ?? fallbackName;
  link.click();
  URL.revokeObjectURL(href);
}

function fileName(disposition?: string): string | undefined {
  if (!disposition) return undefined;
  const utf8 = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1];
  const plain = /filename="?([^";]+)"?/i.exec(disposition)?.[1];
  const raw = utf8 ?? plain;
  if (!raw) return undefined;
  try {
    return decodeURIComponent(raw);
  } catch {
    return raw;
  }
}

export default http;
