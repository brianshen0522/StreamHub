/**
 * VidSrc provider.
 *
 * Unlike the other providers this one has no site of its own to scrape for
 * content: the catalogue is TMDB itself. A search goes to themoviedb.org,
 * the top results are each probed against the VidSrc embed chain, and only
 * the titles VidSrc actually carries are shown.
 *
 * The playback chain (reverse-engineered, see PoC/flixcity notes):
 *
 *   /vs_src.php?type=…&id=…            → { src }  (one-shot embed URL on the EDN pool)
 *   GET <src>                          → page holding "playerUrl"
 *   GET <playerUrl>                    → window.CONFIG { apiToken, api|streamBase, season, episode }
 *   GET api.php?…&api_token=<token>    → { data.stream_urls (WASM-encrypted), vs.wasm_url, … }
 *   GET vs.wasm_url                    → module: alloc/decrypt → newline-separated master playlists
 *   GET <host>/generate.php?api_token  → playback JWT, bound to the caller's /24, ~4 h
 *
 * Every master/variant/segment request afterwards needs BOTH that JWT as
 * ?token=… AND a Referer of the player page — which is why the streams this
 * provider emits carry `preferProxy` (the browser cannot send either one) and
 * the stream proxy forwards `stream.referer` upstream.
 */

import { execFile } from "node:child_process";
import { promisify } from "node:util";

import { caches } from "../cache.js";
import { registerUpstreamReferer } from "../upstream-referers.js";
import { SEARCH_TIMEOUT_MS } from "../config.js";

const run = promisify(execFile);

const UA =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

const TMDB_BASE = "https://www.themoviedb.org";
const EMBED_BASE = "https://embed.vidsrc.si";

// How many TMDB results a search probes against VidSrc. Each probe is a
// four-request chain, and the chain's own gate answers 429 once an address
// asks too often — six is enough to cover any real query while staying
// comfortably under it.
const PROBE_TOP_N = 6;
const PROBE_CONCURRENCY = 3;

/** The player pages VidSrc hands out live under these hosts; the EDN pool
 * rotates, but this one has been stable across every observation. Learned
 * hosts are added at resolve time so a future move heals itself. */
const PLAYER_REFERER_HOSTS = new Set(["stellarconductornexus.com"]);

export function isVidsrcRefererOrigin(rawOrigin) {
  try {
    const url = new URL(rawOrigin);
    return url.protocol === "https:" && PLAYER_REFERER_HOSTS.has(url.hostname);
  } catch {
    return false;
  }
}

function rememberPlayerOrigin(playerUrl) {
  try {
    PLAYER_REFERER_HOSTS.add(new URL(playerUrl).hostname);
  } catch { /* a malformed player URL simply is not remembered */ }
}

// ─── HTTP ────────────────────────────────────────────────────────────────────

async function vidFetch(url, { referer, accept, timeoutMs = 15_000 } = {}) {
  const headers = { "user-agent": UA, "accept-language": "en-US,en;q=0.9" };
  headers.accept = accept || "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";
  if (referer) headers.referer = referer;
  const response = await fetch(url, {
    headers,
    signal: AbortSignal.timeout(timeoutMs),
    redirect: "follow",
  });
  if (!response.ok) {
    const error = new Error(`VidSrc upstream responded ${response.status} for ${url}`);
    error.statusCode = response.status;
    throw error;
  }
  return response;
}

async function vidText(url, opts) {
  const response = await vidFetch(url, opts);
  return response.text();
}

async function vidJson(url, opts) {
  const response = await vidFetch(url, { ...opts, accept: "application/json" });
  return response.json();
}

// ─── TMDB scraping (no API key: the public site is the source of truth) ─────

/** themoviedb.org fingerprints its visitors and 403s Node's own TLS stack
 * (undici) while passing the system curl — verified from this host. So every
 * TMDB page goes through curl with browser headers; provider sites elsewhere
 * still use fetchText. */
