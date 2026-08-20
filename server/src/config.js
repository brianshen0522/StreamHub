function parsePositiveInt(value, fallback) {
  const parsed = Number.parseInt(value ?? "", 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

function parseDurationMs(value, fallback) {
  return parsePositiveInt(value, fallback);
}

export const PORT = parsePositiveInt(process.env.PORT, 8787);
export const REQUEST_TIMEOUT_MS = parseDurationMs(process.env.REQUEST_TIMEOUT_MS, 20_000);
export const SEARCH_TIMEOUT_MS = parseDurationMs(process.env.SEARCH_TIMEOUT_MS, 8_000);
export const STREAM_PROXY_TIMEOUT_MS = parseDurationMs(process.env.STREAM_PROXY_TIMEOUT_MS, 30_000);
// Long enough to cover a viewing session without the realtime socket having to
// reconnect part way through. Session length is still governed by the refresh
// token; this only bounds how long a leaked access token stays usable.
export const ACCESS_TOKEN_TTL_SECONDS = parsePositiveInt(process.env.ACCESS_TOKEN_TTL_SECONDS, 60 * 60 * 4);
export const REFRESH_TOKEN_TTL_DAYS = parsePositiveInt(process.env.REFRESH_TOKEN_TTL_DAYS, 30);
export const HEARTBEAT_ONLINE_WINDOW_SECONDS = parsePositiveInt(process.env.HEARTBEAT_ONLINE_WINDOW_SECONDS, 120);
export const PROVIDER_CHECK_INTERVAL_MS = parseDurationMs(process.env.PROVIDER_CHECK_INTERVAL_MS, 30_000);
export const PROVIDER_POLL_QUERY = String(process.env.PROVIDER_POLL_QUERY || "the");
export const ADMIN_PASSWORD = String(process.env.ADMIN_PASSWORD || "admin");
export const JWT_SECRET = process.env.JWT_SECRET || "streamhub-dev-secret";
