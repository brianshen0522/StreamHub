/**
 * ChinaQ (chinaq.fun).
 *
 * A sibling of dramasq: the search page markup is the same shape, and the play
 * API answers the same `{ video_plays: [{ play_data, src_site }] }` body from a
 * different path. Comparing one title across both sites returned byte-identical
 * CDN URLs, so the two share a back end — but their catalogues have drifted,
 * and chinaq carries titles dramasq has dropped. That is the reason to have
 * both rather than either.
 *
 * Three things differ from dramasq and shape the code below:
 *
 *  1. Detail pages are `/{category}/{showId}/`, where category is one of
 *     tv-tw, tv-jp, tv-cn, tv-kr, tv-th. The category is part of the URL and
 *     cannot be guessed from the id, so it is carried through rather than
 *     rebuilt.
 *  2. The episode list is `#all-ep h2 > a`, not dramasq's `div.eps`.
 *  3. Episode labels repeat the show title — "彈一場完美戀愛 第01集". Left as
 *     the site writes them would put the title on every row of the episode
 *     rail and inside every progress record, so the prefix is stripped.
 */

import * as cheerio from "cheerio";
import { caches } from "../cache.js";
import { SEARCH_TIMEOUT_MS } from "../config.js";
import { fetchJson, fetchText, normalizeUrl } from "../utils/http.js";

const BASE_URL = "https://chinaq.fun";

/**
 * `/tv-tw/202413561/` → `{ category: "tv-tw", showId: "202413561" }`.
 * Both halves are needed: the play API is keyed on the id, and the episode
 * pages live under the category.
 */
function parseDetailUrl(detailUrl) {
  const match = String(detailUrl).match(/\/(tv-[a-z]{2}|show)\/(\d+)\/?/);
  return match ? { category: match[1], showId: match[2] } : null;
}

async function fetchDetailHtml(url) {
  const cacheKey = `chinaq:detail:${url}`;
  const cached = caches.detail.get(cacheKey);
  if (cached) return cached;
  const html = await fetchText(url, { headers: { referer: `${BASE_URL}/` } });
  caches.detail.set(cacheKey, html);
  return html;
}

/**
 * Episode labels carry the show title. Strip it so a row reads "第01集", which
 * is what the rail shows and what a progress record is keyed on.
 *
 * Matched as a prefix of the label rather than by a regex over the episode
 * number, because the numbering is not uniform — "第07集", "第4集" and
 * "第37集已完結" all appear, sometimes within one show.
 */
function stripTitlePrefix(label, title) {
  const text = label.trim().replace(/\s+/g, " ");
  if (!title) return text;
  const full = title.trim().replace(/\s+/g, " ");

  // A Japanese or Korean show's heading carries both names — "大戀愛：與將我忘
  // 記的你 大戀愛～僕を忘れる君と" — but its episode labels use only the first,
  // so matching the whole heading misses and the title lands in every label.
  // Falling back to the part before the first space catches those without
  // loosening the match into a prefix search, which would eat real labels.
  const candidates = [full];
  const firstWord = full.split(" ")[0];
  if (firstWord && firstWord !== full) candidates.push(firstWord);

  for (const prefix of candidates) {
    if (!text.startsWith(prefix)) continue;
    const rest = text.slice(prefix.length).trim();
    // Only when something is left: a show whose episode is titled exactly like
    // the show keeps its label rather than becoming empty.
    if (rest) return rest;
  }
  return text;
}

/** The show title, without the site's own suffix. */
function parseTitle($) {
  const heading = $("h1").first().text().trim().replace(/\s+/g, " ");
  return heading.replace(/\s*ChinaQ線上看\s*$/, "").trim();
}

/**
 * Returns [{label, epSlug}] oldest-first.
 *
 * The page lists newest-first, and every other provider hands this app
 * chronological episodes — the resume rules walk the list by index, so the
 * order is not cosmetic.
 */
