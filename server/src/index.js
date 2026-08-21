import "dotenv/config";
import http from "node:http";
import express from "express";
import morgan from "morgan";
import cookieParser from "cookie-parser";
import { Prisma, UserRole, UserStatus } from "../generated/prisma/index.js";
import { ADMIN_PASSWORD, CONTINUE_SCAN_LIMIT, PORT } from "./config.js";
import { rateLimit } from "./rate-limit.js";
import { providers } from "./providers/index.js";
import { streamCheckedSources, handleAdCuts, handleCleanManifest, handlePosterProxy, handleStreamProxy } from "./stream.js";
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
import { attachRealtime, broadcast } from "./realtime.js";
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

// One hop: nginx in front of this container. Without it every request appears
// to come from the proxy, which makes the recorded session IP useless and would
// have one noisy caller rate-limit everybody.
app.set("trust proxy", 1);

const API_VERSION = 1;
const VERSION_PREFIX = `/api/v${API_VERSION}`;

/**
 * Clients pin themselves to /api/v1 so the unversioned paths stay free to
 * change without breaking a build that is already installed. Nothing pushes
 * updates here — a sideloaded phone or TV build stays on the device until
 * someone reinstalls it by hand — so the version has to exist before the first
 * client ships, not once something needs to break.
 *
 * A rewrite rather than a Router leaves all 44 route registrations untouched.
 * Express keeps req.originalUrl, so logs and error reports still show the path
 * the client actually asked for.
 */
app.use((request, _response, next) => {
  if (request.url.startsWith(`${VERSION_PREFIX}/`)) {
    request.url = "/api" + request.url.slice(VERSION_PREFIX.length);
  }
  next();
});

app.use(morgan("dev"));
app.use(express.json());
app.use(cookieParser());

/**
 * Native clients announce themselves with this header; the web app does not.
 * The web login form is shared by both portals, and an admin signing in there
 * is heading for the admin console rather than a player, so it must keep
 * working.
 */
function getClientKind(request) {
  return String(request.get("x-streamhub-client") || "").trim();
}

const ADMIN_CLIENT_REFUSAL =
  "Administrator accounts cannot sign in to a playback client. Use a viewer account.";

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
    // Seeding an administrator whose password is "admin" on a box that is about
    // to be reachable from the internet is not a default worth having. This only
    // fires on a brand new database; an instance that already has an admin is
    // unaffected.
    if (!ADMIN_PASSWORD || ADMIN_PASSWORD === "admin" || ADMIN_PASSWORD.length < 8) {
      console.error(
        [
          "",
          "StreamHub will not start: there is no administrator yet, and",
          "ADMIN_PASSWORD is missing or too weak to create one with.",
          "",
          "Set it in .env before first boot:",
          "",
          "  ADMIN_PASSWORD=$(openssl rand -base64 24)",
          "",
        ].join("\n"),
      );
      process.exit(1);
    }

    await prisma.user.create({
      data: {
        username: "admin",
        email: "admin@local",
        displayName: "Administrator",
        passwordHash: await hashPassword(ADMIN_PASSWORD),
        role: UserRole.ADMIN,
      },
    });
  }
}

app.get("/api/health", (_request, response) => {
  response.json({ ok: true, apiVersion: API_VERSION });
});

// The only unauthenticated ways in, and the only ones worth guessing at. Only
// failures count: everyone in a household shares one public address, so a
// successful sign-in must not spend anyone else's allowance.
const failed = (status) => status === 401 || status === 403;
const loginLimiter = rateLimit({ name: "login", windowMs: 15 * 60_000, max: 20, countWhen: failed });
const refreshLimiter = rateLimit({ name: "refresh", windowMs: 15 * 60_000, max: 20, countWhen: failed });

app.post("/api/auth/login", loginLimiter, asyncHandler(async (request, response) => {
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

  // An admin authenticates perfectly well and then 403s on every content route,
  // so a client that identifies itself is refused here rather than handed a
  // session that fails on every screen it has.
  if (getClientKind(request) && user.role === UserRole.ADMIN) {
    response.status(403).json({ error: ADMIN_CLIENT_REFUSAL });
    return;
  }

  const session = await issueSession(user, request);
  response.json({
    user: serializeUser(user),
    ...session,
  });
}));

