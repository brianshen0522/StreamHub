import { prisma } from "./db.js";

/**
 * Taking the whole instance somewhere else.
 *
 * Everything a person's account *is* — who they are, what they can reach, and
 * everything they have watched — packed into one file and put back on another
 * server. Admin only, and audited, because the file it produces is the most
 * sensitive thing this application can emit.
 *
 * **The export contains password hashes.** Not plaintext — the database has
 * never held plaintext — but a hash is still credential material, and carrying
 * it is the entire point: without it, a restore would leave every account
 * locked out and needing a new password. Treat the file the way the database
 * itself is treated.
 *
 * Three things are deliberately left out:
 *
 * - **Sessions.** Refresh token hashes are live credentials, and a session
 *   restored onto a different host is worthless anyway — everyone signs in
 *   again after a move. Leaving them out makes the file that much less useful
 *   to anyone who should not have it.
 * - **Provider health checks.** Telemetry about a specific machine's network,
 *   regenerated within thirty seconds of the new instance starting.
 * - **The audit log.** It is a record of what administrators did, and a record
 *   that can be imported is a record that can be rewritten. Losing it on a move
 *   is a smaller problem than being able to forge it.
 */

export const BACKUP_FORMAT = "streamhub.backup";
export const BACKUP_VERSION = 1;

/** Everything, nested under the account it belongs to. */
export async function exportEverything() {
  const [users, providers] = await Promise.all([
    prisma.user.findMany({
      orderBy: { createdAt: "asc" },
      include: {
        favorites: { orderBy: { createdAt: "asc" } },
        watchProgress: { orderBy: { lastWatchedAt: "asc" } },
        watchHistory: { orderBy: { watchedAt: "asc" } },
        sourcePreferences: { orderBy: { lastSelectedAt: "asc" } },
        providerAccess: { include: { provider: { select: { key: true } } } },
      },
    }),
    prisma.provider.findMany({ orderBy: { sortOrder: "asc" } }),
  ]);

  return {
    format: BACKUP_FORMAT,
    version: BACKUP_VERSION,
    generatedAt: new Date().toISOString(),
    providers: providers.map((provider) => ({
      key: provider.key,
      name: provider.name,
      isEnabled: provider.isEnabled,
      sortOrder: provider.sortOrder,
    })),
    users: users.map((user) => ({
      username: user.username,
      email: user.email,
      // The hash, never a password. See the note at the top of this file.
      passwordHash: user.passwordHash,
      role: user.role,
      status: user.status,
      displayName: user.displayName,
      lastLoginAt: user.lastLoginAt,
      lastSeenAt: user.lastSeenAt,
      createdAt: user.createdAt,
      providerAccess: user.providerAccess.map((row) => ({
        providerKey: row.provider.key,
        isEnabled: row.isEnabled,
      })),
      favorites: user.favorites.map(withoutKeys),
      watchProgress: user.watchProgress.map(withoutKeys),
      watchHistory: user.watchHistory.map(withoutKeys),
      sourcePreferences: user.sourcePreferences.map(withoutKeys),
    })),
  };
}

/** Row ids and foreign keys mean nothing on another instance. */
function withoutKeys(row) {
  const { id, userId, ...rest } = row;
  return rest;
}

export class BackupError extends Error {
  constructor(message) {
    super(message);
    this.statusCode = 400;
  }
}

/**
 * Puts a backup back.
 *
 * **Additive.** Accounts are matched by username and updated in place; rows are
 * matched on the same natural keys the application already enforces and
 * updated or inserted. Nothing is ever deleted. An import onto an empty
 * instance is therefore an exact restore, and an import onto a populated one
 * cannot destroy what is already there — which is the behaviour to have when
 * the alternative is a button that silently wipes a live server.
 *
 * The administrator running the import is skipped. Rewriting the credentials of
 * the account performing the operation, halfway through the operation, is how
 * someone locks themselves out of their own server.
 */
