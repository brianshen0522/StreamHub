import "dotenv/config";
import express from "express";
import morgan from "morgan";
import cookieParser from "cookie-parser";
import { Prisma, UserRole, UserStatus } from "../generated/prisma/index.js";
import { PORT } from "./config.js";
import { providers } from "./providers/index.js";
import { streamCheckedSources, handlePosterProxy, handleStreamProxy } from "./stream.js";
import {
  createAccessToken,
  createRefreshToken,
  getOnlineThresholdDate,
  getRefreshTokenExpiresAt,
  hashPassword,
  hashRefreshToken,
  verifyPassword,
} from "./auth.js";
import { prisma } from "./db.js";
import { asyncHandler, forbidAdminPlayback, requireAuth, requireRole } from "./middleware.js";
import { startMonitoring } from "./monitoring.js";
import { assertProviderAccess, getEnabledProvidersForUser } from "./provider-access.js";
import {
  adminResetPasswordSchema,
  createUserSchema,
  favoriteSchema,
  loginSchema,
  progressSchema,
  sourcePreferenceSchema,
  toggleProviderSchema,
  updateMyPasswordSchema,
  updateMyProfileSchema,
  updateUserSchema,
} from "./validators.js";

const app = express();

app.use(morgan("dev"));
app.use(express.json());
app.use(cookieParser());

function getProvider(name) {
  const provider = providers[name];
  if (!provider) {
    const error = new Error(`Unsupported provider: ${name}`);
    error.statusCode = 400;
    throw error;
  }
  return provider;
}

function sanitizeNullable(value) {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  return trimmed ? trimmed : null;
}

function sanitizeKeyString(value) {
  if (typeof value !== "string") return "";
  return value.trim();
}

function serializeUser(user) {
  return {
    id: user.id,
    username: user.username,
    email: user.email,
    displayName: user.displayName,
    role: user.role,
    status: user.status,
    lastLoginAt: user.lastLoginAt,
    lastSeenAt: user.lastSeenAt,
    createdAt: user.createdAt,
    updatedAt: user.updatedAt,
  };
}

function buildProgressKey(userId, payload) {
  return {
    userId_providerKey_itemUrl_seasonUrl_episodeLabel: {
      userId,
      providerKey: payload.providerKey,
      itemUrl: payload.itemUrl,
      seasonUrl: sanitizeKeyString(payload.seasonUrl),
      episodeLabel: sanitizeKeyString(payload.episodeLabel),
    },
  };
}

function buildFavoriteKey(userId, payload) {
  return {
    userId_providerKey_itemUrl_seasonUrl_episodeLabel: {
      userId,
      providerKey: payload.providerKey,
      itemUrl: payload.itemUrl,
      seasonUrl: sanitizeKeyString(payload.seasonUrl),
      episodeLabel: sanitizeKeyString(payload.episodeLabel),
    },
  };
}

async function createAuditLog(actorUserId, action, payload = {}, targetUserId = null) {
  await prisma.auditLog.create({
    data: {
      actorUserId,
      action,
      targetUserId,
      payload,
    },
  });
}


async function issueSession(user, request) {
  const accessToken = createAccessToken(user);
  const refreshToken = createRefreshToken();

  await prisma.userSession.create({
    data: {
      userId: user.id,
      refreshTokenHash: hashRefreshToken(refreshToken),
      ip: request.ip,
      userAgent: request.get("user-agent") || null,
      lastSeenAt: new Date(),
      expiresAt: getRefreshTokenExpiresAt(),
    },
  });

  await prisma.user.update({
    where: { id: user.id },
    data: {
      lastLoginAt: new Date(),
      lastSeenAt: new Date(),
    },
  });

  return { accessToken, refreshToken };
}

async function ensureBootstrapped() {
  const providerSeeds = [
    { key: "movieffm", name: "MovieFFM", sortOrder: 1 },
    { key: "777tv", name: "777TV", sortOrder: 2 },
    { key: "dramasq", name: "DramaSQ", sortOrder: 3 },
  ];

  await Promise.all(
    providerSeeds.map((provider) =>
      prisma.provider.upsert({
        where: { key: provider.key },
        update: {
          name: provider.name,
          sortOrder: provider.sortOrder,
        },
        create: provider,
      }),
    ),
  );

  const existingAdmin = await prisma.user.findFirst({
    where: { role: UserRole.ADMIN },
  });

  if (!existingAdmin) {
    await prisma.user.create({
      data: {
        username: "admin",
        email: "admin@local",
        displayName: "Administrator",
        passwordHash: await hashPassword(process.env.ADMIN_PASSWORD || "admin"),
        role: UserRole.ADMIN,
      },
    });
  }
}

