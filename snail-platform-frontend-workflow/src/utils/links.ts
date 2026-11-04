/** 跨端跳转:三端(管理端/数据大屏/流程平台)共用同一后端会话,跳转携带一次性 ssotoken,
 *  目标端守卫落地后立即剥离;大屏无登录态,收下 token 仅用于再次交接。 */

const ADMIN_URL = import.meta.env.VITE_ADMIN_URL ?? 'http://localhost:8000/';
const SCREEN_URL = import.meta.env.VITE_SCREEN_URL ?? 'http://localhost:8001/';

export const SYSTEM_LINKS = {
  admin: ADMIN_URL,
  screen: SCREEN_URL,
} as const;

export type SystemKey = keyof typeof SYSTEM_LINKS;

/** 目标端地址拼一次性 ssotoken。 */
export function crossAppUrl(key: SystemKey, token: string): string {
  const base = SYSTEM_LINKS[key];
  const joiner = base.includes('?') ? '&' : '?';
  return `${base}${joiner}ssotoken=${encodeURIComponent(token)}`;
}

export function openCrossApp(key: SystemKey, token: string): void {
  window.open(crossAppUrl(key, token), '_blank', 'noopener');
}
