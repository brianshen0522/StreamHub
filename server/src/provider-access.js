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

  return providers.map((provider) => ({
    key: provider.key,
    name: provider.name,
    isEnabled: provider.isEnabled,
    allowed: provider.isEnabled && (permissionMap.get(provider.key) ?? true),
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
