import { WebSocketServer } from "ws";
import { verifyAccessToken } from "./auth.js";
import { prisma } from "./db.js";
import { describeDevice } from "./devices.js";

/**
 * Per-user realtime fan-out, and the channel a phone uses to drive a television.
 *
 * Library mutations arrive over the REST API, and every other page the same
 * user has open needs to hear about them — favouriting a title in the player
 * should refresh a Favorites tab without a manual reload.
 *
 * The same connection carries remote control. A television announces itself as
 * a receiver and publishes what it is playing; a phone lists the receivers and
 * sends commands at one of them. Everything is scoped to a single account:
 * a command is only ever delivered to another socket belonging to the same
 * user, which is the whole of the authorization model here. There is no
 * pairing step and no device code, because there is nothing to pair — both
 * devices already proved they hold the same account's credentials.
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

/**
 * A receiver reports on every transition now, not on a metronome, so bursts
 * are normal — a pause lands as pause+seeked within milliseconds. The window
 * still caps how often the account-wide fan-out runs, but a frame inside it
 * is *held*, never dropped: the last word of a burst is exactly the one the
 * remotes need to settle on.
 */
const MIN_STATE_INTERVAL_MS = 250;

/** userId -> Set<WebSocket> */
const clients = new Map();

/**
 * Severs a signed-out session's live connections on the spot. Deleting the
 * session row stops the *next* request; the socket it already holds — the
 * realtime feed, the cast channel — would otherwise live on until its token
 * ran out, hours after the person pressed sign out.
 */
export function kickSession(userId, sessionId) {
  const sockets = clients.get(String(userId));
  if (!sockets) return;
  for (const socket of sockets) {
    if (socket.sessionId !== sessionId) continue;
    try {
      socket.close(CLOSE_UNAUTHORIZED, "Session revoked.");
    } catch {
      /* the heartbeat will reap it */
    }
  }
}

export function broadcast(userId, event) {
  const sockets = clients.get(String(userId));
  if (!sockets?.size) return;

  const payload = JSON.stringify({ ...event, at: Date.now() });
  send(sockets, payload);
}

