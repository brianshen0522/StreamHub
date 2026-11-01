import argon2 from "argon2";
import crypto from "node:crypto";
import jwt from "jsonwebtoken";
import {
  ACCESS_TOKEN_TTL_SECONDS,
  HEARTBEAT_ONLINE_WINDOW_SECONDS,
  JWT_SECRET,
  REFRESH_TOKEN_TTL_DAYS,
} from "./config.js";

export function hashPassword(password) {
  return argon2.hash(password);
}

export function verifyPassword(password, passwordHash) {
  return argon2.verify(passwordHash, password);
}

const decoyHash = hashPassword(crypto.randomBytes(32).toString("base64url"));

/**
 * Spends the time a real password check would, for a login that has no usable
 * account behind it. Answering faster would say which logins exist.
 */
export async function verifyDecoyPassword(password) {
  await verifyPassword(password, await decoyHash);
}

/**
 * @param sessionId the session this token belongs to. Without it the server
 *   cannot tell which device a request or socket came from, which is what
 *   "this device" in a session list and "send this to that television" both
 *   need. Optional so an older token still verifies until it expires.
 */
export function createAccessToken(user, sessionId = null) {
  return jwt.sign(
    {
      sub: user.id,
      sid: sessionId,
      role: user.role,
    },
    JWT_SECRET,
    { expiresIn: ACCESS_TOKEN_TTL_SECONDS },
  );
}

export function verifyAccessToken(token) {
  return jwt.verify(token, JWT_SECRET, { algorithms: ["HS256"] });
}

export function createRefreshToken() {
  return crypto.randomBytes(48).toString("base64url");
}

export function hashRefreshToken(token) {
  return crypto.createHash("sha256").update(token).digest("hex");
}

export function getRefreshTokenExpiresAt() {
  return new Date(Date.now() + REFRESH_TOKEN_TTL_DAYS * 24 * 60 * 60 * 1000);
}

export function getOnlineThresholdDate() {
  return new Date(Date.now() - HEARTBEAT_ONLINE_WINDOW_SECONDS * 1000);
}

/**
 * @param allowQuery also accept `?accessToken=`. Only the media routes do, for
 *   the requests a media element or <img> makes, which cannot carry a header.
 */
export function getBearerToken(request, { allowQuery = false } = {}) {
  const header = request.get("authorization") || "";
  if (!header.startsWith("Bearer ")) {
    return allowQuery ? String(request.query.accessToken || "").trim() : "";
  }
  return header.slice("Bearer ".length).trim();
}
