import { del, get, put } from '@/adapter/request';
import type { PageParams, PageResult } from './types';

/**
 * 消息通知(端点与 admin/src/api/notify.ts 完全同口径)。
 * bizType 为后端业务标签,移动端仅做展示映射,未知标签兜底为"消息"。
 */

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

export interface NotifyInboxQuery extends PageParams {
  /** 0 未读 1 已读,不传不限 */
  readStatus?: number | null;
  bizType?: string;
}

/** 平台公告,与后端 AnnouncementVO 同口径。 */
export interface AnnouncementItem {
  messageId: number;
  tenantId: number;
  title: string;
  content: string;
  senderName: string | null;
  sendTime: string | null;
  recipientCount: number;
}

export interface AnnouncementQuery extends PageParams {
  title?: string;
}

export function pageInbox(query: NotifyInboxQuery): Promise<PageResult<NotifyInboxItem>> {
  return get<PageResult<NotifyInboxItem>>('/system/notify/page', query);
}

export function fetchUnreadCount(): Promise<number> {
  return get<number>('/system/notify/unread-count');
}

export function markRead(recipientId: number): Promise<void> {
  return put<void>(`/system/notify/${recipientId}/read`);
}

export function markAllRead(): Promise<void> {
  return put<void>('/system/notify/read-all');
}

export function removeInboxItem(recipientId: number): Promise<void> {
  return del<void>(`/system/notify/${recipientId}`);
}

export function pageAnnouncements(query: AnnouncementQuery): Promise<PageResult<AnnouncementItem>> {
  return get<PageResult<AnnouncementItem>>('/system/notify/announcement/page', query);
}