async function tmdbFetchText(path) {
  const { stdout } = await run(
    "curl",
    [
      // --fail: a 403 challenge or 404 page must surface as an error, not
      // be parsed as an empty result and cached (pages are kept for a day).
      "-sSL", "--fail", "--compressed",
      "-A", UA,
      "-H", "accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
      "-H", "accept-language: en-US,en;q=0.9",
      "--max-time", String(Math.ceil(SEARCH_TIMEOUT_MS / 1000)),
      `${TMDB_BASE}${path}`,
    ],
    { maxBuffer: 32 * 1024 * 1024 },
  );
  return stdout;
}

function tmdbPoster(src) {
  if (!src) return "";
  return src.startsWith("http") ? src : `https://media.themoviedb.org${src}`;
}

async function tmdbSearchRaw(keyword) {
  const cacheKey = `vidsrc:tmdb-search:${keyword.toLowerCase()}`;
  const cached = caches.search.get(cacheKey);
  if (cached) return cached;

  const html = await tmdbFetchText(`/search?query=${encodeURIComponent(keyword)}`);

  // Result cards: <a data-media-type="tv" href="/tv/1396-breaking-bad">…<img alt="Breaking Bad" src="…">
  // Both orders of the attributes occur, so each is read on its own around
  // the anchor rather than trusting one regex over the whole tag.
  const seen = new Set();
  const results = [];
  const anchorRe = /<a\b[^>]*href="(\/(?:movie|tv)\/\d+[^"]*)"[^>]*>/g;
  for (const match of html.matchAll(anchorRe)) {
    const href = match[1];
    const typeMatch = href.match(/^\/(movie|tv)\/(\d+)/);
    if (!typeMatch) continue;
    const urlPath = href.split("?")[0].replace(/\/$/, "");
    if (seen.has(urlPath)) continue;
    seen.add(urlPath);

    // The card is the anchor's enclosing .comp:media-card block; work on a
    // slice so an anchor without the card attributes still parses.
    const window = html.slice(Math.max(0, match.index - 200), match.index + 1600);
    const type = typeMatch[1] === "movie" ? "movie" : "tv";
    const id = typeMatch[2];
    let slugTitle = (urlPath.match(/\/\d+-([^/]+)$/)?.[1] ?? "").replace(/-/g, " ");
    try {
      slugTitle = decodeURIComponent(slugTitle);
    } catch { /* a malformed escape keeps the raw slug rather than failing the search */ }
    const altTitle = window.match(/<img[^>]*alt="([^"]+)"/)?.[1];
    const title = (altTitle || slugTitle || "").trim();
    if (!title || /^(?:movie|tv)$/.test(title)) continue;

    const poster = tmdbPoster(window.match(/src="(https:\/\/media\.themoviedb\.org\/[^"]+)"/)?.[1]);
    const year = window.match(/\b(19\d{2}|20\d{2})\b/)?.[1] ?? "";
    results.push({ type, id, title, year, poster, urlPath });
  }

  caches.search.set(cacheKey, results);
  return results;
}

async function tmdbPage(path) {
  const cacheKey = `vidsrc:tmdb-page:${path}`;
  const cached = caches.mediaType.get(cacheKey);
  if (cached) return cached;
  const html = await tmdbFetchText(path);
  // Shows' season/episode structures are stable for days; one day is a
  // conservative read on how long to trust a page.
  caches.mediaType.set(cacheKey, html, { ttl: 1000 * 60 * 60 * 24 });
  return html;
}

async function tmdbSeasons(id, slug) {
  const html = await tmdbPage(`/tv/${id}${slug}/seasons`);
  const seen = new Set();
  const seasons = [];
  for (const match of html.matchAll(new RegExp(`href="(/tv/${id}[^"]*\\/season\\/(\\d+))"`, "g"))) {
    const number = Number(match[2]);
    if (!Number.isFinite(number) || number < 1 || seen.has(number)) continue;
    seen.add(number);
    seasons.push(number);
  }
  seasons.sort((a, b) => a - b);
  return seasons;
}

async function tmdbEpisodeCount(id, slug, season) {
  const html = await tmdbPage(`/tv/${id}${slug}/season/${season}`);
  const seen = new Set();
  for (const match of html.matchAll(new RegExp(`href="(/tv/${id}[^"]*\\/season\\/${season}\\/episode\\/(\\d+))"`, "g"))) {
    seen.add(Number(match[2]));
  }
  return seen.size;
}

