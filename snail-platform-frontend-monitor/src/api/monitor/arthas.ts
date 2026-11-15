import { get } from '../http';

/** Arthas tunnel 状态(§5.8 契约)。 */
export interface ArthasStatus {
  enabled: boolean;
  reachable: boolean;
  latencyMs: number | null;
  lastError: string | null;
  tunnelVersion: string | null;
  consoleBaseUrl: string | null;
  wsPort: number | null;
  agents: { agentId: string; host: string | null; port: number | null; arthasVersion: string | null }[];
  agentCount: number;
  clientConnectionCount: number;
  checkedAt: string | null;
}

export function fetchArthasStatus(): Promise<ArthasStatus> {
  return get<ArthasStatus>('/monitor/arthas/status');
}
