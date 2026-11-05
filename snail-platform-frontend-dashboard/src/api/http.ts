/** 后端统一响应信封:成功码 0,data 为业务载荷(与平台其他前端同口径)。 */
export interface Envelope<T> {
  code: number;
  message: string;
  data: T;
}

/** 业务失败统一异常:code 为后端信封码,400 校验/401 未认证/403 无权限/500 服务端。 */
export class ApiError extends Error {
  readonly code: number;

  constructor(code: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
  }
}

function getToken(): string {
  try {
    return localStorage.getItem('snail_dashboard_token') ?? '';
  } catch {
    return '';
  }
}

/** 401 统一处置:清令牌并回登录页,登录成功后凭 redirect 回到当前页面。 */
function onUnauthorized(): void {
  try {
    localStorage.removeItem('snail_dashboard_token');
  } catch {
    /* ignore */
  }
  if (window.location.pathname.startsWith('/login')) return;
  const target = window.location.pathname + window.location.search;
  window.location.assign(`/login?redirect=${encodeURIComponent(target)}`);
}

/** 大屏保持零依赖:不用 axios,fetch 直出。请求 /system 前缀,由 Vite 代理转发后端。 */
async function request<T>(method: 'GET' | 'POST', url: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  let envelope: Envelope<unknown> | undefined;
  try {
    const response = await fetch(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    envelope = (await response.json().catch(() => undefined)) as Envelope<unknown> | undefined;
  } catch {
    throw new ApiError(-1, '网络异常，请稍后重试');
  }

  // 非 2xx 同样携带 R 信封(401/403/500 等),按信封码分诊而非 HTTP 状态码
  if (!envelope || typeof envelope.code !== 'number') {
    throw new ApiError(-1, '网络异常，请稍后重试');
  }
  if (envelope.code === 401) onUnauthorized();
  if (envelope.code !== 0) {
    throw new ApiError(envelope.code, envelope.message || '请求失败');
  }
  return envelope.data as T;
}

export function get<T>(url: string, params?: Record<string, string | number>): Promise<T> {
  const query = params
    ? '?' + new URLSearchParams(Object.entries(params).map(([k, v]) => [k, String(v)])).toString()
    : '';
  return request<T>('GET', url + query);
}

export function post<T>(url: string, body?: unknown): Promise<T> {
  return request<T>('POST', url, body);
}
