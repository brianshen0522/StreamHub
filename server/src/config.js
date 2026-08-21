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
// Progress rows scanned when building the continue shelf. Rows group by title,
// so this bounds episodes across all shows, not the number of shows.
export const CONTINUE_SCAN_LIMIT = parsePositiveInt(process.env.CONTINUE_SCAN_LIMIT, 500);

export const PROVIDER_CHECK_INTERVAL_MS = parseDurationMs(process.env.PROVIDER_CHECK_INTERVAL_MS, 30_000);
export const PROVIDER_POLL_QUERY = String(process.env.PROVIDER_POLL_QUERY || "the");
export const ADMIN_PASSWORD = String(process.env.ADMIN_PASSWORD || "");

/**
 * Placeholders that have appeared in this repository's own examples, plus the
 * usual suspects. A secret from this list is public knowledge.
 */
const WEAK_SECRETS = new Set([
  "",
  "streamhub-dev-secret",
  "change-me",
  "changeme",
  "secret",
  "password",
]);

export function isWeakSecret(value) {
  return WEAK_SECRETS.has(String(value || "").trim()) || String(value || "").trim().length < 32;
}

/**
 * The signing key is the whole authentication system: anyone who knows it can
 * mint a token for any account, including the admin. It used to fall back to a
 * value committed to this repository, which is survivable on a laptop and not
 * survivable on a public domain — and nothing would have said so.
 */
function readJwtSecret() {
  const secret = String(process.env.JWT_SECRET || "").trim();
  if (!isWeakSecret(secret)) return secret;

  console.error(
    [
      "",
      "StreamHub will not start: JWT_SECRET is missing or too weak.",
      "",
      "It signs every access token, so a known value lets anyone issue a token",
      "for any account, including the administrator.",
      "",
      "Generate one and put it in .env:",
      "",
      "  JWT_SECRET=$(openssl rand -base64 48)",
      "",
      "Changing it signs everyone out, which is the intended effect.",
      "",
    ].join("\n"),
  );
  process.exit(1);
}

export const JWT_SECRET = readJwtSecret();
