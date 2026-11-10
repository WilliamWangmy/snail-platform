/** 跨端跳转:三端(管理端/数据大屏/流程平台)共用同一后端会话,跳转携带一次性 ssotoken,
 *  目标端守卫落地后立即剥离。 */

const ADMIN_URL = import.meta.env.VITE_ADMIN_URL ?? 'http://localhost:8000/';
const SCREEN_URL = import.meta.env.VITE_SCREEN_URL ?? 'http://localhost:8001/';

export const SYSTEM_LINKS = {
  admin: ADMIN_URL,
  screen: SCREEN_URL,
} as const;

export type SystemKey = keyof typeof SYSTEM_LINKS;

/** 目标端地址拼一次性 ssotoken;传 path 则深链到目标端站内路由（如管理端通知中心）。 */
export function crossAppUrl(key: SystemKey, token: string, path?: string): string {
  const base = SYSTEM_LINKS[key];
  const target = path ? `${base.replace(/\/+$/, '')}/${path.replace(/^\/+/, '')}` : base;
  const joiner = target.includes('?') ? '&' : '?';
  return `${target}${joiner}ssotoken=${encodeURIComponent(token)}`;
}

export function openCrossApp(key: SystemKey, token: string, path?: string): void {
  window.open(crossAppUrl(key, token, path), '_blank', 'noopener');
}