// ─── the VidSrc chain ───────────────────────────────────────────────────────

function decodeBase64ToBytes(value) {
  return Uint8Array.from(Buffer.from(value, "base64"));
}

/** The WASM module exposes alloc(len) → ptr and decrypt(ptr, len) → outLen,
 * with the plaintext sitting at ptr+12. Verified against the live pool. */
function decryptStreamUrls(wasmBytes, encryptedBase64) {
  const instance = new WebAssembly.Instance(new WebAssembly.Module(wasmBytes), {});
  const exports = instance.exports;
  const encrypted = decodeBase64ToBytes(encryptedBase64);
  const pointer = exports.alloc(encrypted.length);
  new Uint8Array(exports.memory.buffer, pointer, encrypted.length).set(encrypted);
  const outputLength = exports.decrypt(pointer, encrypted.length);
  return new TextDecoder().decode(new Uint8Array(exports.memory.buffer, pointer + 12, outputLength));
}

function splitPlaylistUrls(decrypted) {
  return decrypted.split("\n").map((line) => line.trim()).filter(Boolean);
}

export class VidsrcGateError extends Error {
  constructor(reason) {
    super(`VidSrc gate is up (${reason ?? "unknown"}). Retry later.`);
    this.name = "VidsrcGateError";
    this.statusCode = 429;
    this.gateReason = reason;
  }
}

/**
 * Walks the chain and returns the resolved session. `withPlaybackToken` also
 * trades the api token at generate.php — that JWT is what every playlist and
 * segment request must carry as ?token=.
 */
async function resolveVidsrc(type, tmdbId, { season, episode, withPlaybackToken = false } = {}) {
  const vsApi = type === "tv"
    ? `${EMBED_BASE}/vs_src.php?type=tv&id=${encodeURIComponent(tmdbId)}&season=${encodeURIComponent(season)}&episode=${encodeURIComponent(episode)}&autoplay=1`
    : `${EMBED_BASE}/vs_src.php?type=movie&id=${encodeURIComponent(tmdbId)}&autoplay=1`;

  const vsBody = await vidJson(vsApi, { referer: `${EMBED_BASE}/` });
  const src = vsBody?.src;
  if (!src) throw new Error("VidSrc: embed returned no src.");

  let embedHost;
  try {
    embedHost = new URL(src).origin;
  } catch {
    throw new Error("VidSrc: embed src is not a URL.");
  }
  const embedPage = await vidText(src, { referer: `${EMBED_BASE}/` });
  const playerPath = embedPage.match(/"playerUrl":"([^"]+)"/)?.[1]?.replace(/\\u0026/g, "&");
  if (!playerPath) throw new Error("VidSrc: embed page carries no playerUrl.");
  const playerUrl = `${embedHost}${playerPath}`;
  rememberPlayerOrigin(playerUrl);

  const playerPage = await vidText(playerUrl, { referer: src });
  const config = playerPage.match(/window\.CONFIG\s*=\s*(\{.*?\});/s)?.[1];
  if (!config) throw new Error("VidSrc: player page carries no CONFIG.");
  const apiToken = config.match(/"apiToken":"([^"]*)"/)?.[1] ?? "";
  if (!apiToken) {
    const reason = config.match(/"gateReason":"([^"]+)"/)?.[1];
    throw new VidsrcGateError(reason);
  }

  // Movies carry a ready-made api URL; shows a streamBase the season and
  // episode hang off.
  let apiUrl;
  const apiMatch = config.match(/"api":"([^"]+)"/);
  if (apiMatch) {
    try {
      apiUrl = JSON.parse(`"${apiMatch[1]}"`);
    } catch {
      throw new Error("VidSrc: CONFIG.api is malformed.");
    }
  } else {
    const baseMatch = config.match(/"streamBase":"([^"]+)"/);
    if (!baseMatch) throw new Error("VidSrc: CONFIG has neither api nor streamBase.");
    let base;
    try {
      base = JSON.parse(`"${baseMatch[1]}"`);
    } catch {
      throw new Error("VidSrc: CONFIG.streamBase is malformed.");
    }
    const cfgSeason = config.match(/"season":(\d+)/)?.[1] ?? season;
    const cfgEpisode = config.match(/"episode":(\d+)/)?.[1] ?? episode;
    apiUrl = `${base}&season=${cfgSeason}&episode=${cfgEpisode}&stream_urls`;
  }

  const streamBody = await vidJson(`${apiUrl}&api_token=${encodeURIComponent(apiToken)}`, {
    referer: playerUrl,
    accept: "application/json",
  });
  const data = streamBody?.data;
  const encrypted = data?.stream_urls;
  if (!data || !encrypted) {
    // api.php answers 200 with no streams for a title it does not carry.
    return { available: false, playerUrl, host: data?.stream_urls_api_host ?? null, fileName: data?.file_name ?? "", title: data?.title ?? "", imdbId: data?.imdb_id ?? "" };
  }

  const wasmBytes = new Uint8Array(await (await vidFetch(streamBody.vs.wasm_url, { referer: playerUrl })).arrayBuffer());
  const playlists = splitPlaylistUrls(decryptStreamUrls(wasmBytes, encrypted));

  let playbackToken = null;
  if (withPlaybackToken) {
    playbackToken = (await vidText(
      `https://${data.stream_urls_api_host}/generate.php?api_token=${encodeURIComponent(apiToken)}`,
      { referer: playerUrl, accept: "*/*" },
    )).trim();
    if (playbackToken.length < 40 || playbackToken.length > 600) {
      throw new Error("VidSrc: generate.php returned no usable playback token.");
    }
  }

  return {
    available: playlists.length > 0,
    host: data.stream_urls_api_host,
    referer: playerUrl,
    fileName: data.file_name ?? "",
    title: data.title ?? "",
    imdbId: data.imdb_id ?? "",
    playlists,
    playbackToken,
  };
}

