import { get } from '../http';

/** 总览概要:实例信息 + 健康芯片 + 8 指标当前值 + 告警计数(§5.1 契约)。 */
export interface HealthItem {
  status: 'up' | 'down' | 'skip';
  latencyMs: number | null;
}

export interface OverviewSummary {
  instance: {
    name: string;
    version: string;
    profile: string[];
    startTime: string;
    uptimeSeconds: number;
  };
  health: Record<string, HealthItem>;
  metrics: Record<string, number | null>;
  activeAlertCount: Record<string, number>;
  metricsSource: string;
  degraded: boolean;
}

export interface TrendsVO {
  loginTrend: { date: string; total: number; fail: number }[];
  onlineNow: number;
}

export function fetchOverviewSummary(): Promise<OverviewSummary> {
  return get<OverviewSummary>('/monitor/overview/summary');
}

export function fetchOverviewTrends(days = 7): Promise<TrendsVO> {
  return get<TrendsVO>('/monitor/overview/trends', { days });
}
