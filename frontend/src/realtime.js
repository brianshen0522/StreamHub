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
let ownSessionId = null;

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
      // The handshake carries this tab's own session id and the receivers
      // already connected, so a page that opens after the television still
      // sees it without waiting for the next announcement. It used to be
      // swallowed here, back when the frame held nothing worth reading.
      ownSessionId = payload.sessionId ?? null;
      emit({ type: "receivers", receivers: payload.receivers ?? [] });
      return;
    }
    if (payload?.type) emit(payload);
  });

  ws.addEventListener("close", (event) => {
    if (socket === ws) socket = null;
    ownSessionId = null;
    // The receiver list only exists in the server's memory for as long as
    // those sockets are open, so a drop means this tab knows of none.
    emit({ type: "receivers", receivers: [] });
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

/**
 * Sends a frame to the server.
 *
 * The socket carried only inbound events until casting needed a way out: a
 * phone driving a television addresses commands at a session id over this same
 * connection, rather than opening a second one with its own auth and its own
 * failure modes.
 *
 * Returns false when there is nothing open to send on, which the caller shows
 * rather than leaving a control that silently does nothing.
 */
export function sendRealtime(frame) {
  if (!socket || socket.readyState !== WebSocket.OPEN) return false;
  try {
    socket.send(JSON.stringify(frame));
    return true;
  } catch {
    return false;
  }
}

/** This tab's own session, as the server sees it. Null until the handshake. */
export function getRealtimeSessionId() {
  return ownSessionId;
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
