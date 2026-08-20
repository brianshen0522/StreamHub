import { WebSocketServer } from "ws";
import { verifyAccessToken } from "./auth.js";

/**
 * Per-user realtime fan-out.
 *
 * Library mutations arrive over the REST API, and every other page the same
 * user has open needs to hear about them — favouriting a title in the player
 * should refresh a Favorites tab without a manual reload.
 *
 * Clients authenticate with an `auth` frame rather than a token in the query
 * string, so access tokens stay out of URLs and proxy access logs.
 */

const AUTH_TIMEOUT_MS = 5_000;
const HEARTBEAT_MS = 30_000;

/** Close codes the client understands. */
const CLOSE_AUTH_TIMEOUT = 4001;
const CLOSE_TOKEN_EXPIRED = 4002;
const CLOSE_UNAUTHORIZED = 4003;

/** userId -> Set<WebSocket> */
const clients = new Map();

export function broadcast(userId, event) {
  const sockets = clients.get(String(userId));
  if (!sockets?.size) return;

  const payload = JSON.stringify({ ...event, at: Date.now() });
  for (const socket of sockets) {
    if (socket.readyState === socket.OPEN) {
      try {
        socket.send(payload);
      } catch {
        /* the heartbeat will reap this socket */
      }
    }
  }
}

function register(userId, socket) {
  if (!clients.has(userId)) clients.set(userId, new Set());
  clients.get(userId).add(socket);
}

function unregister(userId, socket) {
  const sockets = clients.get(userId);
  if (!sockets) return;
  sockets.delete(socket);
  if (!sockets.size) clients.delete(userId);
}

export function attachRealtime(server) {
  const wss = new WebSocketServer({ server, path: "/api/realtime" });

  wss.on("connection", (socket) => {
    let userId = null;
    socket.isAlive = true;
    socket.on("pong", () => { socket.isAlive = true; });

    let expiryTimer = null;

    const authTimer = setTimeout(() => {
      if (!userId) socket.close(CLOSE_AUTH_TIMEOUT, "Authentication timeout.");
    }, AUTH_TIMEOUT_MS);

    socket.on("message", (raw) => {
      // The only frame a client may send is its initial auth.
      if (userId) return;
      let expiresAt = null;
      try {
        const message = JSON.parse(String(raw));
        if (message?.type !== "auth" || !message.token) throw new Error("bad frame");
        const claims = verifyAccessToken(message.token);
        userId = String(claims.sub);
        expiresAt = Number(claims.exp) * 1000;
      } catch {
        clearTimeout(authTimer);
        socket.close(CLOSE_UNAUTHORIZED, "Unauthorized.");
        return;
      }
      clearTimeout(authTimer);
      register(userId, socket);

      // The token is only checked at handshake, so hold the connection no
      // longer than the token itself is valid. The client refreshes and
      // reconnects when it sees this code.
      if (Number.isFinite(expiresAt)) {
        expiryTimer = setTimeout(
          () => socket.close(CLOSE_TOKEN_EXPIRED, "Access token expired."),
          Math.max(0, expiresAt - Date.now()),
        );
      }

      socket.send(JSON.stringify({ type: "ready", expiresAt }));
    });

    const cleanup = () => {
      clearTimeout(authTimer);
      clearTimeout(expiryTimer);
      if (userId) unregister(userId, socket);
    };

    socket.on("close", cleanup);
    socket.on("error", cleanup);
  });

  // Drop sockets that stopped answering so `clients` cannot grow unbounded.
  const heartbeat = setInterval(() => {
    for (const socket of wss.clients) {
      if (socket.isAlive === false) {
        socket.terminate();
        continue;
      }
      socket.isAlive = false;
      try {
        socket.ping();
      } catch {
        socket.terminate();
      }
    }
  }, HEARTBEAT_MS);

  wss.on("close", () => clearInterval(heartbeat));
  return wss;
}
