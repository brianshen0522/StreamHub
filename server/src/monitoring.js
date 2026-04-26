import { ProviderHealthStatus } from "../generated/prisma/index.js";
import { PROVIDER_CHECK_INTERVAL_MS, PROVIDER_POLL_QUERY } from "./config.js";
import { prisma } from "./db.js";
import { providers as providerClients } from "./providers/index.js";

async function recordProviderHealth(providerRow, outcome) {
  const status = !providerRow.isEnabled
    ? ProviderHealthStatus.DISABLED
    : outcome.success
      ? outcome.responseTimeMs > 8_000
        ? ProviderHealthStatus.DEGRADED
        : ProviderHealthStatus.HEALTHY
      : ProviderHealthStatus.DOWN;

  await prisma.providerHealthCheck.create({
    data: {
      providerId: providerRow.id,
      status,
      success: outcome.success,
      responseTimeMs: outcome.responseTimeMs,
      errorMessage: outcome.errorMessage,
    },
  });

  await prisma.provider.update({
    where: { id: providerRow.id },
    data: { lastCheckedAt: new Date() },
  });
}

async function checkProvider(providerRow) {
  if (!providerRow.isEnabled) {
    await recordProviderHealth(providerRow, {
      success: false,
      responseTimeMs: null,
      errorMessage: "Provider disabled by admin.",
    });
    return;
  }

  const client = providerClients[providerRow.key];
  if (!client?.search) {
    await recordProviderHealth(providerRow, {
      success: false,
      responseTimeMs: null,
      errorMessage: "Provider client missing.",
    });
    return;
  }

  const start = Date.now();
  try {
    await client.search(PROVIDER_POLL_QUERY);
    await recordProviderHealth(providerRow, {
      success: true,
      responseTimeMs: Date.now() - start,
      errorMessage: null,
    });
  } catch (error) {
    await recordProviderHealth(providerRow, {
      success: false,
      responseTimeMs: Date.now() - start,
      errorMessage: error?.message || "Provider health check failed.",
    });
  }
}

export async function runProviderHealthCheck() {
  const providerRows = await prisma.provider.findMany({
    orderBy: [{ sortOrder: "asc" }, { key: "asc" }],
  });
  await Promise.all(providerRows.map((providerRow) => checkProvider(providerRow)));
}

export function startMonitoring() {
  runProviderHealthCheck().catch((error) => {
    console.error("Initial provider health check failed:", error);
  });

  return setInterval(() => {
    runProviderHealthCheck().catch((error) => {
      console.error("Scheduled provider health check failed:", error);
    });
  }, PROVIDER_CHECK_INTERVAL_MS);
}
