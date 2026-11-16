// SSE client with explicit reconnect (exponential backoff). Native EventSource stops
// retrying after some failures (e.g. a 503), so we close and re-open ourselves.

export const STREAM_EVENTS = [
  'health.updated', 'telemetry.updated', 'anomaly.detected', 'anomaly.cleared',
  'incident.opened', 'incident.updated', 'incident.resolved', 'diagnosis.updated', 'heartbeat',
];

/**
 * @param {object} o
 * @param {string} o.url
 * @param {(evt:{type:string,data:any,raw:object})=>void} o.onEvent
 * @param {(status:'connecting'|'connected'|'disconnected')=>void} o.onStatus
 * @param {()=>void} [o.onReconnect] called on every open after the first (refetch REST state)
 * @param {typeof EventSource} [o.EventSourceImpl]
 * @returns {{close:()=>void}}
 */
export function connectStream({
  url, onEvent, onStatus, onReconnect,
  EventSourceImpl = globalThis.EventSource,
  minDelayMs = 1000, maxDelayMs = 15000,
  setTimeoutImpl = setTimeout, clearTimeoutImpl = clearTimeout,
}) {
  let es = null;
  let timer = null;
  let closed = false;
  let attempts = 0;
  let everOpened = false;

  const open = () => {
    if (closed) return;
    onStatus('connecting');
    es = new EventSourceImpl(url);
    es.onopen = () => {
      attempts = 0;
      onStatus('connected');
      if (everOpened && onReconnect) onReconnect();
      everOpened = true;
    };
    es.onerror = () => {
      if (closed) return;
      if (es) es.close();
      es = null;
      onStatus('disconnected');
      const delay = Math.min(maxDelayMs, minDelayMs * 2 ** attempts);
      attempts += 1;
      timer = setTimeoutImpl(open, delay);
    };
    for (const name of STREAM_EVENTS) {
      es.addEventListener(name, (msg) => {
        let data = null;
        try {
          const env = JSON.parse(msg.data);
          data = env && Object.prototype.hasOwnProperty.call(env, 'data') ? env.data : env;
        } catch {
          return;
        }
        onEvent({ type: name, data, raw: msg });
      });
    }
  };

  open();
  return {
    close() {
      closed = true;
      if (timer) clearTimeoutImpl(timer);
      if (es) es.close();
    },
  };
}