app.post("/api/auth/refresh", refreshLimiter, asyncHandler(async (request, response) => {
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

  // Same rule as login, so an admin token issued elsewhere cannot be carried
  // into a client by refreshing it.
  if (getClientKind(request) && session.user.role === UserRole.ADMIN) {
    response.status(403).json({ error: ADMIN_CLIENT_REFUSAL });
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

  // Callers building a search filter want only what they can actually search.
  // A status view wants the opposite: a provider that is turned off or blocked
  // is exactly what it needs to show, because "missing from the list" and
  // "found nothing" look identical otherwise.
  const includeUnavailable = String(request.query.all || "") === "true";

  response.json({
    providers: includeUnavailable
      ? providersForUser
      : providersForUser.filter((provider) => provider.allowed),
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
  broadcast(request.auth.user.id, { type: "favorites", action: "added", id: favorite.id });
  response.status(201).json({ favorite });
}));

app.delete("/api/me/favorites/:id", requireAuth(), forbidAdminPlayback(), asyncHandler(async (request, response) => {
  await prisma.favorite.deleteMany({
    where: {
      id: request.params.id,
      userId: request.auth.user.id,
    },
  });
  broadcast(request.auth.user.id, { type: "favorites", action: "removed", id: request.params.id });
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
  const rows = await prisma.watchProgress.findMany({
    where: { userId: request.auth.user.id },
    orderBy: { lastWatchedAt: "desc" },
    take: CONTINUE_SCAN_LIMIT,
  });

  // One card per title, not per episode. Progress is stored per episode, so a
  // series being worked through buried the shelf: 39 rows collapsed to 14
  // titles in practice, one show alone holding 21 of them.
  //
  // Rows arrive newest-first, so the first one seen for a title is what the
  // user was last watching, and insertion order already ranks the groups.
  // Keyed on title rather than itemUrl because the providers disagree on what
  // an "item" is: movieffm keeps one page per show, but dramasq gives every
  // episode its own detail page (…342, …343, …344 for one drama), so grouping
  // by URL left that show holding four cards.
  //
  // mediaType stays out of the key: it is written as "unknown" on some paths,
  // and one show here carries both "tv" and "unknown" rows, which split it in
  // two the moment the field took part.
  const groups = new Map();
  for (const row of rows) {
    const key = `${row.providerKey}::${row.title}`;
    const group = groups.get(key);
    if (group) {
      group.episodesTouched += 1;
      if (row.isCompleted) group.episodesCompleted += 1;
      continue;
    }
    groups.set(key, {
      latest: row,
      episodesTouched: 1,
      episodesCompleted: row.isCompleted ? 1 : 0,
    });
  }

  const items = [];
  for (const { latest, episodesTouched, episodesCompleted } of groups.values()) {
    // Derived from the row rather than trusted from mediaType, for the same
    // reason: only episodes carry a label, films never do.
    const isSeries = Boolean(latest.episodeLabel) || latest.mediaType === "tv";

    // A finished film has nothing left to continue, so it drops off. A finished
    // episode does not mean a finished show: the title stays and points at
    // what comes next, which the player resolves from the episode list on
    // arrival. Filtering completed rows out entirely used to make a series
    // vanish the moment an episode ended.
    if (latest.isCompleted && !isSeries) continue;

    items.push({ ...latest, nextUp: latest.isCompleted, episodesTouched, episodesCompleted });
  }

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
  const where = { userId: request.auth.user.id, providerKey, itemUrl };
  // The continue shelf shows one card per title, so its dismiss button has to
  // clear the whole title; without this it would drop a single episode and the
  // card would reappear pointing at the next-newest one.
  if (String(request.body?.scope || "") === "title") {
    // Match how the continue shelf groups: one card stands for a whole title,
    // and for providers that page per episode that spans several itemUrls.
    delete where.itemUrl;
    where.title = String(request.body?.title || "").trim();
  } else {
    where.seasonUrl = String(request.body?.seasonUrl || "").trim();
    where.episodeLabel = String(request.body?.episodeLabel || "").trim();
  }
  await prisma.watchProgress.deleteMany({ where });
  broadcast(request.auth.user.id, { type: "progress", action: "removed" });
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

  // History is a log of viewing sessions, not of heartbeats. This used to be an
  // `||`, which meant every routine progress tick past the first minute appended
  // a row — a player reporting every fifteen seconds turned one episode into
  // hundreds of entries, and the list was unusable long before the table was.
  //
  // A row is worth writing when something actually happened: the viewer paused,
  // finished, switched source, or opened it again. The minute threshold still
  // keeps out sessions that were abandoned immediately, except for "ended",
  // which is worth recording however short the thing was.
  const isMilestone = payload.event !== "progress";
  if (isMilestone && (positionSeconds >= 60 || payload.event === "ended")) {
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

  broadcast(request.auth.user.id, {
    type: "progress",
    action: "updated",
    // A history row is only written for meaningful events, so tell listeners
    // whether the history view needs refreshing too.
    history: payload.event !== "progress" || positionSeconds >= 60,
  });
  response.json({ progress });
}));

app.get("/api/admin/dashboard", requireAuth(), requireRole(UserRole.ADMIN), asyncHandler(async (_request, response) => {
  const onlineThreshold = getOnlineThresholdDate();
  const [providerRows, recentChecks, userCounts, onlineUsers, activeSessions, onlineUserList, recentWatching, recentLogins] = await Promise.all([
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
      orderBy: { lastLoginAt: { sort: "desc", nulls: "last" } },
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
    onlineUsers: onlineUserList,
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

  // "all", one key, or several separated by commas. Narrowing on the server
  // rather than filtering the results afterwards is the point: a provider the
  // caller excluded is never scraped, so the search also finishes sooner.
  const requested = providerFilter === "all"
    ? allowedKeys
    : providerFilter.split(",").map((key) => key.trim()).filter(Boolean);

  for (const key of requested) {
    await assertProviderAccess(request.auth.user, key);
  }

  // Deduplicated, and empty means the caller asked for nothing rather than
  // everything — answering with every provider would be the opposite of what
  // was asked.
  const providerNames = [...new Set(requested)];

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

app.get("/api/manifest", requireAuth(), forbidAdminPlayback(), asyncHandler(async (request, response) => {
  await handleCleanManifest(request, response);
}));

app.get("/api/ad-cuts", requireAuth(), forbidAdminPlayback(), asyncHandler(async (request, response) => {
  await handleAdCuts(request, response);
}));

app.get("/api/poster", requireAuth(), forbidAdminPlayback(), asyncHandler(async (request, response) => {
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

  const server = http.createServer(app);
  attachRealtime(server);
  server.listen(PORT, () => {
    console.log(`StreamHub server listening on port ${PORT}`);
  });
}

main().catch((error) => {
  console.error("Failed to start server:", error);
  process.exit(1);
});
