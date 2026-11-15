import { get } from '../http';
import type { PageResult } from '../types';

/** 指标曲线点:t=epoch 毫秒,v=值。 */
export interface MetricPoint {
  t: number;
  v: number;
}

export interface MetricSeries {
  metric: string;
  unit: string;
  points: MetricPoint[];
}

export interface RealtimeVO {
  series: MetricSeries[];
  metricsSource: string;
  degraded: boolean;
}

export interface HistoryVO {
  metric: string;
  unit: string;
  step: number;
  points: MetricPoint[];
}

/** 实时曲线(环形缓冲)。 */
export function fetchRealtime(minutes = 60): Promise<RealtimeVO> {
  return get<RealtimeVO>('/monitor/metrics/realtime', { minutes });
}

/** 历史曲线(DB 聚合);step 60/300/3600。 */
export function fetchHistory(metric: string, beginTime: string, endTime: string, step: number): Promise<HistoryVO> {
  return get<HistoryVO>('/monitor/metrics/history', { metric, beginTime, endTime, step });
}

/** 告警详情触发序列(聚合读)。 */
export function fetchSeries(metric: string, beginTime: string, endTime: string): Promise<MetricPoint[]> {
  return fetchHistory(metric, beginTime, endTime, 60).then((vo) => vo.points);
}

export type { PageResult };
