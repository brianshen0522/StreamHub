import { getAccessToken, refreshSession } from "./api.js";

/**
 * Live library updates.
 *
 * One shared socket per tab, opened on first subscriber and closed when the
 * last one goes away. Server events say only *what changed*, not the new
 * value — subscribers refetch, which keeps them correct without having to
 * merge deltas.
 */

const RECONNECT_BASE_MS = 1_000;
const RECONNECT_MAX_MS = 30_000;

/** Server closes with this once the access token behind the handshake lapses. */
const CLOSE_TOKEN_EXPIRED = 4002;

const listeners = new Set();

let socket = null;
let reconnectTimer = null;
let attempts = 0;
let closedByUs = false;

function endpoint() {
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  return `${protocol}//${window.location.host}/api/realtime`;
}

function emit(event) {
  for (const listener of listeners) {
    try {
      listener(event);
    } catch {
      /* one bad subscriber must not stop the others */
    }
  }
}

function scheduleReconnect() {
  if (closedByUs || !listeners.size || reconnectTimer) return;
  const delay = Math.min(RECONNECT_MAX_MS, RECONNECT_BASE_MS * 2 ** attempts);
  attempts += 1;
  reconnectTimer = window.setTimeout(() => {
    reconnectTimer = null;
    connect();
  }, delay);
}

function connect() {
  if (socket || !listeners.size) return;

  const token = getAccessToken();
  if (!token) {
    scheduleReconnect();
    return;
  }

  let ws;
  try {
    ws = new WebSocket(endpoint());
  } catch {
    scheduleReconnect();
    return;
  }
  socket = ws;

  ws.addEventListener("open", () => {
    // Auth travels in the first frame rather than the URL, so the token stays
    // out of proxy logs and browser history.
    ws.send(JSON.stringify({ type: "auth", token: getAccessToken() }));
  });

  ws.addEventListener("message", (event) => {
    let payload;
    try {
      payload = JSON.parse(event.data);
    } catch {
      return;
    }
    if (payload?.type === "ready") {
      attempts = 0;
      return;
    }
    if (payload?.type) emit(payload);
  });

  ws.addEventListener("close", (event) => {
    if (socket === ws) socket = null;
    if (closedByUs || !listeners.size) return;

    // The socket is only authenticated at handshake, so the server drops it
    // when the token lapses. Renew first, otherwise every retry reconnects
    // with the same dead token.
    if (event.code === CLOSE_TOKEN_EXPIRED) {
      attempts = 0;
      refreshSession()
        .then(() => connect())
        .catch(() => scheduleReconnect());
      return;
    }
    scheduleReconnect();
  });

  ws.addEventListener("error", () => {
    try { ws.close(); } catch { /* already closing */ }
  });
}

function teardown() {
  closedByUs = true;
  window.clearTimeout(reconnectTimer);
  reconnectTimer = null;
  if (socket) {
    try { socket.close(); } catch { /* already closing */ }
    socket = null;
  }
}

/**
 * @param {(event: {type: string, action?: string, history?: boolean}) => void} listener
 * @returns {() => void} unsubscribe
 */
export function subscribeRealtime(listener) {
  listeners.add(listener);
  closedByUs = false;
  connect();

  return () => {
    listeners.delete(listener);
    if (!listeners.size) teardown();
  };
}

/** Reconnect with a fresh token — used after the session is renewed. */
export function resetRealtime() {
  attempts = 0;
  if (socket) {
    try { socket.close(); } catch { /* already closing */ }
    socket = null;
  }
  window.clearTimeout(reconnectTimer);
  reconnectTimer = null;
  closedByUs = false;
  connect();
}
