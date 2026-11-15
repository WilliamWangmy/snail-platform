import { get, getBlob, post } from '../http';
import type { PageResult } from '../types';

/** 检查项结果(§5.4)。 */
export interface DiagItem {
  key: string;
  name: string;
  status: 'pass' | 'warn' | 'fail' | 'skip';
  latencyMs: number | null;
  detail: string | null;
  suggestion: string | null;
}

export interface DiagReport {
  reportId: number;
  triggerBy: string;
  triggerTime: string;
  costMs: number;
  overall: 'pass' | 'warn' | 'fail';
  items: DiagItem[] | null;
}

export function runDiag(): Promise<DiagReport> {
  return post<DiagReport>('/monitor/diag/run');
}

export function fetchReportPage(params: Record<string, unknown>): Promise<PageResult<DiagReport>> {
  return get<PageResult<DiagReport>>('/monitor/diag/report/page', params);
}

export function fetchReportDetail(reportId: number): Promise<DiagReport> {
  return get<DiagReport>(`/monitor/diag/report/${reportId}`);
}

export interface ThreadSnapshot {
  threads: { name: string; state: string; daemon: boolean; stackTop: string[] }[];
  deadlocked: number[];
}

export function fetchThreads(): Promise<ThreadSnapshot> {
  return get<ThreadSnapshot>('/monitor/diag/threads');
}

/** 线程 dump 下载(text/plain)。 */
export async function downloadThreads(): Promise<void> {
  const blob = await getBlob('/monitor/diag/threads/download');
  const href = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = href;
  link.download = `snail-threads-${Date.now()}.txt`;
  link.click();
  URL.revokeObjectURL(href);
}

export function fetchEnv(): Promise<Record<string, Record<string, string>>> {
  return get<Record<string, Record<string, string>>>('/monitor/diag/env');
}

export type { PageResult };
