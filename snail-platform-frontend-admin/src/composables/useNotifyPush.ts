import { getToken } from '@/utils/token';

/**
 * 站内消息实时推送(SSE)订阅:fetch 流式读取 + 自实现断线重连。
 * 说明:本文件是全平台"业务代码不得直用 fetch"约定的唯一例外——SSE 是渐进式长响应,
 * axios 在浏览器端拿不到流,EventSource 又带不了 Authorization 头,只有原生 fetch 可行。
 * 事件口径:
 * - `notify:pushed`(detail=单帧 payload):每条推送都发,角标刷新与收件箱页刷新消费它;
 * - `notify:toast`(detail={count,titles}):500ms 合并窗口 flush 后发,只供到达 Toast 消费;
 *   合并只影响 Toast,角标与列表刷新对每条推送照常生效(幂等,无需合并)。
 * 降噪:建连后 3s 内到达的帧不进合并器(多为建连瞬间的旧消息回放感),只刷角标;
 * 连续失败超过 10 次停止重连,完全退回 30s 轮询(存量逻辑不动,本 composable 只是加速器)。
 */

export interface NotifyPushPayload {
  messageId: number;
  title: string;
  bizType: string | null;
}

/** 到达 Toast 的合并帧:窗口期内的多条推送压成一条 */
export interface NotifyToastPayload {
  count: number;
  titles: string[];
}

const MERGE_WINDOW_MS = 500;
/** 建连后该时长内到达的帧不弹 Toast,只刷角标 */
const QUIET_AFTER_OPEN_MS = 3000;
const RECONNECT_BASE_MS = 5000;
const RECONNECT_MAX_MS = 60000;
const MAX_FAILURES = 10;

export interface NotifyPushOptions {
  /** 每帧到达时的回调(角标刷新等);失败静默,回调自身吞错 */
  onFrame?: (payload: NotifyPushPayload) => void;
}

export function useNotifyPush(options: NotifyPushOptions = {}) {
  let running = false;
  let controller: AbortController | undefined;
  let reconnectTimer: number | undefined;
  let failures = 0;
  let openedAt = 0;

  /** 合并器窗口期队列:入队即排 500ms flush,窗口期内再收到只入队不重置定时器 */
  let pending: NotifyPushPayload[] = [];
  let mergeTimer: number | undefined;

  function start(): void {
    if (running) return;
    running = true;
    failures = 0;
    void connect();
  }

  function stop(): void {
    running = false;
    controller?.abort();
    controller = undefined;
    if (reconnectTimer !== undefined) {
      window.clearTimeout(reconnectTimer);
      reconnectTimer = undefined;
    }
    flushPending();
  }

  async function connect(): Promise<void> {
    if (!running) return;
    controller = new AbortController();
    try {
      const response = await fetch('/system/notify/subscribe', {
        headers: { Authorization: `Bearer ${getToken()}` },
        signal: controller.signal,
      });
      if (!response.ok || !response.body) throw new Error(`订阅失败: HTTP ${response.status}`);
      failures = 0;
      openedAt = Date.now();
      await readStream(response.body);
    } catch {
      // 主动 stop 的 abort 不算失败,不走重连
      if (!running) return;
    }
    scheduleReconnect();
  }

  /** 按帧边界(\n\n)切块;注释帧(:ping)跳过,data 帧进业务处理 */
  async function readStream(body: ReadableStream<Uint8Array>): Promise<void> {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let boundary = buffer.indexOf('\n\n');
      while (boundary >= 0) {
        handleFrame(buffer.slice(0, boundary));
        buffer = buffer.slice(boundary + 2);
        boundary = buffer.indexOf('\n\n');
      }
    }
  }

  function handleFrame(block: string): void {
    const data = block
      .split('\n')
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).trim())
      .join('');
    if (!data) return;
    let payload: NotifyPushPayload;
    try {
      payload = JSON.parse(data) as NotifyPushPayload;
    } catch {
      return;
    }
    options.onFrame?.(payload);
    window.dispatchEvent(new CustomEvent<NotifyPushPayload>('notify:pushed', { detail: payload }));
    if (Date.now() - openedAt < QUIET_AFTER_OPEN_MS) return;
    pending.push(payload);
    if (mergeTimer === undefined) {
      mergeTimer = window.setTimeout(flushPending, MERGE_WINDOW_MS);
    }
  }

  /** 合并窗口 flush:有积压才发 toast 事件,事件消费方(NotifyToast)负责展示与降噪 */
  function flushPending(): void {
    if (mergeTimer !== undefined) {
      window.clearTimeout(mergeTimer);
      mergeTimer = undefined;
    }
    if (!pending.length) return;
    const detail: NotifyToastPayload = {
      count: pending.length,
      titles: pending.map((item) => item.title),
    };
    pending = [];
    window.dispatchEvent(new CustomEvent<NotifyToastPayload>('notify:toast', { detail }));
  }

  function scheduleReconnect(): void {
    if (!running) return;
    failures += 1;
    if (failures > MAX_FAILURES) {
      stop();
      return;
    }
    const delay = Math.min(RECONNECT_BASE_MS * 2 ** (failures - 1), RECONNECT_MAX_MS);
    reconnectTimer = window.setTimeout(() => {
      reconnectTimer = undefined;
      void connect();
    }, delay);
  }

  return { start, stop };
}
