import { clearToken, getToken } from '@/utils/token';

/**
 * 全平台唯一 uni.request 出口:业务代码不得自建请求。
 * 对齐后端统一信封 {code,message,data} 与 admin 端 http.ts 的语义:
 * code===0 → data;401 → 清会话并回登录页;其余 → ApiError。
 */

const CONFIGURED_BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? '';

/** H5 开发期走 vite 同源代理(见 vite.config.ts),其余端无代理必须直连后端。 */
function resolveBaseUrl(): string {
  // #ifdef H5
  if (import.meta.env.DEV) return '';
  // #endif
  return CONFIGURED_BASE_URL;
}

const BASE_URL = resolveBaseUrl();
const TIMEOUT = 20000;

/** 业务失败统一异常:code 为后端信封码,-1 表示网络异常。 */
export class ApiError extends Error {
  readonly code: number;

  constructor(code: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
  }
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  url: string;
  params?: object;
  data?: unknown;
}

/** 去掉空值,避免把 pageSize=undefined 之类拼进查询串。 */
function prune(params?: object): Record<string, string> {
  const result: Record<string, string> = {};
  if (params) {
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null && value !== '') result[key] = String(value);
    }
  }
  return result;
}

function appendQuery(url: string, params?: object): string {
  const query = prune(params);
  const pairs = Object.entries(query).map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`);
  if (pairs.length === 0) return url;
  return `${url}${url.includes('?') ? '&' : '?'}${pairs.join('&')}`;
}

/** 并发多个请求同时 401 时只提示与跳转一次 */
let redirecting = false;

function handle401(): void {
  clearToken();
  if (!redirecting) {
    redirecting = true;
    uni.showToast({ title: '登录已过期，请重新登录', icon: 'none' });
    setTimeout(() => {
      uni.reLaunch({ url: '/pages/login/login', complete: () => (redirecting = false) });
    }, 600);
  }
}

function resolveEnvelope<T>(statusCode: number, body: unknown): T {
  const envelope = body as { code?: number; message?: string; data?: T };
  if (envelope && typeof envelope.code === 'number') {
    if (envelope.code === 0) return envelope.data as T;
    if (envelope.code === 401 && statusCode !== 401) handle401();
    throw new ApiError(envelope.code, envelope.message || '请求失败');
  }
  throw new ApiError(-1, '网络异常，请稍后重试');
}

export function request<T>(options: RequestOptions): Promise<T> {
  const token = getToken();
  const header: Record<string, string> = { 'content-type': 'application/json' };
  if (token) header.Authorization = `Bearer ${token}`;

  return new Promise<T>((resolve, reject) => {
    uni.request({
      url: `${BASE_URL}${appendQuery(options.url, options.params)}`,
      method: options.method ?? 'GET',
      data: options.data as Record<string, unknown> | undefined,
      header,
      timeout: TIMEOUT,
      success: (response) => {
        try {
          resolve(resolveEnvelope<T>(response.statusCode, response.data));
        } catch (error) {
          reject(error);
        }
      },
      fail: () => {
        reject(new ApiError(-1, '网络异常，请稍后重试'));
      },
    });
  });
}

export async function get<T>(url: string, params?: object): Promise<T> {
  return request<T>({ method: 'GET', url, params });
}

export async function post<T>(url: string, body?: unknown): Promise<T> {
  return request<T>({ method: 'POST', url, data: body });
}

export async function put<T>(url: string, body?: unknown): Promise<T> {
  return request<T>({ method: 'PUT', url, data: body });
}

export async function del<T>(url: string): Promise<T> {
  return request<T>({ method: 'DELETE', url });
}