// One in-flight chain per key: the sources probe and the watch page's own
// resolve must not race each other into the rate limiter.
const inflight = new Map();

async function resolveCached(type, tmdbId, { season = null, episode = null, withPlaybackToken = false } = {}) {
  const key = `vidsrc:session:${type}:${tmdbId}:${season ?? "-"}:${episode ?? "-"}:${withPlaybackToken ? "t" : "p"}`;
  const cached = caches.streamMetadata.get(key);
  if (cached) return cached;
  if (inflight.has(key)) return inflight.get(key);
  const promise = (async () => {
    try {
      const session = await resolveVidsrc(type, tmdbId, { season, episode, withPlaybackToken });
      // 90 min: the playback JWT lives ~4 h, and a fresh chain beats a stale
      // token. Unavailable answers are cached too — they are the search's
      // negative half.
      caches.streamMetadata.set(key, session, { ttl: 1000 * 60 * 90 });
      return session;
    } finally {
      inflight.delete(key);
    }
  })();
  inflight.set(key, promise);
  return promise;
}

// ─── availability probing ───────────────────────────────────────────────────

const PROBE_TTL_POSITIVE_MS = 1000 * 60 * 60 * 6;   // in the catalogue: half a day
const PROBE_TTL_NEGATIVE_MS = 1000 * 60 * 30;       // not carried: re-checked soon
const PROBE_TTL_GATED_MS = 1000 * 60 * 5;           // gate answered: ask again soon

const probeCache = new Map(); // key → { verdict, at } — verdict: true | false | "gated"
const probeInflight = new Map();
const PROBE_CACHE_MAX = 2000;

async function probeAvailability(type, tmdbId, { season = null, episode = null } = {}) {
  const key = `probe:${type}:${tmdbId}:${season ?? "-"}:${episode ?? "-"}`;
  const cached = probeCache.get(key);
  if (cached) {
    const ttl = cached.verdict === true ? PROBE_TTL_POSITIVE_MS
      : cached.verdict === "gated" ? PROBE_TTL_GATED_MS
        : PROBE_TTL_NEGATIVE_MS;
    if (Date.now() - cached.at < ttl) return cached.verdict;
  }
  if (probeInflight.has(key)) return probeInflight.get(key);

  const promise = (async () => {
    let verdict = false;
    try {
      const session = await resolveCached(type, tmdbId, { season, episode });
      verdict = session.available === true;
    } catch (error) {
      verdict = error instanceof VidsrcGateError ? "gated" : false;
    }
    probeCache.set(key, { verdict, at: Date.now() });
    // Bounded, not timed: entries carry their own freshness, so an old one
    // just costs memory until the map is swept.
    if (probeCache.size > PROBE_CACHE_MAX) probeCache.clear();
    return verdict;
  })().finally(() => probeInflight.delete(key));
  probeInflight.set(key, promise);
  return promise;
}