app.get("/api/health", (_request, response) => {
  response.json({ ok: true });
});

app.post("/api/auth/login", asyncHandler(async (request, response) => {
  const payload = loginSchema.parse(request.body || {});
  const login = payload.login.trim();

  const user = await prisma.user.findFirst({
    where: {
      OR: [
        { username: login },
        { email: login.toLowerCase() },
      ],
    },
  });

  if (!user || user.status !== UserStatus.ACTIVE) {
    response.status(401).json({ error: "Invalid credentials." });
    return;
  }

  const valid = await verifyPassword(payload.password, user.passwordHash);
  if (!valid) {
    response.status(401).json({ error: "Invalid credentials." });
    return;
  }

  const session = await issueSession(user, request);
  response.json({
    user: serializeUser(user),
    ...session,
  });
}));

app.post("/api/auth/refresh", asyncHandler(async (request, response) => {
  const refreshToken = String(request.body?.refreshToken || "");
  if (!refreshToken) {
    response.status(400).json({ error: "Missing refresh token." });
    return;
  }

  const session = await prisma.userSession.findFirst({
    where: {
      refreshTokenHash: hashRefreshToken(refreshToken),
      expiresAt: { gt: new Date() },
    },
    include: { user: true },
  });

  if (!session || session.user.status !== UserStatus.ACTIVE) {
    response.status(401).json({ error: "Invalid refresh token." });
    return;
  }

  const nextRefreshToken = createRefreshToken();
  await prisma.userSession.update({
    where: { id: session.id },
    data: {
      refreshTokenHash: hashRefreshToken(nextRefreshToken),
      lastSeenAt: new Date(),
      expiresAt: getRefreshTokenExpiresAt(),
    },
  });
  await prisma.user.update({
    where: { id: session.user.id },
    data: { lastSeenAt: new Date() },
  });

  response.json({
    user: serializeUser(session.user),
    accessToken: createAccessToken(session.user),
    refreshToken: nextRefreshToken,
  });
}));

app.post("/api/auth/logout", asyncHandler(async (request, response) => {
  const refreshToken = String(request.body?.refreshToken || "");
  if (refreshToken) {
    await prisma.userSession.deleteMany({
      where: { refreshTokenHash: hashRefreshToken(refreshToken) },
    });
  }
  response.json({ ok: true });
}));

app.get("/api/auth/me", requireAuth(), asyncHandler(async (request, response) => {
  response.json({
    user: serializeUser(request.auth.user),
  });
}));

app.post("/api/auth/heartbeat", requireAuth(), asyncHandler(async (request, response) => {
  await prisma.user.update({
    where: { id: request.auth.user.id },
    data: { lastSeenAt: new Date() },
  });
  response.json({ ok: true });
}));

app.patch("/api/auth/me/profile", requireAuth(), asyncHandler(async (request, response) => {
  const payload = updateMyProfileSchema.parse(request.body || {});
  const user = request.auth.user;

  const data = {};
  if (payload.username !== undefined) data.username = payload.username.trim();
  if (payload.email !== undefined) data.email = payload.email.trim().toLowerCase();
  if (payload.displayName !== undefined) data.displayName = payload.displayName.trim();

  const updated = await prisma.user.update({
    where: { id: user.id },
    data,
  });

  response.json({ user: serializeUser(updated) });
}));

app.patch("/api/auth/me/password", requireAuth(), asyncHandler(async (request, response) => {
  const payload = updateMyPasswordSchema.parse(request.body || {});
  const user = await prisma.user.findUnique({ where: { id: request.auth.user.id } });
  const valid = await verifyPassword(payload.currentPassword, user.passwordHash);
  if (!valid) {
    response.status(400).json({ error: "Current password is incorrect." });
    return;
  }

  await prisma.user.update({
    where: { id: user.id },
    data: { passwordHash: await hashPassword(payload.nextPassword) },
  });

  response.json({ ok: true });
}));

