function parsePositiveInt(value, fallback) {
  const parsed = Number.parseInt(value ?? "", 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

export const PORT = parsePositiveInt(process.env.PORT, 8787);
export const REQUEST_TIMEOUT_MS = parsePositiveInt(process.env.REQUEST_TIMEOUT_MS, 20_000);
export const STREAM_PROXY_TIMEOUT_MS = parsePositiveInt(process.env.STREAM_PROXY_TIMEOUT_MS, 30_000);
