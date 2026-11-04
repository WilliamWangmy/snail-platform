/**
 * 顶栏消息铃铛：端点与管理端 api/notify.ts 同口径（同一后端 NotifyController）。
 * unread-count 登录即可读；page 要 system:notify:list，无权限时顶栏只显示未读数。
 */
import { http } from '@/utils/request';
import type { PageResult } from '@/types/api';

/** 收件箱条目,与后端 NotifyInboxItemVO 同口径。 */
export interface NotifyInboxItem {
  recipientId: number;
  messageId: number;
  title: string;
  content: string;
  bizType: string | null;
  bizId: number | null;
  senderName: string | null;
  sendTime: string | null;
  /** 0 未读 1 已读 */
  readStatus: number;
  readTime: string | null;
}

export function fetchUnreadCount(): Promise<number> {
  return http<number>({ url: '/system/notify/unread-count', method: 'GET', silent: true });
}

/** 铃铛面板只取最新几条未读,读与清仍在管理端做。 */
export function pageLatestUnread(pageSize = 6): Promise<PageResult<NotifyInboxItem>> {
  return http<PageResult<NotifyInboxItem>>({
    url: '/system/notify/page',
    method: 'GET',
    params: { pageNum: 1, pageSize, readStatus: 0 },
    silent: true
  });
}
