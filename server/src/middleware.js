import { UserRole, UserStatus } from "../generated/prisma/index.js";
import { prisma } from "./db.js";
import { getBearerToken, verifyAccessToken } from "./auth.js";

export function asyncHandler(handler) {
  return (request, response, next) => {
    Promise.resolve(handler(request, response, next)).catch(next);
  };
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

    request.auth = { user };
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
