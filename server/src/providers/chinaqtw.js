/**
 * ChinaQ TW (chinaq.net.tw).
 *
 * Shares a name with chinaq.fun and nothing else: this one runs MacCMS, and
 * the two catalogues overlap far less than the names suggest. Each carries
 * titles the other does not — chinaq.fun has 「彈一場完美戀愛」 and
 * 「惟有香如故」, this one has 「蘭香如故」, 「慶餘年」 and a film section
 * chinaq.fun has no equivalent of — so both are registered rather than one
 * replacing the other.
 *
 * The reason this site was added at all is its search. chinaq.fun matches on a
 * contiguous substring, so a query one character off the site's own title finds
 * nothing; MacCMS matches loosely and finds it.
 *
 * Two things shape the code below:
 *
 *  1. **The source is chosen by a `sid` cookie, not by the URL.** Every source
 *     links to the same `/video/{id}-{ep}.html`, differing only in a `#sid=N`
 *     fragment, which never reaches the server. Requesting that page with
 *     `Cookie: sid=N` is what selects which CDN the player JSON describes.
 *  2. **Films and series share one detail shape.** A film's "episodes" are
 *     quality markers — HD, 正片, HD国语 — and repeat across sources, so they
 *     cannot be used as keys the way episode numbers can.
 */

import * as cheerio from "cheerio";
import { caches } from "../cache.js";
import { SEARCH_TIMEOUT_MS } from "../config.js";
import { fetchText, normalizeUrl } from "../utils/http.js";

const BASE_URL = "https://chinaq.net.tw";

/**
 * The site's own section names, mapped to what this app calls them. Anything
 * not listed is treated as a series, which is what the bulk of the catalogue
 * is; guessing "movie" wrongly is the worse error, because it sends the client
 * down the single-stream path for something with episodes.
 */
const MOVIE_CATEGORIES = new Set(["电影", "電影", "劇情片", "剧情片", "動作片", "动作片", "喜劇片", "喜剧片", "愛情片", "爱情片", "科幻片", "恐怖片", "戰爭片", "战争片", "紀錄片", "纪录片"]);

function detailIdFrom(url) {
  const match = String(url).match(/\/voddetail\/(\d+)\.html/);
  return match ? match[1] : null;
}

async function fetchDetailHtml(url) {
  const cacheKey = `chinaqtw:detail:${url}`;
  const cached = caches.detail.get(cacheKey);
  if (cached) return cached;
  const html = await fetchText(url, { headers: { referer: `${BASE_URL}/` } });
  caches.detail.set(cacheKey, html);
  return html;
}

/** `（陸劇）` after the title, or "" when the page does not say. */
function parseCategory($) {
  const raw = $(".episode .title h1, .episode h1").first().text();
  const match = raw.match(/（([^）]+)）/) || raw.match(/\(([^)]+)\)/);
  return match ? match[1].trim() : "";
}

function parseTitle($) {
  const raw = $(".episode .title h1, .episode h1").first().text();
  return raw.replace(/（[^）]*）/g, "").replace(/\([^)]*\)/g, "").replace(/\s+/g, " ").trim();
}

/**
 * Every source lists the same episodes, so the first group is read for labels
 * and the rest ignored. Returns [{label, episodeUrl}] in page order, which is
 * already oldest-first here.
 */
function parseEpisodeList($) {
  const entries = [];
  const seen = new Set();
  $(".items a[href*='/video/']").each((_, el) => {
    const href = $(el).attr("href")?.trim();
    const label = $(el).text().trim().replace(/\s+/g, " ");
    if (!href || !label) return;
    // The same episode appears once per source. The URL is identical across
    // them — only the dropped #sid fragment differs — so the first wins.
    const path = href.split("#")[0];
    if (seen.has(path)) return;
    seen.add(path);
    entries.push({ label, episodeUrl: normalizeUrl(BASE_URL, path) });
  });
  return entries;
}

/** The `sid` and display name of every source the detail page offers. */
function parseSources(html) {
  const sources = [];
  const seen = new Set();
  const pattern = /片源(\d+)\s*:\s*([^<\s]+)/g;
  let match = pattern.exec(html);
  while (match) {
    const sid = match[1];
    if (!seen.has(sid)) {
      seen.add(sid);
      sources.push({ sid, name: match[2].trim() });
    }
    match = pattern.exec(html);
  }
  return sources;
}