// ─── streams ────────────────────────────────────────────────────────────────

function toStreams(session, label) {
  if (!session?.available || !session.playbackToken) return [];
  // A client handed the bare CDN URL (a native player's /api/manifest, a
  // cast receiver) cannot send the Referer; the media endpoints look it up.
  for (const playlist of session.playlists) registerUpstreamReferer(playlist, session.referer);
  return session.playlists.map((playlist, index) => ({
    url: `${playlist}?token=${encodeURIComponent(session.playbackToken)}`,
    // Two playlist URLs are two encodes of the same file on the same pool —
    // mirrors, not qualities. The master lists the ladder; the player takes
    // its top rung.
    sourceLabel: session.playlists.length > 1 ? `${label} #${index + 1}` : label,
    referer: session.referer,
    preferProxy: true,
    maxQuality: true,
  }));
}

// ─── provider contract ──────────────────────────────────────────────────────

function itemUrl(type, tmdbId) {
  return type === "tv" ? `${EMBED_BASE}/embed/tv/${tmdbId}` : `${EMBED_BASE}/embed/movie/${tmdbId}`;
}

function parseItemUrl(url) {
  const match = String(url).match(/embed\/(movie|tv)\/(\d+)/);
  if (!match) return null;
  const seasonParam = String(url).match(/[?&]season=(\d+)/)?.[1];
  return { type: match[1], tmdbId: match[2], season: seasonParam ? Number(seasonParam) : null };
}

export async function searchVidsrc(keyword) {
  const query = String(keyword || "").trim();
  if (!query) return [];

  const probeKey = `vidsrc:search:${query.toLowerCase()}`;
  const cached = caches.detail.get(probeKey);
  if (cached) return cached;

  // TMDB decides the ranking; VidSrc only decides visibility.
  const candidates = (await tmdbSearchRaw(query)).slice(0, PROBE_TOP_N);

  // Gated probes stop the batch: every further chain would 429 too, and
  // treating "gated" as "absent" would hide real titles for no reason.
  let gated = false;
  const verdicts = new Map();
  const probeCandidate = async (candidate) => {
    const season = candidate.type === "tv" ? 1 : null;
    const verdict = await probeAvailability(candidate.type, candidate.id, { season, episode: season ? 1 : null });
    // Keyed by type too: TMDB numbers movies and shows separately, so
    // movie 1396 and tv 1396 are two different titles.
    verdicts.set(`${candidate.type}:${candidate.id}`, verdict);
    if (verdict === "gated") gated = true;
  };
  for (let start = 0; start < candidates.length; start += PROBE_CONCURRENCY) {
    if (gated) break;
    const jobs = [];
    for (const candidate of candidates.slice(start, start + PROBE_CONCURRENCY)) {
      jobs.push(probeCandidate(candidate));
    }
    await Promise.all(jobs);
  }

  const items = candidates
    .filter((candidate) => verdicts.get(`${candidate.type}:${candidate.id}`) === true)
    .map((candidate) => ({
      provider: "vidsrc",
      title: candidate.title,
      url: itemUrl(candidate.type, candidate.id),
      posterUrl: candidate.poster,
      mediaType: candidate.type,
      year: candidate.year,
      rawType: "VidSrc",
    }));

  // Only hits are cached: an empty answer is usually the upstream gate, and
  // caching it would keep hiding titles for the cache's whole TTL after the
  // gate lifts. The per-title probe cache already carries the short-memory
  // half of that.
  if (items.length) caches.detail.set(probeKey, items);
  return items;
}