export async function importEverything(document, actingUserId) {
  if (document?.format !== BACKUP_FORMAT) {
    throw new BackupError("That file is not a StreamHub backup.");
  }
  if (Number(document.version) !== BACKUP_VERSION) {
    throw new BackupError(
      `This server reads backup version ${BACKUP_VERSION}; that file is version ${document.version}.`,
    );
  }
  if (!Array.isArray(document.users)) {
    throw new BackupError("That backup has no users in it.");
  }

  const summary = {
    usersCreated: 0,
    usersUpdated: 0,
    usersSkipped: 0,
    favorites: 0,
    watchProgress: 0,
    watchHistory: 0,
    sourcePreferences: 0,
    providerAccess: 0,
    providers: 0,
  };

  for (const provider of document.providers ?? []) {
    if (!provider?.key) continue;
    await prisma.provider.upsert({
      where: { key: provider.key },
      create: {
        key: provider.key,
        name: provider.name ?? provider.key,
        isEnabled: provider.isEnabled ?? true,
        sortOrder: provider.sortOrder ?? 0,
      },
      // Only what a person chose. Health and timestamps belong to this machine.
      update: {
        name: provider.name ?? provider.key,
        isEnabled: provider.isEnabled ?? true,
        sortOrder: provider.sortOrder ?? 0,
      },
    });
    summary.providers += 1;
  }

  const providerIds = new Map(
    (await prisma.provider.findMany({ select: { id: true, key: true } }))
      .map((provider) => [provider.key, provider.id]),
  );

  const acting = actingUserId
    ? await prisma.user.findUnique({ where: { id: actingUserId }, select: { username: true } })
    : null;

  for (const incoming of document.users) {
    if (!incoming?.username || !incoming?.passwordHash) {
      throw new BackupError("A user in that backup has no username or password.");
    }
    if (acting && incoming.username === acting.username) {
      summary.usersSkipped += 1;
      continue;
    }

    const existing = await prisma.user.findUnique({ where: { username: incoming.username } });
    const fields = {
      email: incoming.email ?? `${incoming.username}@imported.local`,
      passwordHash: incoming.passwordHash,
      role: incoming.role === "ADMIN" ? "ADMIN" : "USER",
      status: incoming.status === "DISABLED" ? "DISABLED" : "ACTIVE",
      displayName: incoming.displayName ?? incoming.username,
      lastLoginAt: date(incoming.lastLoginAt),
      lastSeenAt: date(incoming.lastSeenAt),
    };

    const user = existing
      ? await prisma.user.update({ where: { id: existing.id }, data: fields })
      : await prisma.user.create({
          data: { username: incoming.username, ...fields, createdAt: date(incoming.createdAt) ?? undefined },
        });
    if (existing) summary.usersUpdated += 1;
    else summary.usersCreated += 1;

    for (const row of incoming.providerAccess ?? []) {
      const providerId = providerIds.get(row?.providerKey);
      if (!providerId) continue;
      await prisma.userProviderPermission.upsert({
        where: { userId_providerId: { userId: user.id, providerId } },
        create: { userId: user.id, providerId, isEnabled: row.isEnabled ?? true },
        update: { isEnabled: row.isEnabled ?? true },
      });
      summary.providerAccess += 1;
    }

    for (const row of incoming.favorites ?? []) {
      const key = libraryKey(user.id, row);
      if (!key) continue;
      await prisma.favorite.upsert({
        where: { userId_providerKey_itemUrl_seasonUrl_episodeLabel: key },
        create: { ...key, ...favoriteFields(row) },
        update: favoriteFields(row),
      });
      summary.favorites += 1;
    }

    for (const row of incoming.watchProgress ?? []) {
      const key = libraryKey(user.id, row);
      if (!key) continue;
      await prisma.watchProgress.upsert({
        where: { userId_providerKey_itemUrl_seasonUrl_episodeLabel: key },
        create: { ...key, ...progressFields(row) },
        update: progressFields(row),
      });
      summary.watchProgress += 1;
    }

    for (const row of incoming.sourcePreferences ?? []) {
      if (!row?.providerKey || !row?.title || !row?.sourceLabel) continue;
      const key = {
        userId: user.id,
        providerKey: row.providerKey,
        mediaType: row.mediaType ?? "unknown",
        title: row.title,
      };
      await prisma.userSourcePreference.upsert({
        where: { userId_providerKey_mediaType_title: key },
        create: {
          ...key,
          sourceLabel: row.sourceLabel,
          lastSelectedAt: date(row.lastSelectedAt) ?? new Date(),
        },
        update: {
          sourceLabel: row.sourceLabel,
          lastSelectedAt: date(row.lastSelectedAt) ?? new Date(),
        },
      });
      summary.sourcePreferences += 1;
    }

    // History is the one table with no unique key — it is a log, and the same
    // episode legitimately appears in it many times. Matching on when it was
    // watched is what stops importing the same file twice from doubling it.
    for (const row of incoming.watchHistory ?? []) {
      if (!row?.providerKey || !row?.itemUrl) continue;
      const watchedAt = date(row.watchedAt) ?? new Date();
      const already = await prisma.watchHistory.findFirst({
        where: {
          userId: user.id,
          providerKey: row.providerKey,
          itemUrl: row.itemUrl,
          episodeLabel: row.episodeLabel ?? null,
          watchedAt,
        },
        select: { id: true },
      });
      if (already) continue;
      await prisma.watchHistory.create({
        data: {
          userId: user.id,
          providerKey: row.providerKey,
          mediaType: row.mediaType ?? "unknown",
          title: row.title ?? "",
          posterUrl: row.posterUrl ?? null,
          itemUrl: row.itemUrl,
          detailUrl: row.detailUrl ?? null,
          seasonUrl: row.seasonUrl ?? null,
          seasonLabel: row.seasonLabel ?? null,
          episodeLabel: row.episodeLabel ?? null,
          sourceLabel: row.sourceLabel ?? null,
          positionSeconds: int(row.positionSeconds),
          durationSeconds: int(row.durationSeconds),
          watchedAt,
        },
      });
      summary.watchHistory += 1;
    }
  }

  return summary;
}

function libraryKey(userId, row) {
  if (!row?.providerKey || !row?.itemUrl) return null;
  return {
    userId,
    providerKey: row.providerKey,
    itemUrl: row.itemUrl,
    seasonUrl: row.seasonUrl ?? "",
    episodeLabel: row.episodeLabel ?? "",
  };
}

function favoriteFields(row) {
  return {
    mediaType: row.mediaType ?? "unknown",
    title: row.title ?? "",
    posterUrl: row.posterUrl ?? null,
    detailUrl: row.detailUrl ?? null,
    seasonLabel: row.seasonLabel ?? null,
    createdAt: date(row.createdAt) ?? new Date(),
  };
}

function progressFields(row) {
  return {
    mediaType: row.mediaType ?? "unknown",
    title: row.title ?? "",
    posterUrl: row.posterUrl ?? null,
    detailUrl: row.detailUrl ?? null,
    seasonLabel: row.seasonLabel ?? null,
    sourceLabel: row.sourceLabel ?? null,
    durationSeconds: int(row.durationSeconds),
    positionSeconds: int(row.positionSeconds),
    progressPercent: Number.isFinite(Number(row.progressPercent)) ? Number(row.progressPercent) : 0,
    isCompleted: Boolean(row.isCompleted),
    lastWatchedAt: date(row.lastWatchedAt) ?? new Date(),
  };
}

function date(value) {
  if (!value) return null;
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? null : parsed;
}

function int(value) {
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 ? Math.round(number) : 0;
}
