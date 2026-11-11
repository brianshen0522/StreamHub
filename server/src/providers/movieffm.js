import * as cheerio from "cheerio";
import { caches } from "../cache.js";
import { SEARCH_TIMEOUT_MS } from "../config.js";
import { fetchText, normalizeUrl } from "../utils/http.js";

// The site moved from www.movieffm.net, which now only 301s here. Fetching the
// old host still works but costs a redirect hop on every request.
const BASE_URL = "https://www.mvffm.net";
const SEARCH_PATH = "/xssearch";

function normalizeMediaType(raw) {
  const text = raw.trim();
  if (text === "電影") {
    return "movie";
  }
  if (text === "電視劇" || text === "連續劇") {
    return "tv";
  }
  return "unknown";
}

function parseSearchResults(html) {
  const $ = cheerio.load(html);
  const results = [];
  $(".result-item").each((_, element) => {
    const titleNode = $(element).find("div.title a").first();
    const typeNode = $(element).find("div.thumbnail span").first();
    const posterNode = $(element).find("div.thumbnail img").first();
    const title = titleNode.text().trim().replace(/\s+/g, " ");
    const url = titleNode.attr("href");
    if (!title || !url) {
      return;
    }
    results.push({
      provider: "movieffm",
      title,
      url,
      posterUrl: posterNode.attr("src")?.trim() ?? "",
      mediaType: normalizeMediaType(typeNode.text() || "unknown"),
      rawType: typeNode.text().trim() || "Unknown",
    });
  });
  return results;
}

export async function searchMovieffm(keyword) {
  const cacheKey = `movieffm:search:${keyword}`;
  const cached = caches.search.get(cacheKey);
  if (cached) {
    return cached;
  }

  const pageUrl = (page) => {
    const base = `${BASE_URL}${SEARCH_PATH}?q=${encodeURIComponent(keyword)}`;
    return page > 1 ? `${base}&f=_all&p=${page}` : base;
  };
  const fetchPage = (page) => fetchText(pageUrl(page), {
    headers: { referer: `${BASE_URL}/` },
    timeout: SEARCH_TIMEOUT_MS,
  }).then(parseSearchResults);

  // Page one decides whether there is anything at all; pages two and three
  // are then fetched together rather than one after another. Sequentially,
  // three pages at up to SEARCH_TIMEOUT_MS each outlasted the web client's
  // 12 s per-provider deadline, so a slow afternoon on this site meant the
  // whole provider showed nothing. A later page that fails costs only its own
  // rows — the site's ranking puts the real matches on page one.
  const pages = [await fetchPage(1)];
  if (pages[0].length > 0) {
    const rest = await Promise.allSettled([fetchPage(2), fetchPage(3)]);
    for (const result of rest) {
      if (result.status === "fulfilled") pages.push(result.value);
    }
  }

  const results = [];
  const seen = new Set();
  for (const pageResults of pages) {
    for (const item of pageResults) {
      if (seen.has(item.url)) continue;
      seen.add(item.url);
      results.push(item);
    }
  }

  caches.search.set(cacheKey, results);
  return results;
}

async function fetchDetailHtml(url) {
  const cacheKey = `movieffm:detail:${url}`;
  const cached = caches.detail.get(cacheKey);
  if (cached) {
    return cached;
  }
  const html = await fetchText(url, {
    headers: { referer: `${BASE_URL}/` },
  });
  caches.detail.set(cacheKey, html);
  return html;
}

function extractTableLabels(pageHtml) {
  const match = pageHtml.match(/tables:(\[\{.*?\}\])\s*,tbcur:/s);
  if (!match) {
    return [];
  }
  const tables = JSON.parse(match[1].replaceAll("\\/", "/"));
  return tables.map((item) => {
    const $ = cheerio.load(item.ht || "");
    return $.text().trim().replace(/\s+/g, " ");
  });
}

