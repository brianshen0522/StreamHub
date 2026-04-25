import * as cheerio from "cheerio";
import { caches } from "../cache.js";
import { SEARCH_TIMEOUT_MS } from "../config.js";
import { fetchJson, fetchText, normalizeUrl } from "../utils/http.js";

const BASE_URL = "https://dramasq.io";

function extractShowId(detailUrl) {
  const match = detailUrl.match(/\/detail\/(\d+)\.html/);
  return match ? match[1] : null;
}

async function fetchDetailHtml(url) {
  const cacheKey = `dramasq:detail:${url}`;
  const cached = caches.detail.get(cacheKey);
  if (cached) return cached;
  const html = await fetchText(url, { headers: { referer: `${BASE_URL}/` } });
  caches.detail.set(cacheKey, html);
  return html;
}

// Returns [{label, epSlug}] in chronological order (ep1 first).
function parseEpisodeList(html) {
  const $ = cheerio.load(html);
  const entries = [];
  const seen = new Set();
  $("div.eps a[href*='/vodplay/']").each((_, el) => {
    const href = $(el).attr("href")?.trim();
    const label = $(el).text().trim().replace(/\s+/g, " ");
    if (!href || !label || seen.has(href)) return;
    seen.add(href);
    // /vodplay/201940342/ep44.html → ep44
    const epSlug = href.replace(/\.html$/, "").split("/").pop();
    entries.push({ label, epSlug });
  });
  // Page lists newest-first; reverse to chronological
  return entries.reverse();
}

export async function searchDramasq(keyword) {
  const cacheKey = `dramasq:search:${keyword}`;
  const cached = caches.search.get(cacheKey);
  if (cached) return cached;

  const html = await fetchText(
    `${BASE_URL}/search?q=${encodeURIComponent(keyword)}`,
    { timeout: SEARCH_TIMEOUT_MS, headers: { referer: `${BASE_URL}/` } },
  );

  const $ = cheerio.load(html);
  const results = [];
  const seen = new Set();
  $("div.list a.drama[href*='/detail/']").each((_, el) => {
    const href = $(el).attr("href")?.trim();
    if (!href) return;
    const url = normalizeUrl(BASE_URL, href);
    if (seen.has(url)) return;
    seen.add(url);
    const title = $(el).text().trim().replace(/\s+/g, " ");
    const showId = extractShowId(href);
    results.push({
      provider: "dramasq",
      title,
      url,
      posterUrl: showId ? `${BASE_URL}/uuimg/${showId}.jpg` : "",
      mediaType: "tv",
    });
  });

  caches.search.set(cacheKey, results);
  return results;
}

export async function getDramasqItem(item) {
  const html = await fetchDetailHtml(item.url);
  const $ = cheerio.load(html);

  const title = $(".title h1").first().text().trim() || item.title;
  const posterSrc = $(".pinfo img").first().attr("src")?.trim() || "";
  const posterUrl = posterSrc ? normalizeUrl(BASE_URL, posterSrc) : item.posterUrl;

  const entries = parseEpisodeList(html);
  if (entries.length === 0) {
    throw new Error("Could not extract dramasq episode list.");
  }

  return {
    provider: "dramasq",
    mediaType: "tv",
    title,
    posterUrl,
    detailUrl: item.url,
    episodes: entries.map((e) => e.label),
  };
}

export async function getDramasqEpisodes(detailUrl) {
  const html = await fetchDetailHtml(detailUrl);
  const entries = parseEpisodeList(html);
  return entries.map((e) => e.label);
}

export async function getDramasqEpisodeStreams(detailUrl, episodeLabel) {
  const html = await fetchDetailHtml(detailUrl);
  const entries = parseEpisodeList(html);

  const entry = entries.find((e) => e.label === episodeLabel);
  if (!entry) {
    throw new Error(`Episode not found: ${episodeLabel}`);
  }

  const showId = extractShowId(detailUrl);
  if (!showId) {
    throw new Error(`Could not extract show ID from: ${detailUrl}`);
  }

  const apiUrl = `${BASE_URL}/drq/${showId}/${entry.epSlug}`;
  const data = await fetchJson(apiUrl, {
    headers: { referer: `${BASE_URL}/vodplay/${showId}/${entry.epSlug}.html` },
  });

  return (data.video_plays ?? [])
    .map((item, index) => {
      const url = item.play_data?.trim();
      if (!url) return null;
      return {
        sourceLabel: item.src_site || `source${index + 1}`,
        episodeLabel,
        url,
      };
    })
    .filter(Boolean);
}