app.get("/api/me/providers", requireAuth(), forbidAdminPlayback(), asyncHandler(async (request, response) => {
  const providersForUser = await getEnabledProvidersForUser(request.auth.user);
  response.json({
    providers: providersForUser.filter((provider) => provider.allowed),
  });
}));

app.get("/api/me/favorites", requireAuth(), forbidAdminPlayback(), asyncHandler(async (request, response) => {
  const favorites = await prisma.favorite.findMany({
    where: { userId: request.auth.user.id },
    orderBy: { createdAt: "desc" },
  });
  response.json({ favorites });
}));

app.post("/api/me/favorites", requireAuth(), forbidAdminPlayback(), asyncHandler(async (request, response) => {
  const payload = favoriteSchema.parse(request.body || {});
  await assertProviderAccess(request.auth.user, payload.providerKey);

  const favorite = await prisma.favorite.upsert({
    where: buildFavoriteKey(request.auth.user.id, payload),
    update: {
      mediaType: payload.mediaType,
      title: payload.title,
      posterUrl: sanitizeNullable(payload.posterUrl),
      detailUrl: sanitizeNullable(payload.detailUrl),
      seasonLabel: sanitizeNullable(payload.seasonLabel),
    },
    create: {
      userId: request.auth.user.id,
      providerKey: payload.providerKey,
      mediaType: payload.mediaType,
      title: payload.title,
      posterUrl: sanitizeNullable(payload.posterUrl),
      itemUrl: payload.itemUrl,
      detailUrl: sanitizeNullable(payload.detailUrl),
      seasonUrl: sanitizeKeyString(payload.seasonUrl),
      seasonLabel: sanitizeNullable(payload.seasonLabel),
      episodeLabel: sanitizeKeyString(payload.episodeLabel),
    },
  });
  response.status(201).json({ favorite });
}));

app.delete("/api/me/favorites/:id", requireAuth(), forbidAdminPlayback(), asyncHandler(async (request, response) => {
  await prisma.favorite.deleteMany({
    where: {
      id: request.params.id,
      userId: request.auth.user.id,
    },
  });
  response.json({ ok: true });
}));

app.get("/api/me/history", requireAuth(), forbidAdminPlayback(), asyncHandler(async (request, response) => {
  const history = await prisma.watchHistory.findMany({
    where: { userId: request.auth.user.id },
    orderBy: { watchedAt: "desc" },
    take: 200,
  });
  response.json({ history });
}));

app.get("/api/me/continue-watching", requireAuth(), forbidAdminPlayback(), asyncHandler(async (request, response) => {
  const items = await prisma.watchProgress.findMany({
    where: {
      userId: request.auth.user.id,
      isCompleted: false,
    },
    orderBy: { lastWatchedAt: "desc" },
    take: 100,
  });
  response.json({ items });
}));

app.get("/api/me/progress", requireAuth(), forbidAdminPlayback(), asyncHandler(async (request, response) => {
  const providerKey = String(request.query.providerKey || "").trim();
  const itemUrl = String(request.query.itemUrl || "").trim();
  const where = { userId: request.auth.user.id };
  if (providerKey) where.providerKey = providerKey;
  if (itemUrl) where.itemUrl = itemUrl;
  const progress = await prisma.watchProgress.findMany({
    where,
    orderBy: { lastWatchedAt: "desc" },
    take: 200,
  });
  response.json({ progress });
}));

app.get("/api/me/source-preference", requireAuth(), forbidAdminPlayback(), asyncHandler(async (request, response) => {
  const providerKey = String(request.query.providerKey || "").trim();
  const mediaType = String(request.query.mediaType || "unknown").trim();
  const title = String(request.query.title || "").trim();
  if (!providerKey || !title) {
    response.status(400).json({ error: "Missing providerKey or title." });
    return;
  }

  const preference = await prisma.userSourcePreference.findUnique({
    where: {
      userId_providerKey_mediaType_title: {
        userId: request.auth.user.id,
        providerKey,
        mediaType,
        title,
      },
    },
  });

  response.json({ preference });
}));

