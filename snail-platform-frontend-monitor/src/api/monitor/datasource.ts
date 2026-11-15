import { get } from '../http';

/** 数据源状态(§5.7 契约)。 */
export interface DataSourceStatus {
  metrics: {
    configured: string;
    active: string;
    reachable: boolean;
    latencyMs: number | null;
    lastError: string | null;
    mappedKeys: number;
    totalKeys: number;
    baseUrl: string | null;
  };
  logs: {
    configured: string;
    active: string;
    reachable: boolean;
    latencyMs: number | null;
    lastError: string | null;
    selector: string;
    baseUrl: string | null;
  };
  arthas: {
    enabled: boolean;
    reachable: boolean;
    agentCount: number;
    baseUrl: string | null;
  };
  links: { grafanaUrl: string | null };
  checkedAt: string | null;
}

export function fetchDatasourceStatus(): Promise<DataSourceStatus> {
  return get<DataSourceStatus>('/monitor/datasource/status');
}
