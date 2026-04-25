import * as cheerio from "cheerio";
import { caches } from "../cache.js";
import { fetchText, normalizeUrl } from "../utils/http.js";

const BASE_URL = "https://777tv.ai";
const PLAY_BASE_URL = "https://play.777tv.ai";
const SEARCH_PATH = "/vod/search.html";

function normalizeMediaType(raw) {
  const text = raw.trim();
  if (["電影", "动作片", "劇情片", "喜劇片"].includes(text)) {
    return "movie";
  }
  if (["電視劇", "连续剧", "連續劇", "美劇", "韓劇", "陸劇", "港劇", "日劇", "台劇", "泰劇"].includes(text)) {
    return "tv";
  }
  return "unknown";
}

async function fetchDetailHtml(url) {
  const cacheKey = `777tv:detail:${url}`;
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

export async function search777tv(keyword) {
  const cacheKey = `777tv:search:${keyword}`;
  const cached = caches.search.get(cacheKey);
  if (cached) {
    return cached;
  }

  const responseHtml = await fetchText(`${BASE_URL}${SEARCH_PATH}`, {
    method: "POST",
    headers: {
      "content-type": "application/x-www-form-urlencoded",
      referer: `${BASE_URL}/`,
    },
    body: new URLSearchParams({ wd: keyword, submit: "" }).toString(),
  });

  const $ = cheerio.load(responseHtml);
  const results = [];
  const seen = new Set();
  $("li.stui-vodlist__item").each((_, element) => {
    const titleLink = $(element).find("h4.stui-vodlist__title a[href*='/vod/detail/id/']").first();
    const thumb = $(element).find("a.stui-vodlist__thumb[href*='/vod/detail/id/']").first();
    const anchor = titleLink.attr("href") ? titleLink : thumb;
    const href = anchor.attr("href")?.trim();
    if (!href) {
      return;
    }
    const url = normalizeUrl(BASE_URL, href);
    if (seen.has(url)) {
      return;
    }
    seen.add(url);
    results.push({
      provider: "777tv",
      title: anchor.attr("title")?.trim() || anchor.text().trim().replace(/\s+/g, " "),
      url,
      posterUrl: thumb.attr("data-original")?.trim() || thumb.attr("data-src")?.trim() || "",
      mediaType: "unknown",
      rawType: $(element).find(".pic-text").text().trim() || "Unknown",
    });
  });

  await Promise.all(
    results.map(async (item) => {
      item.mediaType = await detect777tvMediaType(item.url, item.rawType);
    }),
  );

  caches.search.set(cacheKey, results);
  return results;
}

async function detect777tvMediaType(detailUrl, fallback) {
  const cacheKey = `777tv:type:${detailUrl}`;
  const cached = caches.mediaType.get(cacheKey);
  if (cached) {
    return cached;
  }
  try {
    const html = await fetchDetailHtml(detailUrl);
    const $ = cheerio.load(html);
    const active = $(".stui-header__menu li.active a").first().text().trim();
    const mediaType = normalizeMediaType(active || fallback);
    caches.mediaType.set(cacheKey, mediaType);
    return mediaType;
  } catch {
    return normalizeMediaType(fallback);
  }
}

function groupKey(label, playUrl) {
  const numbers = label.match(/\d+/g);
  if (numbers?.length) {
    return `ep:${Number(numbers[numbers.length - 1])}`;
  }
  const normalized = label.trim().toLowerCase();
  if ([
    "正片",
    "hd",
    "hd中字",
    "hd國語",
    "hd国语",
    "国语",
    "國語",
    "中字",
    "tc",
    "tc中字",
    "tcv2",
    "預告片",
    "预告片",
  ].includes(normalized)) {
    return `variant:${normalized}`;
  }
  if (!label) {
    const nidMatch = playUrl.match(/\/nid\/(\d+)\.html/);
    if (nidMatch) {
      return `nid:${nidMatch[1]}`;
    }
  }
  return `label:${normalized}`;
}

function extractPlayOptions(html) {
  const $ = cheerio.load(html);
  const options = [];
  $("div.stui-pannel").each((_, element) => {
    const sourceLabel = $(element).find("div.stui-pannel__head h4.title").first().text().trim().replace(/\s+/g, " ");
    const playlist = $(element).find("ul.stui-content__playlist").first();
    if (!sourceLabel || playlist.length === 0) {
      return;
    }
    playlist.find("a[href*='/vod/play/']").each((__, anchor) => {
      const href = $(anchor).attr("href")?.trim();
      if (!href) {
        return;
      }
      const episodeLabel = $(anchor).text().trim().replace(/\s+/g, " ");
      const url = normalizeUrl(PLAY_BASE_URL, href);
      options.push({
        sourceLabel,
        episodeLabel,
        url,
        groupKey: groupKey(episodeLabel, url),
      });
    });
  });
  return options;
}

async function extractStreamUrl(playUrl) {
  const html = await fetchText(playUrl, {
    headers: { referer: `${BASE_URL}/` },
  });
  const match =
    html.match(/var\s+player_\w+\s*=\s*(\{.*?\})<\/script>/s) ||
    html.match(/var\s+player_data\s*=\s*(\{.*?\})<\/script>/s);
  if (!match) {
    return null;
  }
  const payload = JSON.parse(match[1].replaceAll("\\/", "/"));
  return typeof payload.url === "string" ? payload.url : null;
}

export async function get777tvItem(item) {
  const html = await fetchDetailHtml(item.url);
  const options = extractPlayOptions(html);
  if (options.length === 0) {
    throw new Error("Could not extract 777tv item details.");
  }
  if (item.mediaType === "movie") {
    return {
      provider: "777tv",
      mediaType: "movie",
      title: item.title,
      posterUrl: item.posterUrl,
      streams: await Promise.all(
        options.map(async (option) => {
          const url = await extractStreamUrl(option.url);
          return url
            ? {
                sourceLabel: option.sourceLabel,
                episodeLabel: "Movie",
                url,
              }
            : null;
        }),
      ).then((items) => items.filter(Boolean)),
    };
  }

  const episodes = [];
  const seen = new Set();
  for (const option of options) {
    if (!seen.has(option.groupKey)) {
      seen.add(option.groupKey);
      episodes.push(option.episodeLabel);
    }
  }
  return {
    provider: "777tv",
    mediaType: "tv",
    title: item.title,
    posterUrl: item.posterUrl,
    detailUrl: item.url,
    episodes,
  };
}

export async function get777tvEpisodes(detailUrl) {
  const html = await fetchDetailHtml(detailUrl);
  const options = extractPlayOptions(html);
  const episodes = [];
  const seen = new Set();
  for (const option of options) {
    if (!seen.has(option.groupKey)) {
      seen.add(option.groupKey);
      episodes.push(option.episodeLabel);
    }
  }
  return episodes;
}

export async function get777tvEpisodeStreams(detailUrl, episodeLabel) {
  const html = await fetchDetailHtml(detailUrl);
  const options = extractPlayOptions(html);
  const selectedKey = groupKey(episodeLabel, "");
  const matched = options.filter((option) => option.groupKey === selectedKey);
  const streams = await Promise.all(
    matched.map(async (option) => {
      const url = await extractStreamUrl(option.url);
      return url
        ? {
            sourceLabel: option.sourceLabel,
            episodeLabel: option.episodeLabel,
            url,
          }
        : null;
    }),
  );
  return streams.filter(Boolean);
}