app.post("/api/me/source-preference", requireAuth(), forbidAdminPlayback(), asyncHandler(async (request, response) => {
  const payload = sourcePreferenceSchema.parse(request.body || {});
  await assertProviderAccess(request.auth.user, payload.providerKey);

  const preference = await prisma.userSourcePreference.upsert({
    where: {
      userId_providerKey_mediaType_title: {
        userId: request.auth.user.id,
        providerKey: payload.providerKey,
        mediaType: payload.mediaType,
        title: payload.title,
      },
    },
    update: {
      sourceLabel: payload.sourceLabel,
      lastSelectedAt: new Date(),
    },
    create: {
      userId: request.auth.user.id,
      providerKey: payload.providerKey,
      mediaType: payload.mediaType,
      title: payload.title,
      sourceLabel: payload.sourceLabel,
      lastSelectedAt: new Date(),
    },
  });

  response.json({ preference });
}));

app.delete("/api/me/progress", requireAuth(), forbidAdminPlayback(), asyncHandler(async (request, response) => {
  const providerKey = String(request.body?.providerKey || "").trim();
  const itemUrl = String(request.body?.itemUrl || "").trim();
  if (!providerKey || !itemUrl) {
    response.status(400).json({ error: "Missing providerKey or itemUrl." });
    return;
  }
  const seasonUrl = String(request.body?.seasonUrl || "").trim();
  const episodeLabel = String(request.body?.episodeLabel || "").trim();
  await prisma.watchProgress.deleteMany({
    where: { userId: request.auth.user.id, providerKey, itemUrl, seasonUrl, episodeLabel },
  });
  response.json({ ok: true });
}));

app.put("/api/me/progress", requireAuth(), forbidAdminPlayback(), asyncHandler(async (request, response) => {
  const payload = progressSchema.parse(request.body || {});
  await assertProviderAccess(request.auth.user, payload.providerKey);

  const durationSeconds = Math.max(0, payload.durationSeconds);
  const positionSeconds = Math.max(0, payload.positionSeconds);
  const progressPercent = durationSeconds > 0 ? Math.min(100, (positionSeconds / durationSeconds) * 100) : 0;
  const isCompleted = durationSeconds > 0 && (progressPercent >= 95 || durationSeconds - positionSeconds <= 90);

  const progress = await prisma.watchProgress.upsert({
    where: buildProgressKey(request.auth.user.id, payload),
    update: {
      mediaType: payload.mediaType,
      title: payload.title,
      posterUrl: sanitizeNullable(payload.posterUrl),
      detailUrl: sanitizeNullable(payload.detailUrl),
      seasonLabel: sanitizeNullable(payload.seasonLabel),
      sourceLabel: sanitizeNullable(payload.sourceLabel),
      durationSeconds,
      positionSeconds,
      progressPercent,
      isCompleted,
      lastWatchedAt: new Date(),
    },
    create: {
      userId: request.auth.user.id,
      providerKey: payload.providerKey,
      mediaType: payload.mediaType,
      title: payload.title,
      posterUrl: sanitizeNullable(payload.posterUrl),
      itemUrl: payload.itemUrl,
      detailUrl: sanitizeNullable(payload.detailUrl),
      seasonUrl: sanitizeKeyString(payload.seasonUrl),
      seasonLabel: sanitizeNullable(payload.seasonLabel),
      episodeLabel: sanitizeKeyString(payload.episodeLabel),
      sourceLabel: sanitizeNullable(payload.sourceLabel),
      durationSeconds,
      positionSeconds,
      progressPercent,
      isCompleted,
      lastWatchedAt: new Date(),
    },
  });

  if (payload.event !== "progress" || positionSeconds >= 60) {
    await prisma.watchHistory.create({
      data: {
        userId: request.auth.user.id,
        providerKey: payload.providerKey,
        mediaType: payload.mediaType,
        title: payload.title,
        posterUrl: sanitizeNullable(payload.posterUrl),
        itemUrl: payload.itemUrl,
        detailUrl: sanitizeNullable(payload.detailUrl),
        seasonUrl: sanitizeNullable(payload.seasonUrl),
        seasonLabel: sanitizeNullable(payload.seasonLabel),
        episodeLabel: sanitizeNullable(payload.episodeLabel),
        sourceLabel: sanitizeNullable(payload.sourceLabel),
        positionSeconds,
        durationSeconds,
      },
    });
  }

  response.json({ progress });
}));

