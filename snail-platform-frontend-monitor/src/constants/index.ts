/** 本地存储 key(集中管理):监控端独立命名空间,不与 admin/workflow 互写。 */
export const STORAGE_KEY_TOKEN = 'snail_monitor_token';
/** 主题键:首读回落 admin 键(老用户沿用既有偏好),写入只写 monitor 键(不反向写 admin)。 */
export const STORAGE_KEY_THEME = 'snail_monitor_theme_v1';
export const STORAGE_KEY_THEME_ADMIN = 'snail_admin_theme_v1';

/** axios 实例 baseURL:dev 经 Vite proxy 直转后端,生产同域直发。 */
export const API_BASE = '';

/** 请求超时(毫秒)。 */
export const REQUEST_TIMEOUT = 20_000;
