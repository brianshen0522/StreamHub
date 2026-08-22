import { UserRole, UserStatus } from "../generated/prisma/index.js";
import { prisma } from "./db.js";
import { getBearerToken, getRefreshTokenExpiresAt, verifyAccessToken } from "./auth.js";

export function asyncHandler(handler) {
  return (request, response, next) => {
    Promise.resolve(handler(request, response, next)).catch(next);
  };
}

/**
 * Sliding session expiry: any authenticated contact restarts the clock.
 *
 * A session's expiresAt used to move only when the refresh token rotated, so
 * the countdown ran from the last refresh rather than the last *use* — a
 * device in daily use could still be marching toward its sign-out. Now every
 * authenticated request pushes the whole window ahead of it, which makes the
 * TTL an idle limit and nothing else: a device only expires by genuinely not
 * being used for that long.
 *
 * Throttled per session, because sliding a 90-day window forward more than
 * once per quarter hour changes nothing but the write load. The throttle table
 * is in memory on purpose — after a restart the first request per session
 * writes once, which is the correct behaviour anyway. The write is not awaited
 * and its failure is swallowed: a session row deleted mid-flight (signed out
 * on another device) must not turn a valid request into an error.
 */
const SLIDE_EVERY_MS = 15 * 60 * 1000;
const lastSlideAt = new Map();

function slideSessionExpiry(sessionId) {
  if (!sessionId) return;
  const now = Date.now();
  const last = lastSlideAt.get(sessionId) ?? 0;
  if (now - last < SLIDE_EVERY_MS) return;
  lastSlideAt.set(sessionId, now);
  // Bounded: entries only exist for sessions seen since the last restart.
  if (lastSlideAt.size > 10_000) lastSlideAt.clear();

  prisma.userSession.update({
    where: { id: sessionId },
    data: { expiresAt: getRefreshTokenExpiresAt(), lastSeenAt: new Date() },
  }).catch(() => {});
}

export function requireAuth() {
  return asyncHandler(async (request, response, next) => {
    const token = getBearerToken(request);
    if (!token) {
      response.status(401).json({ error: "Authentication required." });
      return;
    }

    let payload;
    try {
      payload = verifyAccessToken(token);
    } catch {
      response.status(401).json({ error: "Invalid access token." });
      return;
    }

    const user = await prisma.user.findUnique({
      where: { id: String(payload.sub) },
    });
    if (!user || user.status !== UserStatus.ACTIVE) {
      response.status(401).json({ error: "User not available." });
      return;
    }

    // sessionId is null for tokens issued before it was a claim; they expire
    // within the access-token lifetime, so nothing needs migrating.
    request.auth = { user, sessionId: payload.sid ?? null };
    slideSessionExpiry(request.auth.sessionId);
    next();
  });
}

export function requireRole(role) {
  return (request, response, next) => {
    if (!request.auth?.user) {
      response.status(401).json({ error: "Authentication required." });
      return;
    }
    if (request.auth.user.role !== role) {
      response.status(403).json({ error: "Forbidden." });
      return;
    }
    next();
  };
}

export function forbidAdminPlayback() {
  return (request, response, next) => {
    if (request.auth?.user?.role === UserRole.ADMIN) {
      response.status(403).json({ error: "Admin playback access is disabled." });
      return;
    }
    next();
  };
}