app.get("/api/admin/dashboard", requireAuth(), requireRole(UserRole.ADMIN), asyncHandler(async (_request, response) => {
  const onlineThreshold = getOnlineThresholdDate();
  const [providerRows, recentChecks, userCounts, onlineUsers, activeSessions, recentWatching, recentLogins] = await Promise.all([
    prisma.provider.findMany({
      include: {
        healthChecks: {
          take: 1,
          orderBy: { checkedAt: "desc" },
        },
      },
      orderBy: [{ sortOrder: "asc" }, { key: "asc" }],
    }),
    prisma.providerHealthCheck.findMany({
      include: { provider: true },
      orderBy: { checkedAt: "desc" },
      take: 12,
    }),
    prisma.user.groupBy({
      by: ["status"],
      _count: true,
    }),
    prisma.user.count({
      where: {
        role: UserRole.USER,
        lastSeenAt: { gte: onlineThreshold },
      },
    }),
    prisma.userSession.count({
      where: { lastSeenAt: { gte: onlineThreshold } },
    }),
    prisma.user.findMany({
      where: {
        role: UserRole.USER,
        lastSeenAt: { gte: onlineThreshold },
      },
      select: {
        id: true,
        username: true,
        email: true,
        displayName: true,
        lastSeenAt: true,
        lastLoginAt: true,
        status: true,
      },
      orderBy: { lastSeenAt: "desc" },
      take: 20,
    }),
    prisma.watchHistory.findMany({
      take: 10,
      orderBy: { watchedAt: "desc" },
      include: {
        user: {
          select: { id: true, username: true, displayName: true },
        },
      },
    }),
    prisma.user.findMany({
      where: { role: UserRole.USER },
      select: {
        id: true,
        username: true,
        email: true,
        displayName: true,
        lastLoginAt: true,
        lastSeenAt: true,
        status: true,
      },
      orderBy: { lastLoginAt: "desc" },
      take: 10,
    }),
  ]);

  const totalUsers = userCounts.reduce((sum, item) => sum + item._count, 0);

  response.json({
    providers: providerRows.map((provider) => ({
      key: provider.key,
      name: provider.name,
      isEnabled: provider.isEnabled,
      lastCheckedAt: provider.lastCheckedAt,
      latestHealth: provider.healthChecks[0] || null,
    })),
    users: {
      total: totalUsers,
      active: userCounts.find((item) => item.status === UserStatus.ACTIVE)?._count || 0,
      disabled: userCounts.find((item) => item.status === UserStatus.DISABLED)?._count || 0,
      online: onlineUsers,
      activeSessions,
    },
    onlineUsers,
    recentProviderChecks: recentChecks,
    recentWatching,
    recentLogins,
  });
}));

app.get("/api/admin/online-users", requireAuth(), requireRole(UserRole.ADMIN), asyncHandler(async (_request, response) => {
  const onlineUsers = await prisma.user.findMany({
    where: {
      role: UserRole.USER,
      lastSeenAt: { gte: getOnlineThresholdDate() },
    },
    select: {
      id: true,
      username: true,
      email: true,
      displayName: true,
      lastSeenAt: true,
      lastLoginAt: true,
      status: true,
    },
    orderBy: { lastSeenAt: "desc" },
    take: 50,
  });
  response.json({ users: onlineUsers });
}));

app.get("/api/admin/audit-logs", requireAuth(), requireRole(UserRole.ADMIN), asyncHandler(async (_request, response) => {
  const logs = await prisma.auditLog.findMany({
    include: {
      actorUser: {
        select: {
          id: true,
          username: true,
          email: true,
          displayName: true,
        },
      },
    },
    orderBy: { createdAt: "desc" },
    take: 200,
  });
  response.json({ logs });
}));

app.get("/api/admin/providers", requireAuth(), requireRole(UserRole.ADMIN), asyncHandler(async (_request, response) => {
  const providerRows = await prisma.provider.findMany({
    include: {
      healthChecks: {
        take: 10,
        orderBy: { checkedAt: "desc" },
      },
    },
    orderBy: [{ sortOrder: "asc" }, { key: "asc" }],
  });
  response.json({ providers: providerRows });
}));

app.patch("/api/admin/providers/:providerKey", requireAuth(), requireRole(UserRole.ADMIN), asyncHandler(async (request, response) => {
  const payload = toggleProviderSchema.parse(request.body || {});
  const provider = await prisma.provider.update({
    where: { key: request.params.providerKey },
    data: { isEnabled: payload.isEnabled },
  });
  await createAuditLog(request.auth.user.id, "provider.toggle", payload, null);
  response.json({ provider });
}));

