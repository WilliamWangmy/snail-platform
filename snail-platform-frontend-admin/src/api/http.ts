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

/** 全平台唯一 axios 出口:业务代码不得自建实例或直用 fetch。 */
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

/**
 * 取错误响应里的信封。非 2xx 也会带 R 信封(401/403/404/405 与 500),
 * 但流式请求(responseType=blob)的响应体是 Blob,不先读出来就只能报"网络异常",
 * 把服务端说清的拒绝理由(如无下载权限)吞掉。
 */
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

function report(code: number, message: string): void {
  const onLoginPage = window.location.pathname.startsWith('/login');
  if (code === 401 && !onLoginPage) {
    clearToken();
    ElMessage.warning('登录已过期，请重新登录');
    window.location.assign(`/login?redirect=${encodeURIComponent(window.location.pathname)}`);
    return;
  }
  ElMessage.error(message || '请求失败');
}

/** 去掉空值,避免把 pageSize=undefined 之类拼进查询串。 */
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

export async function post<T>(url: string, body?: unknown): Promise<T> {
  const response = await http.post<Envelope<T>>(url, body);
  return response.data.data;
}

export async function put<T>(url: string, body?: unknown, params?: object): Promise<T> {
  const response = await http.put<Envelope<T>>(url, body, { params: prune(params) });
  return response.data.data;
}

export async function del<T>(url: string): Promise<T> {
  const response = await http.delete<Envelope<T>>(url);
  return response.data.data;
}

/** 导出端点返回 xlsx 流;同一路径若返回 JSON 说明是业务失败信封,按错误处理。 */
export async function download(url: string, params: object, fallbackName: string): Promise<void> {
  const response = await http.get<Blob>(url, { params: prune(params), responseType: 'blob' });
  const blob = response.data;
  if (blob.type.includes('application/json')) {
    const body = JSON.parse(await blob.text()) as Envelope<unknown>;
    report(body.code, body.message);
    throw new ApiError(body.code, body.message);
  }
  const name = fileName(response.headers['content-disposition']) ?? fallbackName;
  const href = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = href;
  link.download = name;
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
