/** 本地存储 key（集中管理，避免散落字符串） */
export const STORAGE_KEY_TOKEN = 'snail-workflow-token';

/** axios 实例 baseURL（经 Vite proxy 剥前缀转发后端） */
export const API_BASE = import.meta.env.VITE_API_BASE || '/api';

/** 请求超时（毫秒） */
export const REQUEST_TIMEOUT = 15_000;