app.get("/api/admin/users", requireAuth(), requireRole(UserRole.ADMIN), asyncHandler(async (_request, response) => {
  const users = await prisma.user.findMany({
    orderBy: { createdAt: "desc" },
    include: {
      providerAccess: {
        include: { provider: true },
      },
    },
  });

  response.json({
    users: users.map((user) => ({
      ...serializeUser(user),
      providerAccess: user.providerAccess.map((entry) => ({
        providerKey: entry.provider.key,
        isEnabled: entry.isEnabled,
      })),
    })),
  });
}));

app.post("/api/admin/users", requireAuth(), requireRole(UserRole.ADMIN), asyncHandler(async (request, response) => {
  const payload = createUserSchema.parse(request.body || {});
  const user = await prisma.user.create({
    data: {
      username: payload.username.trim(),
      email: payload.email.trim().toLowerCase(),
      displayName: payload.displayName.trim(),
      passwordHash: await hashPassword(payload.password),
      role: UserRole.USER,
      status: UserStatus.ACTIVE,
    },
  });

  const providerRows = await prisma.provider.findMany();
  await prisma.userProviderPermission.createMany({
    data: providerRows.map((provider) => ({
      userId: user.id,
      providerId: provider.id,
      isEnabled: true,
    })),
    skipDuplicates: true,
  });

  await createAuditLog(request.auth.user.id, "user.create", {
    username: user.username,
    email: user.email,
  }, user.id);

  response.status(201).json({ user: serializeUser(user) });
}));

app.get("/api/admin/users/:id", requireAuth(), requireRole(UserRole.ADMIN), asyncHandler(async (request, response) => {
  const user = await prisma.user.findUnique({
    where: { id: request.params.id },
  });
  if (!user) {
    response.status(404).json({ error: "User not found." });
    return;
  }

  const [favorites, history, progress, sessions, providerAccess] = await Promise.all([
    prisma.favorite.findMany({
      where: { userId: user.id },
      orderBy: { createdAt: "desc" },
      take: 100,
    }),
    prisma.watchHistory.findMany({
      where: { userId: user.id },
      orderBy: { watchedAt: "desc" },
      take: 100,
    }),
    prisma.watchProgress.findMany({
      where: { userId: user.id },
      orderBy: { lastWatchedAt: "desc" },
      take: 100,
    }),
    prisma.userSession.findMany({
      where: { userId: user.id },
      orderBy: { createdAt: "desc" },
    }),
    prisma.userProviderPermission.findMany({
      where: { userId: user.id },
      include: { provider: true },
      orderBy: { provider: { sortOrder: "asc" } },
    }),
  ]);

  response.json({
    user: serializeUser(user),
    favorites,
    history,
    progress,
    sessions,
    providerAccess: providerAccess.map((entry) => ({
      providerKey: entry.provider.key,
      providerName: entry.provider.name,
      isEnabled: entry.isEnabled,
      globalEnabled: entry.provider.isEnabled,
    })),
  });
}));

app.patch("/api/admin/users/:id", requireAuth(), requireRole(UserRole.ADMIN), asyncHandler(async (request, response) => {
  const payload = updateUserSchema.parse(request.body || {});
  const data = {};
  if (payload.username !== undefined) data.username = payload.username.trim();
  if (payload.email !== undefined) data.email = payload.email.trim().toLowerCase();
  if (payload.displayName !== undefined) data.displayName = payload.displayName.trim();
  if (payload.status !== undefined) data.status = payload.status;

  const user = await prisma.user.update({
    where: { id: request.params.id },
    data,
  });

  await createAuditLog(request.auth.user.id, "user.update", payload, user.id);
  response.json({ user: serializeUser(user) });
}));

app.patch("/api/admin/users/:id/password", requireAuth(), requireRole(UserRole.ADMIN), asyncHandler(async (request, response) => {
  const payload = adminResetPasswordSchema.parse(request.body || {});
  const target = await prisma.user.findUnique({
    where: { id: request.params.id },
  });
  if (!target) {
    response.status(404).json({ error: "User not found." });
    return;
  }
  if (target.role === UserRole.ADMIN) {
    response.status(400).json({ error: "Use the personal password screen for admin accounts." });
    return;
  }

  await prisma.user.update({
    where: { id: target.id },
    data: {
      passwordHash: await hashPassword(payload.nextPassword),
    },
  });

  await createAuditLog(request.auth.user.id, "user.password.reset", {}, target.id);
  response.json({ ok: true });
}));

