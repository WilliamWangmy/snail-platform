import { get } from '../http';
import type { PageResult } from '../types';

/** 日志行(§5.3 rows 元素)。 */
export interface LogLine {
  fileId: string;
  lineNo: number;
  time: string;
  level: string;
  traceId: string;
  thread: string;
  logger: string;
  message: string;
}

export interface LogFilesVO {
  source: string;
  files: { fileId: string; name: string; sizeBytes: number; mtime: string | null }[];
}

export interface AppLogPageVO {
  source: string;
  rows: LogLine[];
  scannedLines: number;
  truncated: boolean;
}

export interface AppLogDetailVO {
  target: LogLine;
  before: LogLine[];
  after: LogLine[];
}

export function fetchLogFiles(): Promise<LogFilesVO> {
  return get<LogFilesVO>('/monitor/log/app/files');
}

export function fetchLogPage(params: Record<string, unknown>): Promise<AppLogPageVO> {
  return get<AppLogPageVO>('/monitor/log/app/page', params);
}

export function fetchLogTail(since: number, limit = 200): Promise<LogLine[]> {
  return get<LogLine[]>('/monitor/log/app/tail', { since, limit });
}

export function fetchLogDetail(fileId: string, lineNo: number, context = 50): Promise<AppLogDetailVO> {
  return get<AppLogDetailVO>('/monitor/log/app/detail', { fileId, lineNo, context });
}

/** 操作日志分页(复用既有端点,不改)。 */
export function fetchOperLogPage(params: Record<string, unknown>): Promise<PageResult<Record<string, unknown>>> {
  return get<PageResult<Record<string, unknown>>>('/system/log/oper/page', params);
}

/** 登录日志分页(复用既有端点,不改)。 */
export function fetchLoginLogPage(params: Record<string, unknown>): Promise<PageResult<Record<string, unknown>>> {
  return get<PageResult<Record<string, unknown>>>('/system/log/login/page', params);
}
