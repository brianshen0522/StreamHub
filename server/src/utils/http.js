import { REQUEST_TIMEOUT_MS } from "../config.js";
import { safeFetch } from "./safe-fetch.js";

const USER_AGENT =
  "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

export const DEFAULT_TIMEOUT = REQUEST_TIMEOUT_MS;

/**
 * Every provider fetch goes through here, and several of the URLs come straight
 * from a query parameter — /api/item, /api/episodes and /api/sources all take
 * one. That makes this the same open door as the media proxies, so it uses the
 * same guard: each hop has to resolve to a public address. Provider sites are on
 * the public internet, so nothing legitimate is lost.
 */
export async function fetchText(url, options = {}) {
  const { response } = await safeFetch(url, {
    ...options,
    headers: {
      "user-agent": USER_AGENT,
      "accept-language": "zh-TW,zh;q=0.9,en;q=0.8",
      ...(options.headers ?? {}),
    },
    signal: AbortSignal.timeout(options.timeout ?? DEFAULT_TIMEOUT),
  });
  if (!response.ok) {
    throw new Error(`Request failed (${response.status}) for ${url}`);
  }
  return response.text();
}

export async function fetchJson(url, options = {}) {
  const text = await fetchText(url, options);
  return JSON.parse(text);
}

export function normalizeUrl(baseUrl, input) {
  return new URL(input, baseUrl).toString();
}

export function getBrowserLanguage(acceptLanguage) {
  if (!acceptLanguage) {
    return "zh-TW";
  }
  const primary = acceptLanguage.split(",")[0]?.trim().toLowerCase();
  return primary?.startsWith("zh") ? "zh-TW" : "en";
}

export function isPlaylist(url) {
  return url.toLowerCase().includes(".m3u8");
}