app.delete("/api/admin/users/:id", requireAuth(), requireRole(UserRole.ADMIN), asyncHandler(async (request, response) => {
  const target = await prisma.user.findUnique({ where: { id: request.params.id } });
  if (!target) {
    response.status(404).json({ error: "User not found." });
    return;
  }
  if (target.role === UserRole.ADMIN) {
    response.status(400).json({ error: "Admin user cannot be deleted." });
    return;
  }

  await prisma.user.delete({ where: { id: target.id } });
  await createAuditLog(request.auth.user.id, "user.delete", {
    username: target.username,
    email: target.email,
  }, target.id);
  response.json({ ok: true });
}));

app.get("/api/admin/users/:id/providers", requireAuth(), requireRole(UserRole.ADMIN), asyncHandler(async (request, response) => {
  const entries = await prisma.userProviderPermission.findMany({
    where: { userId: request.params.id },
    include: { provider: true },
    orderBy: { provider: { sortOrder: "asc" } },
  });
  response.json({
    providers: entries.map((entry) => ({
      providerKey: entry.provider.key,
      providerName: entry.provider.name,
      isEnabled: entry.isEnabled,
      globalEnabled: entry.provider.isEnabled,
    })),
  });
}));

app.put("/api/admin/users/:id/providers/:providerKey", requireAuth(), requireRole(UserRole.ADMIN), asyncHandler(async (request, response) => {
  const payload = toggleProviderSchema.parse(request.body || {});
  const provider = await prisma.provider.findUnique({
    where: { key: request.params.providerKey },
  });
  if (!provider) {
    response.status(404).json({ error: "Provider not found." });
    return;
  }

  const entry = await prisma.userProviderPermission.upsert({
    where: {
      userId_providerId: {
        userId: request.params.id,
        providerId: provider.id,
      },
    },
    update: { isEnabled: payload.isEnabled },
    create: {
      userId: request.params.id,
      providerId: provider.id,
      isEnabled: payload.isEnabled,
    },
  });

  await createAuditLog(request.auth.user.id, "user.provider.toggle", {
    providerKey: provider.key,
    isEnabled: payload.isEnabled,
  }, request.params.id);

  response.json({ permission: entry });
}));

app.get("/api/admin/users/:id/favorites", requireAuth(), requireRole(UserRole.ADMIN), asyncHandler(async (request, response) => {
  const favorites = await prisma.favorite.findMany({
    where: { userId: request.params.id },
    orderBy: { createdAt: "desc" },
  });
  response.json({ favorites });
}));

app.get("/api/admin/users/:id/history", requireAuth(), requireRole(UserRole.ADMIN), asyncHandler(async (request, response) => {
  const history = await prisma.watchHistory.findMany({
    where: { userId: request.params.id },
    orderBy: { watchedAt: "desc" },
  });
  response.json({ history });
}));

app.get("/api/admin/users/:id/progress", requireAuth(), requireRole(UserRole.ADMIN), asyncHandler(async (request, response) => {
  const progress = await prisma.watchProgress.findMany({
    where: { userId: request.params.id },
    orderBy: { lastWatchedAt: "desc" },
  });
  response.json({ progress });
}));

app.get("/api/admin/users/:id/sessions", requireAuth(), requireRole(UserRole.ADMIN), asyncHandler(async (request, response) => {
  const sessions = await prisma.userSession.findMany({
    where: { userId: request.params.id },
    orderBy: { createdAt: "desc" },
  });
  response.json({ sessions });
}));

app.get("/api/search", requireAuth(), forbidAdminPlayback(), asyncHandler(async (request, response) => {
  const q = String(request.query.q || "").trim();
  const providerFilter = String(request.query.provider || "all");
  if (!q) {
    response.status(400).json({ error: "Missing q parameter." });
    return;
  }

  const availableProviders = await getEnabledProvidersForUser(request.auth.user);
  const allowedKeys = availableProviders.filter((provider) => provider.allowed).map((provider) => provider.key);
  const providerNames = providerFilter === "all" ? allowedKeys : [providerFilter];

  if (providerFilter !== "all") {
    await assertProviderAccess(request.auth.user, providerFilter);
  }

  const settled = await Promise.allSettled(
    providerNames.map(async (providerName) => ({
      provider: providerName,
      items: await getProvider(providerName).search(q),
    })),
  );
  const results = settled.map((result, index) => {
    const provider = providerNames[index];
    if (result.status === "fulfilled") {
      return result.value;
    }
    console.error(`Search failed for provider ${provider}:`, result.reason);
    return {
      provider,
      items: [],
      error: result.reason?.message || "Search failed.",
    };
  });

  response.json({ query: q, results });
}));

