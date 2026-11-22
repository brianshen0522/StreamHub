/**
 * Subtitles, from OpenSubtitles' legacy REST API (rest.opensubtitles.org).
 *
 * The API needs no key, but every request — search and download alike — must
 * carry a registered application identifier in X-User-Agent or it 403s. That
 * value is the operator's own (OPENSUBTITLES_USER_AGENT); without it the
 * feature is off and nothing here calls out.
 *
 * A provider opts in with `getSubtitleContext(url, episodeLabel)`, which
 * answers `{ imdbId, season, episode, videoName }`. Only VidSrc does: it is the
 * only provider whose sessions name an IMDb id and the release being played.
 *
 * The API throttles bursts (429, sometimes 403), so every request goes through
 * one serial queue with a gap between starts, and everything is cached for a
 * day — a title's subtitle list does not change between viewings.
 *
 * Downloads are only ever of links this server got from a search: a client
 * asks for a file by its `IDSubtitleFile`, never by URL.
 */

import zlib from "node:zlib";
import { promisify } from "node:util";
import { LRUCache } from "lru-cache";

import { safeFetch } from "./utils/safe-fetch.js";

const gunzip = promisify(zlib.gunzip);

const API_BASE = "https://rest.opensubtitles.org";
const BROWSER_UA =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

const REQUEST_GAP_MS = 1_100;
const REQUEST_TIMEOUT_MS = 15_000;
const MAX_SEARCH_BYTES = 8 * 1024 * 1024;
const MAX_DOWNLOAD_BYTES = 4 * 1024 * 1024;
const MAX_SUBTITLE_BYTES = 16 * 1024 * 1024;
const DAY_MS = 1000 * 60 * 60 * 24;

/**
 * OPENSUBTITLES_USER_AGENT: the operator's registered application identifier.
 * Empty turns the feature off. OPENSUBTITLES_LANGUAGES: ISO 639-2 codes, in
 * the order tracks are listed. Read per call, so neither needs a restart to
 * be checked.
 */
function settings() {
  const userAgent = String(process.env.OPENSUBTITLES_USER_AGENT || "").trim();
  const languages = String(process.env.OPENSUBTITLES_LANGUAGES || "chi,eng")
    .split(",")
    .map((code) => code.trim().toLowerCase())
    .filter((code) => /^[a-z]{3}$/.test(code));
  return { userAgent, languages: [...new Set(languages.length ? languages : ["chi", "eng"])] };
}

// ─── caches ─────────────────────────────────────────────────────────────────

// (imdbId, season, episode, lang) → normalised candidates. Each candidate keeps
// its download link, so the file index below can always be rebuilt from here.
const searchCache = new LRUCache({ max: 1000, ttl: DAY_MS });
// IDSubtitleFile → { downloadLink, encoding, lang }: the only ids /file serves.
const fileIndex = new LRUCache({ max: 20_000, ttl: DAY_MS });
// IDSubtitleFile → converted WebVTT.
const vttCache = new LRUCache({
  max: 300,
  maxSize: 64 * 1024 * 1024,
  sizeCalculation: (vtt) => vtt.length || 1,
  ttl: DAY_MS,
});
// (provider, url, episode) → context. An IMDb id never changes, and this keeps
// a long viewing from re-running a provider chain after its session expired.
const contextCache = new LRUCache({ max: 2000, ttl: DAY_MS });

const searchInflight = new Map();
const fileInflight = new Map();

// ─── the request queue ──────────────────────────────────────────────────────

let queueTail = Promise.resolve();
let lastStartAt = 0;

/** Runs `task` after every earlier one has finished and the gap has passed. */
function enqueue(task) {
  const result = queueTail.then(async () => {
    const wait = lastStartAt + REQUEST_GAP_MS - Date.now();
    if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait));
    lastStartAt = Date.now();
    return task();
  });
  queueTail = result.catch(() => {});
  return result;
}

async function readBytes(body, maxBytes) {
  if (!body) return Buffer.alloc(0);
  const reader = body.getReader();
  const chunks = [];
  let total = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > maxBytes) {
        const error = new Error("OpenSubtitles response is too large.");
        error.statusCode = 502;
        throw error;
      }
      chunks.push(value);
    }
  } finally {
    reader.cancel().catch(() => {});
  }
  return Buffer.concat(chunks.map((chunk) => Buffer.from(chunk.buffer, chunk.byteOffset, chunk.byteLength)));
}