// videourls entries are no longer plain URLs but base64 — of the URL itself on
// drama pages, of its percent-encoded form on movie pages. The site's player
// undoes both with `decodeURIComponent(atob(raw))` and falls back to the raw
// value when that throws, so this does the same: an older page that still
// carries a plain URL passes through unchanged.
function decodeVideoUrl(raw) {
  if (typeof raw !== "string") return raw;
  if (/^https?:\/\//i.test(raw)) return raw;
  try {
    const decoded = decodeURIComponent(Buffer.from(raw, "base64").toString("utf8"));
    return /^https?:\/\//i.test(decoded) ? decoded : raw;
  } catch {
    return raw;
  }
}

function buildDramaStreams(groups, tableLabels) {
  const streams = [];
  groups.forEach((group, sourceIndex) => {
    const sourceLabel = tableLabels[sourceIndex] || `Source ${sourceIndex + 1}`;
    const episodes = Array.isArray(group) ? group : Object.values(group);
    episodes.forEach((episode) => {
      const url = decodeVideoUrl(episode.url);
      if (typeof url !== "string" || !url.includes(".m3u8")) {
        return;
      }
      streams.push({
        sourceLabel,
        episodeLabel: String(episode.name || `EP${streams.length + 1}`),
        url,
      });
    });
  });
  return streams;
}

function buildMovieStreams(sources) {
  return sources
    .filter((item) => typeof item.url === "string" && ["hls", "mp4"].includes(item.type))
    .map((item, index) => ({
      sourceLabel: `Source ${Number(item.source ?? index) + 1}`,
      episodeLabel: "Movie",
      url: decodeVideoUrl(item.url),
    }));
}

function canonicalEpisodeKey(label) {
  const numbers = label.match(/\d+/g);
  if (numbers?.length) {
    return String(Number(numbers[numbers.length - 1]));
  }
  return label.trim().toLowerCase();
}

export async function getMovieffmItem(item) {
  const html = await fetchDetailHtml(item.url);
  if (item.url.includes("/tvshows/")) {
    const $ = cheerio.load(html);
    const seasons = [];
    const seen = new Set();
    $("a[href]").each((_, element) => {
      const href = $(element).attr("href")?.trim();
      if (!href || !href.includes("/drama/")) {
        return;
      }
      const url = normalizeUrl(item.url, href);
      if (seen.has(url)) {
        return;
      }
      // The link is <span class="se-t">2</span><span class="title">Season 2
      // <i>2005</i><i>全24集</i></span>. Reading the whole anchor glues the
      // numbering badge onto the title ("2Season 2 2005全24集"), so drop it.
      const node = $(element).clone();
      node.find(".se-t").remove();
      const text = (node.text() || $(element).text()).trim().replace(/\s+/g, " ");
      if (!text.includes("Season") && !text.includes("全")) {
        return;
      }
      seen.add(url);
      seasons.push({ label: text || url, url });
    });
    return {
      provider: "movieffm",
      mediaType: "tv",
      title: item.title,
      posterUrl: item.posterUrl,
      seasons,
    };
  }

  const dramaMatch = html.match(/videourls:(\[\[.*?\]\])\s*,tables:/s);
  const movieMatch = html.match(/videourls:(\[.*?\])\s*,isActive:/s);
  if (dramaMatch) {
    const groups = JSON.parse(dramaMatch[1].replaceAll("\\/", "/"));
    const tableLabels = extractTableLabels(html);
    const streams = buildDramaStreams(groups, tableLabels);
    const episodes = [];
    const seen = new Set();
    for (const stream of streams) {
      const key = canonicalEpisodeKey(stream.episodeLabel);
      if (!seen.has(key)) {
        seen.add(key);
        episodes.push(stream.episodeLabel);
      }
    }
    return {
      provider: "movieffm",
      mediaType: "tv",
      title: item.title,
      posterUrl: item.posterUrl,
      seasonUrl: item.url,
      episodes,
    };
  }
  if (movieMatch) {
    const sources = JSON.parse(movieMatch[1].replaceAll("\\/", "/"));
    return {
      provider: "movieffm",
      mediaType: "movie",
      title: item.title,
      posterUrl: item.posterUrl,
      streams: buildMovieStreams(sources),
    };
  }
  throw new Error("Could not extract MovieFFM item details.");
}

export async function getMovieffmSeasonEpisodes(seasonUrl) {
  const html = await fetchDetailHtml(seasonUrl);
  const dramaMatch = html.match(/videourls:(\[\[.*?\]\])\s*,tables:/s);
  if (!dramaMatch) {
    throw new Error("Could not extract season episodes.");
  }
  const groups = JSON.parse(dramaMatch[1].replaceAll("\\/", "/"));
  const labels = [];
  const seen = new Set();
  for (const stream of buildDramaStreams(groups, extractTableLabels(html))) {
    const key = canonicalEpisodeKey(stream.episodeLabel);
    if (seen.has(key)) {
      continue;
    }
    seen.add(key);
    labels.push(stream.episodeLabel);
  }
  return labels;
}

export async function getMovieffmEpisodeStreams(seasonUrl, episodeLabel) {
  const html = await fetchDetailHtml(seasonUrl);
  const dramaMatch = html.match(/videourls:(\[\[.*?\]\])\s*,tables:/s);
  if (!dramaMatch) {
    throw new Error("Could not extract episode streams.");
  }
  const groups = JSON.parse(dramaMatch[1].replaceAll("\\/", "/"));
  const streams = buildDramaStreams(groups, extractTableLabels(html));
  const selectedKey = canonicalEpisodeKey(episodeLabel);
  return streams.filter((stream) => canonicalEpisodeKey(stream.episodeLabel) === selectedKey);
}