app.get("/api/item", requireAuth(), forbidAdminPlayback(), asyncHandler(async (request, response) => {
  const providerName = String(request.query.provider || "");
  const title = String(request.query.title || "");
  const mediaType = String(request.query.mediaType || "unknown");
  const posterUrl = String(request.query.posterUrl || "");
  const url = String(request.query.url || "");

  if (!providerName || !url) {
    response.status(400).json({ error: "Missing provider or url." });
    return;
  }

  const provider = getProvider(providerName);
  await assertProviderAccess(request.auth.user, providerName);
  const item = await provider.getItem({ title, mediaType, posterUrl, url, provider: providerName });
  response.json(item);
}));

app.get("/api/episodes", requireAuth(), forbidAdminPlayback(), asyncHandler(async (request, response) => {
  const providerName = String(request.query.provider || "");
  const sourceUrl = String(request.query.sourceUrl || "");
  if (!providerName || !sourceUrl) {
    response.status(400).json({ error: "Missing provider or sourceUrl." });
    return;
  }

  await assertProviderAccess(request.auth.user, providerName);
  const provider = getProvider(providerName);
  const episodes = await provider.getEpisodes(sourceUrl);
  response.json({ provider: providerName, sourceUrl, episodes });
}));

app.get("/api/sources", requireAuth(), forbidAdminPlayback(), asyncHandler(async (request, response) => {
  const providerName = String(request.query.provider || "");
  const sourceUrl = String(request.query.sourceUrl || "");
  const episode = String(request.query.episode || "");
  const preferredLabel = String(request.query.preferredLabel || "");

  if (!providerName || !sourceUrl) {
    response.status(400).json({ error: "Missing provider or sourceUrl." });
    return;
  }

  await assertProviderAccess(request.auth.user, providerName);
  const provider = getProvider(providerName);
  const rawStreams = episode
    ? await provider.getEpisodeStreams(sourceUrl, episode)
    : [];

  response.setHeader("Content-Type", "application/x-ndjson");
  response.setHeader("Cache-Control", "no-cache");
  response.setHeader("X-Accel-Buffering", "no");

  await streamCheckedSources(rawStreams, preferredLabel, (source) => {
    response.write(JSON.stringify(source) + "\n");
  });

  response.end();
}));

app.post("/api/check-sources", requireAuth(), forbidAdminPlayback(), asyncHandler(async (request, response) => {
  const providerName = String(request.body?.provider || "");
  const streams = Array.isArray(request.body?.streams) ? request.body.streams : null;
  const preferredLabel = String(request.body?.preferredLabel || "");
  if (!streams) {
    response.status(400).json({ error: "Missing streams array." });
    return;
  }
  if (providerName) {
    await assertProviderAccess(request.auth.user, providerName);
  }

  response.setHeader("Content-Type", "application/x-ndjson");
  response.setHeader("Cache-Control", "no-cache");
  response.setHeader("X-Accel-Buffering", "no");

  await streamCheckedSources(streams, preferredLabel, (source) => {
    response.write(JSON.stringify(source) + "\n");
  });

  response.end();
}));

app.get("/api/stream", requireAuth(), forbidAdminPlayback(), asyncHandler(async (request, response) => {
  await handleStreamProxy(request, response);
}));

app.get("/api/poster", requireAuth(), asyncHandler(async (request, response) => {
  await handlePosterProxy(request, response);
}));

app.use((error, _request, response, _next) => {
  if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === "P2002") {
    response.status(409).json({ error: "Duplicate value." });
    return;
  }
  if (error?.name === "ZodError") {
    response.status(400).json({
      error: "Validation failed.",
      details: error.issues,
    });
    return;
  }

  const statusCode = error.statusCode || 500;
  response.status(statusCode).json({
    error: error.message || "Internal Server Error",
  });
});

async function main() {
  await ensureBootstrapped();
  startMonitoring();
  app.listen(PORT, () => {
    console.log(`StreamHub server listening on port ${PORT}`);
  });
}

main().catch((error) => {
  console.error("Failed to start server:", error);
  process.exit(1);
});
