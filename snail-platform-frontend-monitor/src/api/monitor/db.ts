import { get } from '../http';
import type { PageResult } from '../types';

/** 慢 SQL 摘要行(§5.1.1)。 */
export interface SlowSqlDigest {
  digestId: string;
  sql: string;
  mapperId: string;
  slowCount: number;
  maxMs: number;
  avgMs: number;
  lastMs: number;
  lastTime: string;
}

export interface SlowSqlEvent {
  time: string;
  costMs: number;
  digestId: string;
  sql: string;
  mapperId: string;
  traceId: string | null;
}

export interface DbSummary {
  pool: {
    available: boolean;
    active: number | null;
    idle: number | null;
    total: number | null;
    max: number | null;
    pending: number | null;
    usagePercent: number | null;
  };
  sql: {
    enabled: boolean;
    execPerSec: number | null;
    avgRtMs: number | null;
    slowThresholdMs: number | null;
    slowCount5m: number | null;
    slowCountTotal: number | null;
    since: string | null;
  };
  server: {
    available: boolean;
    version: string | null;
    maxConnections: number | null;
    threadsConnected: number | null;
    threadsRunning: number | null;
    rowLockWaitsPerMin: number | null;
    lastError: string | null;
    checkedAt: string | null;
  };
  slowTop: SlowSqlDigest[];
  checkedAt: string;
}

export function fetchDbSummary(): Promise<DbSummary> {
  return get<DbSummary>('/monitor/db/summary');
}

export function fetchDbSlow(): Promise<{ thresholdMs: number; since: string; top: SlowSqlDigest[]; recent: SlowSqlEvent[] }> {
  return get('/monitor/db/slow');
}

export type { PageResult };