function parseEpisodeList(html, title) {
  const $ = cheerio.load(html);
  const entries = [];
  const seen = new Set();
  $("#all-ep h2 a[href*='/ep']").each((_, el) => {
    const href = $(el).attr("href")?.trim();
    const raw = $(el).text().trim();
    if (!href || !raw || seen.has(href)) return;
    seen.add(href);
    // /tv-tw/202413561/ep12.html → ep12
    const epSlug = href.replace(/\.html$/, "").split("/").pop();
    entries.push({ label: stripTitlePrefix(raw, title), epSlug });
  });
  return entries.toReversed();
}

export async function searchChinaq(keyword) {
  const cacheKey = `chinaq:search:${keyword}`;
  const cached = caches.search.get(cacheKey);
  if (cached) return cached;

  const html = await fetchText(
    `${BASE_URL}/search?q=${encodeURIComponent(keyword)}`,
    { timeout: SEARCH_TIMEOUT_MS, headers: { referer: `${BASE_URL}/` } },
  );

  const $ = cheerio.load(html);
  const results = [];
  const seen = new Set();
  $("div.list a.drama").each((_, el) => {
    const href = $(el).attr("href")?.trim();
    if (!href) return;
    const parsed = parseDetailUrl(href);
    // Anything that is not a show detail link — section nav, ads — has no id.
    if (!parsed) return;
    const url = normalizeUrl(BASE_URL, href);
    if (seen.has(url)) return;
    seen.add(url);
    results.push({
      provider: "chinaq",
      // Search rows read "戀愛是科學 第21集": the title plus how far the site
      // has got. Kept whole here, because it is how the site names the row and
      // the same shape dramasq returns; the detail page supplies the clean
      // title once a row is opened.
      title: $(el).text().trim().replace(/\s+/g, " "),
      url,
      posterUrl: `${BASE_URL}/img/${parsed.showId}.jpg`,
      // Every section of this site is a series. There is no film catalogue.
      mediaType: "tv",
    });
  });

  caches.search.set(cacheKey, results);
  return results;
}

export async function getChinaqItem(item) {
  const html = await fetchDetailHtml(item.url);
  const $ = cheerio.load(html);

  const parsed = parseDetailUrl(item.url);
  const title = parseTitle($) || item.title;
  const entries = parseEpisodeList(html, title);

  if (entries.length === 0) {
    // Same shape of hole as dramasq's: the page renders, the episode container
    // is empty, and no slug answers. Said as a 404 so the library can record
    // the title as gone and the Watch page can offer the other providers,
    // rather than as an error that reads like a bug here.
    const error = new Error("chinaq lists no episodes for this title yet.");
    error.statusCode = 404;
    throw error;
  }

  return {
    provider: "chinaq",
    mediaType: "tv",
    title,
    posterUrl: parsed ? `${BASE_URL}/img/${parsed.showId}.jpg` : item.posterUrl,
    detailUrl: item.url,
    episodes: entries.map((e) => e.label),
  };
}

export async function getChinaqEpisodes(detailUrl) {
  const html = await fetchDetailHtml(detailUrl);
  const $ = cheerio.load(html);
  return parseEpisodeList(html, parseTitle($)).map((e) => e.label);
}

export async function getChinaqEpisodeStreams(detailUrl, episodeLabel) {
  const html = await fetchDetailHtml(detailUrl);
  const $ = cheerio.load(html);
  const entries = parseEpisodeList(html, parseTitle($));

  const entry = entries.find((e) => e.label === episodeLabel);
  if (!entry) {
    throw new Error(`Episode not found: ${episodeLabel}`);
  }

  const parsed = parseDetailUrl(detailUrl);
  if (!parsed) {
    throw new Error(`Could not extract show ID from: ${detailUrl}`);
  }

  const apiUrl = `${BASE_URL}/qplays/${parsed.showId}/${entry.epSlug}`;
  const data = await fetchJson(apiUrl, {
    headers: {
      referer: `${BASE_URL}/${parsed.category}/${parsed.showId}/${entry.epSlug}.html`,
    },
  });

  return (data.video_plays ?? [])
    .map((play, index) => {
      const url = play.play_data?.trim();
      if (!url) return null;
      return {
        sourceLabel: play.src_site || `source${index + 1}`,
        episodeLabel,
        url,
      };
    })
    .filter(Boolean);
}
