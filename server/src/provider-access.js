import { ProviderHealthStatus } from "../generated/prisma/index.js";
import { prisma } from "./db.js";

export async function getEnabledProvidersForUser(user) {
  const providers = await prisma.provider.findMany({
    orderBy: [{ sortOrder: "asc" }, { key: "asc" }],
  });

  if (user.role === "ADMIN") {
    return providers.map((provider) => ({
      key: provider.key,
      name: provider.name,
      isEnabled: provider.isEnabled,
      allowed: false,
      status: provider.isEnabled ? ProviderHealthStatus.HEALTHY : ProviderHealthStatus.DISABLED,
    }));
  }

  const permissions = await prisma.userProviderPermission.findMany({
    where: { userId: user.id },
    include: { provider: true },
  });
  const permissionMap = new Map(permissions.map((entry) => [entry.provider.key, entry.isEnabled]));

  // The health poller already knows whether each site is answering. Without it a
  // client can only report that a search returned nothing, which looks the same
  // whether the provider is down, disabled, or simply has no match.
  //
  // One indexed lookup per provider rather than `findMany({ distinct })`:
  // Prisma does `distinct` in memory, so that form read the *whole* table — a
  // row every 30 s per provider, 864,000 rows after ten weeks — on every search
  // and every library load, and searches sat 20 s on it before scraping began.
  // (provider_id, checked_at) is indexed, so each of these reads one row.
  const latestChecks = await Promise.all(providers.map((provider) =>
    prisma.providerHealthCheck.findFirst({
      where: { providerId: provider.id },
      orderBy: { checkedAt: "desc" },
      select: { providerId: true, status: true },
    })));
  const healthByProvider = new Map(latestChecks.filter(Boolean).map((check) => [check.providerId, check]));

  return providers.map((provider) => {
    const health = healthByProvider.get(provider.id);
    return {
      key: provider.key,
      name: provider.name,
      isEnabled: provider.isEnabled,
      allowed: provider.isEnabled && (permissionMap.get(provider.key) ?? true),
      // Status only. errorMessage is whatever the upstream fetch threw and can
      // carry hostnames, paths or stack detail; a viewer needs to know a
      // provider is down, not why, and the admin console already has the full
      // text. responseTimeMs is left out for the same reason — it describes the
      // server, not anything the viewer can act on.
      status: health?.status ?? null,
    };
  });
}

/**
 * Every provider, with how it stands for one account — which is not the same
 * as the permission rows that exist for them.
 *
 * Rows are written once, when the account is created, so an account never has
 * a row for a provider added after it. Listing the rows therefore showed the
 * catalogue as it was on the day the account was made, and a provider added
 * since was simply absent from the admin console — unswitchable, while being
 * searchable, because a missing row means allowed.
 *
 * Driven by the provider table instead, with the rows as an overlay.
 */
export async function listProviderAccess(userId) {
  const [providers, permissions] = await Promise.all([
    prisma.provider.findMany({ orderBy: [{ sortOrder: "asc" }, { key: "asc" }] }),
    prisma.userProviderPermission.findMany({ where: { userId } }),
  ]);
  const byProviderId = new Map(permissions.map((entry) => [entry.providerId, entry.isEnabled]));

  return providers.map((provider) => ({
    providerKey: provider.key,
    providerName: provider.name,
    // Absent means allowed, matching what the search path already enforces.
    isEnabled: byProviderId.get(provider.id) ?? true,
    globalEnabled: provider.isEnabled,
  }));
}

export async function assertProviderAccess(user, providerKey) {
  const provider = await prisma.provider.findUnique({ where: { key: providerKey } });
  if (!provider) {
    const error = new Error(`Unsupported provider: ${providerKey}`);
    error.statusCode = 400;
    throw error;
  }
  if (!provider.isEnabled) {
    const error = new Error("Provider disabled by admin.");
    error.statusCode = 403;
    throw error;
  }
  if (user.role !== "ADMIN") {
    const permission = await prisma.userProviderPermission.findFirst({
      where: {
        userId: user.id,
        providerId: provider.id,
      },
    });
    if (permission && !permission.isEnabled) {
      const error = new Error("Provider disabled for this user.");
      error.statusCode = 403;
      throw error;
    }
  }
  return provider;
}