function send(sockets, payload) {
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

/**
 * The receivers currently reachable on this account.
 *
 * Only sockets that announced themselves appear. A television with the app
 * closed is signed in but not connected, so it is absent here while still
 * being listed under Settings — the phone needs to tell those two states
 * apart to say "switch the television on" instead of failing silently.
 */
function receiversFor(userId) {
  const sockets = clients.get(String(userId));
  if (!sockets?.size) return [];

  // One row per session, not per socket. Browser tabs of one sign-in share a
  // session id and elect a single announcer among themselves, but a tab that
  // held the role earlier still has isReceiver set on its own socket — left
  // as-is the session would appear twice, once stale. The freshest
  // announcement speaks for the session.
  const bySession = new Map();
  for (const socket of sockets) {
    if (!socket.isReceiver) continue;
    const key = String(socket.sessionId);
    const current = bySession.get(key);
    if (current && current.lastStateAt >= socket.lastStateAt) continue;
    bySession.set(key, socket);
  }
  const receivers = [...bySession.values()].map((socket) => ({
    sessionId: socket.sessionId,
    deviceName: socket.deviceName,
    clientKind: socket.clientKind,
    state: socket.playbackState ?? null,
  }));
  // Stable order, so the cast sheet does not reshuffle itself on every tick.
  receivers.sort((a, b) => String(a.sessionId).localeCompare(String(b.sessionId)));
  return receivers;
}

function publishReceivers(userId) {
  const sockets = clients.get(String(userId));
  if (!sockets?.size) return;
  send(sockets, JSON.stringify({
    type: "receivers",
    receivers: receiversFor(userId),
    at: Date.now(),
  }));
}

/**
 * What a receiver is allowed to say it is doing.
 *
 * Rebuilt field by field rather than passed through: this object is relayed
 * verbatim to the account's other devices, and a receiver is just another
 * client. Copying only known keys keeps a compromised or buggy one from
 * pushing arbitrary payloads into the phone's UI.
 */
function sanitizePlaybackState(raw) {
  if (!raw || typeof raw !== "object") return null;

  const text = (value, max = 300) =>
    typeof value === "string" && value.trim() ? value.trim().slice(0, max) : null;
  const millis = (value) => {
    const number = Number(value);
    return Number.isFinite(number) && number >= 0 ? Math.round(number) : null;
  };

  return {
    provider: text(raw.provider, 40),
    itemUrl: text(raw.itemUrl, 2000),
    title: text(raw.title),
    subtitle: text(raw.subtitle),
    posterUrl: text(raw.posterUrl, 2000),
    episodeLabel: text(raw.episodeLabel, 120),
    positionMs: millis(raw.positionMs) ?? 0,
    durationMs: millis(raw.durationMs) ?? 0,
    paused: Boolean(raw.paused),
    buffering: Boolean(raw.buffering),
    // Season edges, known only to the device that holds the episode list. The
    // remotes disable their skip buttons off these.
    hasNext: Boolean(raw.hasNext),
    hasPrevious: Boolean(raw.hasPrevious),
  };
}

/** The commands a controller may send, normalised the same way and for the same reason. */
function sanitizeCommand(raw) {
  if (!raw || typeof raw !== "object") return null;
  const action = String(raw.action || "");

  switch (action) {
    case "pause":
    case "resume":
    case "stop":
    case "next":
    case "previous":
    // Toggles the receiver's own idea of full screen — on the web that is the
    // immersive layout, a native television is already there and ignores it.
    case "fullscreen":
      return { action };
    case "seek": {
      const positionMs = Number(raw.positionMs);
      if (!Number.isFinite(positionMs) || positionMs < 0) return null;
      return { action, positionMs: Math.round(positionMs) };
    }
    case "play": {
      const playback = raw.playback;
      if (!playback || typeof playback !== "object") return null;
      const text = (value, max = 2000) =>
        typeof value === "string" && value.trim() ? value.trim().slice(0, max) : null;
      const streamUrl = text(playback.streamUrl);
      if (!streamUrl) return null;
      return {
        action,
        playback: {
          streamUrl,
          provider: text(playback.provider, 40),
          itemUrl: text(playback.itemUrl),
          title: text(playback.title, 300),
          subtitle: text(playback.subtitle, 300),
          posterUrl: text(playback.posterUrl),
          episodeLabel: text(playback.episodeLabel, 120),
          episodeUrl: text(playback.episodeUrl),
          // Without this a cast receiver has no idea what comes next, and a
          // television loses its own next-episode control the moment a phone
          // starts something on it.
          nextEpisodeLabel: text(playback.nextEpisodeLabel, 120),
          prevEpisodeLabel: text(playback.prevEpisodeLabel, 120),
          positionMs: Math.max(0, Math.round(Number(playback.positionMs) || 0)),
        },
      };
    }
    default:
      return null;
  }
}

export function attachRealtime(server) {
  const wss = new WebSocketServer({ server, path: "/api/realtime" });

  wss.on("connection", (socket) => {
    socket.isAlive = true;
    socket.on("pong", () => { socket.isAlive = true; });

    socket.userId = null;
    socket.sessionId = null;
    socket.clientKind = null;
    socket.deviceName = null;
    socket.isReceiver = false;
    socket.playbackState = null;
    socket.lastStateAt = 0;

    let expiryTimer = null;

    const authTimer = setTimeout(() => {
      if (!socket.userId) socket.close(CLOSE_AUTH_TIMEOUT, "Authentication timeout.");
    }, AUTH_TIMEOUT_MS);

    const authenticate = async (message) => {
      if (message?.type !== "auth" || !message.token) throw new Error("bad frame");
      const claims = verifyAccessToken(message.token);
      const userId = String(claims.sub);
      const sessionId = claims.sid ? String(claims.sid) : null;
      const expiresAt = Number(claims.exp) * 1000;

      // The receiver list has to name devices the same way Settings does, and
      // that name lives on the session row rather than in the token. The row
      // is also the revocation switch: a token whose session was signed out
      // does not get a socket, however long the JWT itself has left.
      let deviceName = null;
      let clientKind = null;
      if (sessionId) {
        const session = await prisma.userSession.findFirst({
          where: { id: sessionId, userId: claims.sub },
          select: { userAgent: true, clientKind: true, expiresAt: true },
        });
        if (!session || session.expiresAt <= new Date()) {
          clearTimeout(authTimer);
          socket.close(CLOSE_UNAUTHORIZED, "Session revoked.");
          return;
        }
        deviceName = describeDevice(session);
        clientKind = session.clientKind ?? null;
      }

      clearTimeout(authTimer);
      socket.userId = userId;
      socket.sessionId = sessionId;
      socket.deviceName = deviceName ?? "Unknown device";
      socket.clientKind = clientKind;
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

      socket.send(JSON.stringify({
        type: "ready",
        expiresAt,
        sessionId,
        receivers: receiversFor(userId),
      }));
    };

    socket.on("message", (raw) => {
      let message = null;
      try {
        message = JSON.parse(String(raw));
      } catch {
        return;
      }

      if (!socket.userId) {
        authenticate(message).catch(() => {
          clearTimeout(authTimer);
          socket.close(CLOSE_UNAUTHORIZED, "Unauthorized.");
        });
        return;
      }

      switch (message?.type) {
        case "playback": {
          // Sending this frame at all is how a device announces it can be
          // driven; an idle television still has to appear in the cast list,
          // so a null state is a valid announcement rather than a withdrawal.
          socket.isReceiver = true;
          socket.playbackState = sanitizePlaybackState(message.state);
          const now = Date.now();
          const wait = MIN_STATE_INTERVAL_MS - (now - socket.lastStateAt);
          if (wait > 0) {
            // Inside the window: the state is already recorded above, so one
            // deferred publish carries whatever the burst settles on. Dropping
            // it instead let a pause's own report die and left every remote
            // showing "playing" until the next heartbeat.
            if (!socket.trailingPublish) {
              socket.trailingPublish = setTimeout(() => {
                socket.trailingPublish = null;
                socket.lastStateAt = Date.now();
                if (socket.readyState === socket.OPEN) publishReceivers(socket.userId);
              }, wait);
            }
            return;
          }
          socket.lastStateAt = now;
          publishReceivers(socket.userId);
          return;
        }
        case "command": {
          const command = sanitizeCommand(message.command);
          const target = String(message.to || "");
          if (!command || !target) return;

          // Scoped to this user's own sockets. A session id from another
          // account simply matches nothing.
          const sockets = clients.get(socket.userId);
          if (!sockets) return;
          for (const candidate of sockets) {
            if (candidate.sessionId !== target || candidate.readyState !== candidate.OPEN) continue;
            try {
              candidate.send(JSON.stringify({
                type: "command",
                from: socket.sessionId,
                fromName: socket.deviceName,
                command,
                at: Date.now(),
              }));
            } catch {
              /* the heartbeat will reap this socket */
            }
          }
          return;
        }
        default:
          return;
      }
    });

    const cleanup = () => {
      clearTimeout(authTimer);
      clearTimeout(expiryTimer);
      clearTimeout(socket.trailingPublish);
      socket.trailingPublish = null;
      if (!socket.userId) return;
      const userId = socket.userId;
      const wasReceiver = socket.isReceiver;
      unregister(userId, socket);
      // A television that just dropped off has to disappear from the phone's
      // cast list, or the phone goes on sending commands into nothing.
      if (wasReceiver) publishReceivers(userId);
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
