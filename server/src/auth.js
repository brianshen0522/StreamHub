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

export function createAccessToken(user) {
  return jwt.sign(
    {
      sub: user.id,
      role: user.role,
      username: user.username,
      email: user.email,
    },
    JWT_SECRET,
    { expiresIn: ACCESS_TOKEN_TTL_SECONDS },
  );
}

export function verifyAccessToken(token) {
  return jwt.verify(token, JWT_SECRET);
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

export function getBearerToken(request) {
  const header = request.get("authorization") || "";
  if (!header.startsWith("Bearer ")) {
    const queryToken = String(request.query.accessToken || "");
    return queryToken.trim();
  }
  return header.slice("Bearer ".length).trim();
}