export async function getVidsrcItem(item) {
  const parsed = parseItemUrl(item?.url);
  if (!parsed) throw new Error("VidSrc item URL not recognised.");

  if (parsed.type === "movie") {
    const session = await resolveCached("movie", parsed.tmdbId, { withPlaybackToken: true });
    if (!session.available) {
      const error = new Error("This title is not on VidSrc.");
      error.statusCode = 404;
      throw error;
    }
    return {
      provider: "vidsrc",
      mediaType: "movie",
      title: item.title || session.title || `TMDB ${parsed.tmdbId}`,
      posterUrl: item.posterUrl || "",
      streams: toStreams(session, "VidSrc"),
    };
  }

  // Shows: the seasons come from TMDB; availability is decided per episode
  // when it is opened, not per show — the catalogue is per-file.
  const slugMatch = item.url.match(/tv\/\d+([^/?]*)/);
  const slug = slugMatch?.[1] ?? "";
  const seasonNumbers = await tmdbSeasons(parsed.tmdbId, slug);
  if (!seasonNumbers.length) {
    const error = new Error("VidSrc show has no listed seasons.");
    error.statusCode = 404;
    throw error;
  }
  return {
    provider: "vidsrc",
    mediaType: "tv",
    title: item.title,
    posterUrl: item.posterUrl || "",
    seasons: seasonNumbers.map((number) => ({
      label: `Season ${number}`,
      url: `${EMBED_BASE}/embed/tv/${parsed.tmdbId}?season=${number}`,
    })),
  };
}

export async function getVidsrcSeasonEpisodes(seasonUrl) {
  const season = Number(seasonUrl.match(/[?&]season=(\d+)/)?.[1]);
  if (!Number.isFinite(season)) throw new Error("VidSrc season URL carries no season.");
  const parsed = parseItemUrl(seasonUrl);
  // The item URL lost the slug on the way into view state, so seasons are
  // fetched by bare id — themoviedb.org answers for /tv/<id> without one.
  const count = await tmdbEpisodeCount(parsed.tmdbId, "", season);
  if (!count) {
    const error = new Error("No episodes listed for this season.");
    error.statusCode = 404;
    throw error;
  }
  return Array.from({ length: count }, (_, index) => `Episode ${index + 1}`);
}

export async function getVidsrcEpisodeStreams(seasonUrl, episodeLabel) {
  const season = Number(seasonUrl.match(/[?&]season=(\d+)/)?.[1]);
  const episode = Number(String(episodeLabel).match(/(\d+)\s*$/)?.[1]);
  const parsed = parseItemUrl(seasonUrl);
  if (!Number.isFinite(season) || !Number.isFinite(episode) || !parsed) {
    throw new Error("VidSrc episode streams need a season and episode number.");
  }
  const session = await resolveCached("tv", parsed.tmdbId, {
    season,
    episode,
    withPlaybackToken: true,
  });
  if (!session.available) {
    const error = new Error("This episode is not on VidSrc.");
    error.statusCode = 404;
    throw error;
  }
  return toStreams(session, "VidSrc");
}

/**
 * What /api/subtitles needs to search OpenSubtitles: the IMDb id, the episode,
 * and the release name being played (scored against subtitle file names, so
 * the cut and frame rate line up).
 *
 * Reads the same session the Watch page's own resolve filled — same key, with
 * the playback token — so opening subtitles normally runs no chain of its own.
 */
export async function getVidsrcSubtitleContext(url, episodeLabel) {
  const parsed = parseItemUrl(url);
  if (!parsed) throw new Error("VidSrc item URL not recognised.");
  let season = null;
  let episode = null;
  if (parsed.type === "tv") {
    season = parsed.season;
    episode = Number(String(episodeLabel ?? "").match(/(\d+)\s*$/)?.[1]);
    if (!Number.isFinite(season) || !Number.isFinite(episode)) {
      const error = new Error("VidSrc episode subtitles need a season and episode number.");
      error.statusCode = 400;
      throw error;
    }
  }
  const session = await resolveCached(parsed.type, parsed.tmdbId, { season, episode, withPlaybackToken: true });
  return {
    imdbId: String(session.imdbId || "").replace(/\D/g, ""),
    season,
    episode,
    videoName: session.fileName || "",
  };
}

/** The health poller's cheap pulse: one request, no chains. */
export async function pingVidsrc() {
  const response = await vidFetch(`${EMBED_BASE}/`, { timeoutMs: 8_000 });
  await response.body?.cancel?.().catch?.(() => {});
  return response.ok;
}