function osRequest(url, accept, maxBytes) {
  const { userAgent } = settings();
  return enqueue(async () => {
    const { response } = await safeFetch(url, {
      headers: {
        "x-user-agent": userAgent,
        "user-agent": BROWSER_UA,
        accept,
      },
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    if (!response.ok) {
      response.body?.cancel?.().catch(() => {});
      const error = new Error(
        response.status === 429 || response.status === 403
          ? `OpenSubtitles is throttling requests (${response.status}).`
          : `OpenSubtitles responded ${response.status}.`,
      );
      error.statusCode = 502;
      error.upstreamStatus = response.status;
      throw error;
    }
    return readBytes(response.body, maxBytes);
  });
}

// ─── search ─────────────────────────────────────────────────────────────────

function toInt(value) {
  const parsed = Number.parseInt(String(value ?? "").replace(/[^\d]/g, ""), 10);
  return Number.isFinite(parsed) ? parsed : 0;
}

export function isAllowedDownloadLink(rawUrl) {
  try {
    const url = new URL(rawUrl);
    if (url.protocol !== "https:") return false;
    const host = url.hostname.toLowerCase();
    return host === "opensubtitles.org" || host === "opensubtitles.com"
      || host.endsWith(".opensubtitles.org") || host.endsWith(".opensubtitles.com");
  } catch {
    return false;
  }
}

function normaliseCandidate(raw, wanted) {
  const id = String(raw?.IDSubtitleFile ?? "").trim();
  if (!/^\d+$/.test(id)) return null;
  // Legacy links are http://dl.opensubtitles.org/…; the same path answers
  // over https, and nothing is fetched in the clear.
  const downloadLink = String(raw.SubDownloadLink || "").replace(/^http:\/\//i, "https://");
  if (!isAllowedDownloadLink(downloadLink)) return null;
  const format = String(raw.SubFormat || "srt").toLowerCase();
  if (format !== "srt") return null;
  if (wanted.season != null) {
    const season = toInt(raw.SeriesSeason);
    const episode = toInt(raw.SeriesEpisode);
    if ((season && season !== wanted.season) || (episode && episode !== wanted.episode)) return null;
  }
  return {
    id,
    fileName: String(raw.SubFileName || ""),
    releaseName: String(raw.MovieReleaseName || ""),
    downloads: toInt(raw.SubDownloadsCnt),
    languageId: String(raw.SubLanguageID || "").toLowerCase(),
    encoding: String(raw.SubEncoding || ""),
    downloadLink,
  };
}

function indexCandidates(candidates, lang) {
  for (const candidate of candidates) {
    fileIndex.set(candidate.id, { downloadLink: candidate.downloadLink, encoding: candidate.encoding, lang });
  }
}

async function searchLanguage({ imdbId, season, episode }, lang) {
  const key = `${imdbId}:${season ?? "-"}:${episode ?? "-"}:${lang}`;
  const cached = searchCache.get(key);
  if (cached) {
    indexCandidates(cached, lang);
    return cached;
  }
  if (searchInflight.has(key)) return searchInflight.get(key);

  const promise = (async () => {
    const path = season != null
      ? `/search/episode-${episode}/imdbid-${imdbId}/season-${season}/sublanguageid-${lang}`
      : `/search/imdbid-${imdbId}/sublanguageid-${lang}`;
    const body = await osRequest(`${API_BASE}${path}`, "application/json", MAX_SEARCH_BYTES);
    let parsed;
    try {
      parsed = JSON.parse(body.toString("utf8"));
    } catch {
      const error = new Error("OpenSubtitles answered with something that is not JSON.");
      error.statusCode = 502;
      throw error;
    }
    const seen = new Set();
    const candidates = [];
    for (const raw of Array.isArray(parsed) ? parsed : []) {
      const candidate = normaliseCandidate(raw, { season, episode });
      if (!candidate || seen.has(candidate.id)) continue;
      seen.add(candidate.id);
      candidates.push(candidate);
    }
    // Empty answers are cached as well: "nobody has subtitled this" is as
    // stable as a list.
    searchCache.set(key, candidates);
    indexCandidates(candidates, lang);
    return candidates;
  })().finally(() => searchInflight.delete(key));
  searchInflight.set(key, promise);
  return promise;
}

// ─── scoring ────────────────────────────────────────────────────────────────

const TRADITIONAL_RE = /繁|(?:^|[^a-z0-9])(?:cht|big5|zh[-_ ]?tw|zh[-_ ]?hant|zht|tc)(?=[^a-z0-9]|$)/;

function candidateName(candidate) {
  return (candidate.fileName || candidate.releaseName).toLowerCase();
}

function looksTraditional(candidate) {
  if (candidate.languageId === "zht") return true;
  return TRADITIONAL_RE.test(candidate.fileName.toLowerCase()) || TRADITIONAL_RE.test(candidate.releaseName.toLowerCase());
}

function seasonEpisodeOf(text) {
  const match = /s(\d{1,2})[ ._-]?e(\d{1,3})/i.exec(text);
  return match ? { season: Number(match[1]), episode: Number(match[2]) } : null;
}

export function scoreCandidate(candidate, context, lang) {
  const name = candidateName(candidate);
  let score = 0;
  const videoName = String(context.videoName || "").toLowerCase();
  if (videoName) {
    const resolution = /(2160|1080|720|480)p/.exec(videoName)?.[0];
    if (resolution && name.includes(resolution)) score += 3;

    const wanted = seasonEpisodeOf(videoName)
      ?? (context.season != null ? { season: context.season, episode: context.episode } : null);
    const found = seasonEpisodeOf(name);
    if (wanted && found && wanted.season === found.season && wanted.episode === found.episode) score += 4;

    const year = /(?<!\d)(19\d{2}|20\d{2})(?!\d)/.exec(videoName)?.[1];
    if (year && name.includes(year)) score += 2;
  }
  if (candidate.downloads > 10_000) score += 3;
  else if (candidate.downloads > 1_000) score += 2;
  else if (candidate.downloads > 100) score += 1;
  if (lang === "chi" && looksTraditional(candidate)) score += 2;
  return score;
}

function pickBest(candidates, context, lang) {
  let best = null;
  for (const candidate of candidates) {
    const score = scoreCandidate(candidate, context, lang);
    if (!best || score > best.score || (score === best.score && candidate.downloads > best.candidate.downloads)) {
      best = { candidate, score };
    }
  }
  return best;
}

const LANGUAGES = {
  eng: { srclang: "en", label: "English" },
  chi: { srclang: "zh", label: "中文" },
  jpn: { srclang: "ja", label: "日本語" },
  kor: { srclang: "ko", label: "한국어" },
  spa: { srclang: "es", label: "Español" },
  fre: { srclang: "fr", label: "Français" },
  ger: { srclang: "de", label: "Deutsch" },
  ita: { srclang: "it", label: "Italiano" },
  por: { srclang: "pt", label: "Português" },
  pob: { srclang: "pt-BR", label: "Português (Brasil)" },
  rus: { srclang: "ru", label: "Русский" },
  dut: { srclang: "nl", label: "Nederlands" },
  tha: { srclang: "th", label: "ไทย" },
  vie: { srclang: "vi", label: "Tiếng Việt" },
  ind: { srclang: "id", label: "Bahasa Indonesia" },
  may: { srclang: "ms", label: "Bahasa Melayu" },
  ara: { srclang: "ar", label: "العربية" },
};

function describeLanguage(lang, candidate) {
  if (lang === "chi" && looksTraditional(candidate)) return { srclang: "zh-Hant", label: "中文（繁體）" };
  return LANGUAGES[lang] ?? { srclang: lang, label: lang };
}

// ─── listing ────────────────────────────────────────────────────────────────

async function resolveContext(providerName, provider, url, episodeLabel) {
  const key = `${providerName}:${url}:${episodeLabel}`;
  const cached = contextCache.get(key);
  if (cached) return cached;
  const context = await provider.getSubtitleContext(url, episodeLabel);
  if (context?.imdbId) contextCache.set(key, context);
  return context;
}

/**
 * `{ enabled, tracks, error? }` for one title or episode. Never throws for an
 * upstream failure: subtitles are an extra, and the Watch page must not fail
 * because they did.
 */
export async function listSubtitles({ providerName, provider, url, episodeLabel }) {
  const { userAgent, languages } = settings();
  if (!userAgent) return { enabled: false, tracks: [] };
  if (typeof provider?.getSubtitleContext !== "function") return { enabled: true, tracks: [] };

  let context;
  try {
    context = await resolveContext(providerName, provider, url, episodeLabel);
  } catch (error) {
    return { enabled: true, tracks: [], error: error?.message || "Could not identify this title." };
  }
  const imdbId = String(context?.imdbId ?? "").replace(/\D/g, "");
  if (!imdbId) return { enabled: true, tracks: [] };
  const isEpisode = context.season != null && context.episode != null;
  const query = {
    imdbId,
    season: isEpisode ? Number(context.season) : null,
    episode: isEpisode ? Number(context.episode) : null,
  };

  const tracks = [];
  let firstError = null;
  // One language after another: the queue serialises the requests anyway,
  // and this keeps the order the operator configured.
  for (const lang of languages) {
    try {
      const candidates = await searchLanguage(query, lang);
      const best = pickBest(candidates, { ...context, ...query }, lang);
      if (!best) continue;
      const { candidate, score } = best;
      tracks.push({
        id: candidate.id,
        lang,
        ...describeLanguage(lang, candidate),
        releaseName: candidate.fileName || candidate.releaseName,
        score,
        url: `/api/subtitles/file?id=${candidate.id}`,
      });
    } catch (error) {
      firstError ??= error;
    }
  }

  const result = { enabled: true, tracks };
  if (firstError) result.error = firstError.message || "OpenSubtitles failed.";
  return result;
}

// ─── download and conversion ────────────────────────────────────────────────

function replacementShare(text) {
  if (!text.length) return 0;
  let bad = 0;
  for (const char of text) if (char === "�") bad += 1;
  return bad / text.length;
}

function tryDecode(bytes, label) {
  try {
    return new TextDecoder(label, { fatal: false }).decode(bytes);
  } catch {
    return null; // not a label this runtime knows
  }
}

/** UTF-8 first; the declared encoding, then the usual legacy ones, if that mangles it. */
export function decodeSubtitle(bytes, { encoding, lang }) {
  let best = tryDecode(bytes, "utf-8");
  let bestShare = replacementShare(best);
  if (bestShare > 0.01) {
    const fallbacks = [];
    if (encoding) fallbacks.push(encoding);
    if (lang === "chi" || lang === "zht") fallbacks.push("big5", "gbk");
    else fallbacks.push("windows-1252");
    for (const label of fallbacks) {
      const text = tryDecode(bytes, label);
      if (text == null) continue;
      const share = replacementShare(text);
      if (share < bestShare) {
        best = text;
        bestShare = share;
      }
      if (share === 0) break;
    }
  }
  return best.replace(/^﻿/, "");
}

export function srtToVtt(srt) {
  const body = srt
    .replace(/^﻿/, "")
    .replace(/\r\n?/g, "\n")
    .split("\n")
    .map((line) => (line.includes("-->")
      ? line.replace(/(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})/g, (_m, h, m, s, ms) => `${h.padStart(2, "0")}:${m}:${s}.${ms.padEnd(3, "0")}`)
      : line))
    .join("\n")
    .trim();
  return `WEBVTT\n\n${dropAdvertCues(body)}\n`;
}

// OpenSubtitles splices its own adverts into downloaded files as ordinary
// cues ("Advertise your product or brand here", "become VIP member…",
// "tryray.app"). They are not the film's, so they are dropped. Matched on
// the cue text only, never on timing, so a real line is never at risk unless
// it names these services.
const ADVERT_CUE = /opensubtitles|osdb\.link|(?:try|get)ray|ray\.app|vip member|advertise your product|support us and become/i;

function dropAdvertCues(body) {
  return body
    .split(/\n{2,}/)
    .filter((cue) => {
      const text = cue.split("\n").filter((line) => !line.includes("-->") && !/^\d+$/.test(line.trim())).join(" ");
      return !ADVERT_CUE.test(text);
    })
    .join("\n\n");
}

function lookupFile(id) {
  const indexed = fileIndex.get(id);
  if (indexed) return indexed;
  // The index is the shorter-lived of the two; a search entry still holding
  // the id is just as good.
  for (const [key, candidates] of searchCache.entries()) {
    const hit = candidates.find((candidate) => candidate.id === id);
    if (hit) {
      const entry = { downloadLink: hit.downloadLink, encoding: hit.encoding, lang: key.split(":").pop() };
      fileIndex.set(id, entry);
      return entry;
    }
  }
  return null;
}

/** The WebVTT for a subtitle file a search on this server returned. */
export async function getSubtitleVtt(rawId) {
  const id = String(rawId ?? "").trim();
  const entry = /^\d+$/.test(id) ? lookupFile(id) : null;
  if (!entry) {
    const error = new Error("Unknown subtitle id.");
    error.statusCode = 404;
    throw error;
  }
  const cached = vttCache.get(id);
  if (cached) return cached;
  if (fileInflight.has(id)) return fileInflight.get(id);

  const promise = (async () => {
    if (!settings().userAgent) {
      const error = new Error("Subtitles are not enabled on this server.");
      error.statusCode = 404;
      throw error;
    }
    if (!isAllowedDownloadLink(entry.downloadLink)) {
      const error = new Error("Subtitle link is not on OpenSubtitles.");
      error.statusCode = 400;
      throw error;
    }
    const raw = await osRequest(entry.downloadLink, "*/*", MAX_DOWNLOAD_BYTES);
    let bytes = raw;
    if (raw[0] === 0x1f && raw[1] === 0x8b) {
      try {
        bytes = await gunzip(raw, { maxOutputLength: MAX_SUBTITLE_BYTES });
      } catch {
        const error = new Error("OpenSubtitles sent a damaged subtitle file.");
        error.statusCode = 502;
        throw error;
      }
    }
    const vtt = srtToVtt(decodeSubtitle(bytes, entry));
    vttCache.set(id, vtt);
    return vtt;
  })().finally(() => fileInflight.delete(id));
  fileInflight.set(id, promise);
  return promise;
}