export async function searchChinaqTw(keyword) {
  const cacheKey = `chinaqtw:search:${keyword}`;
  const cached = caches.search.get(cacheKey);
  if (cached) return cached;

  const html = await fetchText(
    `${BASE_URL}/vodsearch/-------------.html?wd=${encodeURIComponent(keyword)}`,
    { timeout: SEARCH_TIMEOUT_MS, headers: { referer: `${BASE_URL}/` } },
  );

  const $ = cheerio.load(html);
  const results = [];
  const seen = new Set();
  $(".content .drama").each((_, el) => {
    const node = $(el);
    const href = node.find("a[href*='/voddetail/']").attr("href")
      || node.attr("href");
    if (!href) return;
    const url = normalizeUrl(BASE_URL, href.split("#")[0]);
    if (!detailIdFrom(url) || seen.has(url)) return;
    seen.add(url);

    // The row holds the title and, in a second .title, the cast. The first is
    // the one that names the show.
    const title = node.find(".title").first().text().trim().replace(/\s+/g, " ");
    if (!title) return;

    const category = node.find(".icon-region").first().text().trim();
    const background = node.find("[style*='background-image']").attr("style") || "";
    const poster = background.match(/url\(([^)]+)\)/);

    results.push({
      provider: "chinaqtw",
      title,
      url,
      posterUrl: poster ? normalizeUrl(BASE_URL, poster[1].replace(/['"]/g, "")) : "",
      mediaType: MOVIE_CATEGORIES.has(category) ? "movie" : "tv",
    });
  });

  caches.search.set(cacheKey, results);
  return results;
}

export async function getChinaqTwItem(item) {
  const html = await fetchDetailHtml(item.url);
  const $ = cheerio.load(html);

  const entries = parseEpisodeList($);
  if (entries.length === 0) {
    // Same hole as the other providers': the page renders but lists nothing
    // playable. Said as a 404 so the library records the title as gone and the
    // Watch page offers the other providers.
    const error = new Error("chinaq.net.tw lists nothing playable for this title yet.");
    error.statusCode = 404;
    throw error;
  }

  const category = parseCategory($);
  const isMovie = MOVIE_CATEGORIES.has(category) || item.mediaType === "movie";

  return {
    provider: "chinaqtw",
    mediaType: isMovie ? "movie" : "tv",
    title: parseTitle($) || item.title,
    posterUrl: item.posterUrl || "",
    detailUrl: item.url,
    episodes: entries.map((entry) => entry.label),
  };
}

export async function getChinaqTwEpisodes(detailUrl) {
  const html = await fetchDetailHtml(detailUrl);
  return parseEpisodeList(cheerio.load(html)).map((entry) => entry.label);
}

/**
 * One request per source, because the source is chosen by cookie rather than
 * by URL and the page only ever describes the one that is selected.
 */
export async function getChinaqTwEpisodeStreams(detailUrl, episodeLabel) {
  const html = await fetchDetailHtml(detailUrl);
  const $ = cheerio.load(html);

  const entries = parseEpisodeList($);
  const entry = entries.find((candidate) => candidate.label === episodeLabel)
    // A film's markers repeat across sources and the list is deduplicated by
    // URL, so an exact miss falls back to the first entry rather than failing.
    || (entries.length === 1 ? entries[0] : null);
  if (!entry) {
    throw new Error(`Episode not found: ${episodeLabel}`);
  }

  const sources = parseSources(html);
  if (sources.length === 0) {
    throw new Error(`No sources listed for: ${detailUrl}`);
  }

  const settled = await Promise.allSettled(sources.map(async (source) => {
    const page = await fetchText(entry.episodeUrl, {
      headers: { referer: detailUrl, cookie: `sid=${source.sid}` },
    });
    const match = page.match(/var\s+player_\w+\s*=\s*(\{.*?\})\s*<\/script>/s);
    if (!match) return null;

    // The player block is scraped out of a page, so its contents are whatever
    // the site emitted. A source whose JSON will not parse is skipped like one
    // that offered no URL at all, rather than failing the whole episode.
    let config;
    try {
      config = JSON.parse(match[1]);
    } catch {
      return null;
    }

    const url = typeof config.url === "string" ? config.url.trim() : "";
    if (!url) return null;
    return {
      // The site's own name for the CDN, which is what the player menu shows;
      // 片源N is a position in a list and means nothing to a viewer.
      sourceLabel: config.from || source.name || `source${source.sid}`,
      episodeLabel,
      url,
    };
  }));

  const streams = [];
  const seen = new Set();
  for (const result of settled) {
    if (result.status !== "fulfilled" || !result.value) continue;
    // Several sids can resolve to one CDN URL; the player would show them as
    // separate choices that behave identically.
    if (seen.has(result.value.url)) continue;
    seen.add(result.value.url);
    streams.push(result.value);
  }
  return streams;
}
