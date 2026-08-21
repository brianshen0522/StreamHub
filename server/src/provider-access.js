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
  const latestChecks = await prisma.providerHealthCheck.findMany({
    where: { providerId: { in: providers.map((provider) => provider.id) } },
    orderBy: { checkedAt: "desc" },
    distinct: ["providerId"],
  });
  const healthByProvider = new Map(latestChecks.map((check) => [check.providerId, check]));

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
