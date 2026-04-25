import * as cheerio from "cheerio";
import { caches } from "../cache.js";
import { fetchText, normalizeUrl } from "../utils/http.js";

const BASE_URL = "https://www.movieffm.net";
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

  const results = [];
  const seen = new Set();
  for (let page = 1; page <= 3; page += 1) {
    let url = `${BASE_URL}${SEARCH_PATH}?q=${encodeURIComponent(keyword)}`;
    if (page > 1) {
      url += `&f=_all&p=${page}`;
    }
    const html = await fetchText(url, {
      headers: { referer: `${BASE_URL}/` },
    });
    const pageResults = parseSearchResults(html);
    if (pageResults.length === 0) {
      break;
    }
    let newCount = 0;
    for (const item of pageResults) {
      if (seen.has(item.url)) {
        continue;
      }
      seen.add(item.url);
      results.push(item);
      newCount += 1;
    }
    if (newCount === 0) {
      break;
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

function buildDramaStreams(groups, tableLabels) {
  const streams = [];
  groups.forEach((group, sourceIndex) => {
    const sourceLabel = tableLabels[sourceIndex] || `Source ${sourceIndex + 1}`;
    const episodes = Array.isArray(group) ? group : Object.values(group);
    episodes.forEach((episode) => {
      if (typeof episode.url !== "string" || !episode.url.includes(".m3u8")) {
        return;
      }
      streams.push({
        sourceLabel,
        episodeLabel: String(episode.name || `EP${streams.length + 1}`),
        url: episode.url,
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
      url: item.url,
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
      const text = ($(element).parent().text() || $(element).text()).trim().replace(/\s+/g, " ");
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
