import { del, get, post, put } from '../http';
import type { PageResult } from '../types';

/** 告警规则(§5.2 契约)。 */
export interface AlertRule {
  ruleId: number;
  name: string;
  metric: string;
  comparator: string;
  threshold: number;
  durationPoints: number;
  level: string;
  enabled: number;
  isSystem: number;
  silenceUntil: string | null;
  remark: string | null;
  createBy: number | null;
  createTime: string | null;
  updateTime: string | null;
}

export interface MonitorAlert {
  alertId: number;
  ruleId: number;
  ruleName: string;
  metric: string;
  level: string;
  status: 'firing' | 'acked' | 'resolved';
  triggerValue: number;
  threshold: number;
  comparator: string;
  firstTime: string;
  lastTime: string;
  resolveTime: string | null;
  ackBy: string | null;
  ackTime: string | null;
  silenceUntil: string | null;
}

export interface AlertDetail {
  alert: MonitorAlert;
  rule: AlertRule | null;
  series: { t: number; v: number }[];
  notifyRecords: { time: string; type: string; recipients: number; channels: string[]; error: string | null }[];
  seriesExpired: boolean;
}

export function fetchActiveAlerts(): Promise<MonitorAlert[]> {
  return get<MonitorAlert[]>('/monitor/alert/active');
}

export function fetchAlertPage(params: Record<string, unknown>): Promise<PageResult<MonitorAlert>> {
  return get<PageResult<MonitorAlert>>('/monitor/alert/page', params);
}

export function fetchAlertDetail(alertId: number): Promise<AlertDetail> {
  return get<AlertDetail>(`/monitor/alert/${alertId}`);
}

export function ackAlert(alertId: number): Promise<void> {
  return post<void>(`/monitor/alert/${alertId}/ack`);
}

export function silenceAlert(alertId: number, minutes: number): Promise<string> {
  return post<string>(`/monitor/alert/${alertId}/silence`, { minutes });
}

export function fetchRulePage(params: Record<string, unknown>): Promise<PageResult<AlertRule>> {
  return get<PageResult<AlertRule>>('/monitor/alert/rule/page', params);
}

export function createRule(body: Record<string, unknown>): Promise<AlertRule> {
  return post<AlertRule>('/monitor/alert/rule', body);
}

export function updateRule(ruleId: number, body: Record<string, unknown>): Promise<AlertRule> {
  return put<AlertRule>(`/monitor/alert/rule/${ruleId}`, body);
}

export function deleteRule(ruleId: number): Promise<void> {
  return del<void>(`/monitor/alert/rule/${ruleId}`);
}

export function toggleRule(ruleId: number, enabled: number): Promise<void> {
  return put<void>(`/monitor/alert/rule/${ruleId}/enabled`, { enabled });
}
