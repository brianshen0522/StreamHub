import { getBearerToken } from "./auth.js";
import { caches } from "./cache.js";
import { REQUEST_TIMEOUT_MS, STREAM_PROXY_TIMEOUT_MS } from "./config.js";
import { analyzePlaylist, stripAds } from "./utils/adfilter.js";
import { BlockedTargetError, assertPublicUrl, safeFetch } from "./utils/safe-fetch.js";

const HOP_BY_HOP_HEADERS = new Set([
  "connection",
  "keep-alive",
  "proxy-authenticate",
  "proxy-authorization",
  "te",
  "trailers",
  "transfer-encoding",
  "upgrade",
  "host",
  "content-length",
  "content-encoding",
]);

const POSTER_CONTENT_TYPES = ["image/jpeg", "image/png", "image/webp", "image/avif", "image/gif"];

function copyHeaders(upstreamHeaders, response) {
  upstreamHeaders.forEach((value, key) => {
    if (HOP_BY_HOP_HEADERS.has(key.toLowerCase())) {
      return;
    }
    response.setHeader(key, value);
  });
}

/**
 * Segment, key and variant URLs inside a rewritten playlist point back at this
 * proxy, and hls.js fetches them as plain media requests that cannot carry an
 * Authorization header. The token has to ride along in the query string the
 * same way it does on the playlist request that got us here — without it every
 * segment answers 401 and proxy playback stalls on a black screen.
 */
function buildProxyUrl(request, targetUrl) {
  const url = `/api/stream?target=${encodeURIComponent(targetUrl)}`;
  const token = getBearerToken(request);
  return token ? `${url}&accessToken=${encodeURIComponent(token)}` : url;
}

function rewritePlaylist(request, sourceUrl, content) {
  const lines = content.split("\n");
  return lines
    .map((line) => {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith("#")) {
        if (trimmed.startsWith("#EXT-X-KEY")) {
          return line.replace(/URI="([^"]+)"/, (_, uri) => {
            const keyUrl = new URL(uri, sourceUrl).toString();
            return `URI="${buildProxyUrl(request, keyUrl)}"`;
          });
        }
        return line;
      }
      const absoluteUrl = new URL(trimmed, sourceUrl).toString();
      return buildProxyUrl(request, absoluteUrl);
    })
    .join("\n");
}

const UA =
  "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

async function readLimited(body, maxBytes) {
  if (!body) return "";
  const reader = body.getReader();
  const chunks = [];
  let total = 0;
  try {
    while (total < maxBytes) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value);
      total += value.byteLength;
    }
  } finally {
    reader.cancel().catch(() => {});
  }
  const buf = new Uint8Array(Math.min(total, maxBytes));
  let offset = 0;
  for (const chunk of chunks) {
    const n = Math.min(chunk.byteLength, maxBytes - offset);
    buf.set(chunk.subarray(0, n), offset);
    offset += n;
    if (offset >= maxBytes) break;
  }
  return new TextDecoder("utf-8", { fatal: false }).decode(buf);
}

function isValidM3u8Content(text) {
  const t = text.trimStart();
  if (!t.startsWith("#EXTM3U")) return false;
  // must contain at least one segment or sub-stream reference
  return (
    t.includes("#EXTINF") ||
    t.includes("#EXT-X-STREAM-INF") ||
    t.includes("#EXT-X-TARGETDURATION") ||
    t.includes("#EXT-X-MEDIA-SEQUENCE")
  );
}

function parseM3u8DurationSeconds(text) {
  const matches = text.matchAll(/#EXTINF:([\d.]+)/g);
  let total = 0;
  let count = 0;
  for (const match of matches) {
    const seconds = Number.parseFloat(match[1]);
    if (Number.isFinite(seconds) && seconds > 0) {
      total += seconds;
      count += 1;
    }
  }
  return count > 0 ? Math.round(total) : null;
}

function extractVariantPlaylistUrls(sourceUrl, text) {
  const lines = text.split("\n");
  const urls = [];
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index].trim();
    if (!line.startsWith("#EXT-X-STREAM-INF")) {
      continue;
    }
    const nextLine = lines[index + 1]?.trim();
    if (!nextLine || nextLine.startsWith("#")) {
      continue;
    }
    try {
      urls.push(new URL(nextLine, sourceUrl).toString());
    } catch {}
  }
  return urls;
}

async function fetchTextSnippet(url, maxBytes = 256_000) {
  const response = await fetch(url, {
    redirect: "follow",
    headers: {
      "user-agent": UA,
      range: `bytes=0-${Math.max(1024, maxBytes - 1)}`,
    },
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
  });
  if (response.status !== 200 && response.status !== 206) {
    throw new Error(`Upstream responded ${response.status}`);
  }
  return readLimited(response.body, maxBytes);
}

async function inspectM3u8Metadata(url, depth = 0) {
  const text = await fetchTextSnippet(url);
  if (!isValidM3u8Content(text)) {
    return { durationSeconds: null, adSeconds: 0, playlistType: "invalid" };
  }

  const durationSeconds = parseM3u8DurationSeconds(text);
  if (durationSeconds !== null) {
    // Match the player, which strips spliced ad segments before playback —
    // otherwise the source list advertises a longer runtime than the timeline.
    const { contentSeconds, adSeconds } = analyzePlaylist(text, url);
    return {
      durationSeconds: adSeconds > 0 ? Math.round(contentSeconds) : durationSeconds,
      adSeconds: Math.round(adSeconds),
      playlistType: "media",
    };
  }

  if (depth >= 1) {
    return { durationSeconds: null, adSeconds: 0, playlistType: "master" };
  }

  const variantUrls = extractVariantPlaylistUrls(url, text);
  if (variantUrls.length === 0) {
    return { durationSeconds: null, adSeconds: 0, playlistType: "master" };
  }

  const inspectedVariants = await Promise.all(
    variantUrls.slice(0, 5).map(async (variantUrl) => {
      try {
        return await inspectM3u8Metadata(variantUrl, depth + 1);
      } catch {
        return null;
      }
    }),
  );

  const longest = inspectedVariants.filter(Boolean).reduce((current, variant) => (
    Number.isFinite(variant.durationSeconds) && variant.durationSeconds > (current?.durationSeconds ?? -1)
      ? variant
      : current
  ), null);

  return {
    durationSeconds: longest?.durationSeconds ?? null,
    adSeconds: longest?.adSeconds ?? 0,
    playlistType: "master",
  };
}

export async function getStreamMetadata(stream) {
  const cached = caches.streamMetadata.get(stream.url);
  if (cached) {
    return { ...stream, ...cached };
  }

  let metadata = { durationSeconds: null, adSeconds: 0 };
  if (stream.url.toLowerCase().includes(".m3u8")) {
    try {
      await assertPublicUrl(stream.url);
      const inspected = await inspectM3u8Metadata(stream.url);
      metadata = {
        durationSeconds: Number.isFinite(inspected.durationSeconds) ? inspected.durationSeconds : null,
        adSeconds: inspected.adSeconds || 0,
      };
    } catch {
      metadata = { durationSeconds: null, adSeconds: 0 };
    }
  }

  caches.streamMetadata.set(stream.url, metadata);
  return { ...stream, ...metadata };
}

async function checkM3u8(url) {
  let response;
  try {
    response = await fetch(url, {
      redirect: "follow",
      headers: {
        "user-agent": UA,
        range: "bytes=0-4095",
      },
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    if (response.status !== 200 && response.status !== 206) {
      return { ok: false, statusCode: response.status };
    }
    const text = await readLimited(response.body, 4096);
    return { ok: isValidM3u8Content(text), statusCode: response.status };
  } catch {
    return { ok: false, statusCode: null };
  }
}

async function checkGeneric(url) {
  let response;
  try {
    response = await fetch(url, {
      method: "HEAD",
      redirect: "follow",
      headers: { "user-agent": UA },
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    if ([403, 405, 500, 501].includes(response.status)) {
      response = await fetch(url, {
        method: "GET",
        redirect: "follow",
        headers: { range: "bytes=0-0", "user-agent": UA },
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      });
    }
    return {
      ok: response.status >= 200 && response.status < 300,
      statusCode: response.status,
    };
  } catch {
    return { ok: false, statusCode: null };
  } finally {
    response?.body?.cancel?.().catch?.(() => {});
  }
}

export async function checkStream(stream) {
  const cached = caches.streamCheck.get(stream.url);
  if (cached) return { ...stream, ...cached };

  // /api/check-sources takes the stream list from the request body, so these
  // URLs are whatever the caller sent. One that points inside the network fails
  // its check and is never emitted, rather than being fetched to find out.
  try {
    await assertPublicUrl(stream.url);
  } catch {
    const blocked = { ok: false, statusCode: null };
    caches.streamCheck.set(stream.url, blocked);
    return { ...stream, ...blocked };
  }

  const result = stream.url.toLowerCase().includes(".m3u8")
    ? await checkM3u8(stream.url)
    : await checkGeneric(stream.url);

  caches.streamCheck.set(stream.url, result);
  return { ...stream, ...result };
}

export async function streamCheckedSources(rawStreams, preferredLabel, onSource) {
  if (rawStreams.length === 0) return;

  async function processOne(stream) {
    const enriched = await getStreamMetadata(stream);
    const checked = await checkStream(enriched);
    if (checked.ok) {
      onSource({
        ...checked,
        directUrl: checked.url,
        proxyUrl: `/api/stream?target=${encodeURIComponent(checked.url)}`,
      });
    }
  }

  // Preferred stream gets the first slot so it enters the event loop first
  const preferred = preferredLabel
    ? rawStreams.find((s) => s.sourceLabel === preferredLabel)
    : null;
  const rest = preferred ? rawStreams.filter((s) => s !== preferred) : rawStreams;

  await Promise.all([
    ...(preferred ? [processOne(preferred)] : []),
    ...rest.map((s) => processOne(s)),
  ]);
}

export async function handleStreamProxy(request, response) {
  const target = request.query.target;
  if (typeof target !== "string") {
    response.status(400).json({ error: "Invalid target URL." });
    return;
  }

  let upstream;
  try {
    ({ response: upstream } = await safeFetch(target, {
      headers: {
        "user-agent": UA,
        referer: new URL(target).origin + "/",
        range: request.get("range") || "",
      },
      signal: AbortSignal.timeout(STREAM_PROXY_TIMEOUT_MS),
    }));
  } catch (error) {
    if (error instanceof BlockedTargetError) {
      response.status(400).json({ error: error.message });
      return;
    }
    throw error;
  }

  if (!upstream.ok && upstream.status !== 206) {
    response.status(upstream.status).send(await upstream.text());
    return;
  }

  const contentType = upstream.headers.get("content-type") || "";
  copyHeaders(upstream.headers, response);
  response.status(upstream.status);
  response.setHeader("access-control-allow-origin", "*");
  // Private, not public: rewritePlaylist writes this user's access token into
  // segment URLs, and a shared cache in front of a public domain would keep the
  // document — token and all — for the next person through.
  response.setHeader("cache-control", "private, max-age=60");

  if (contentType.includes("mpegurl") || target.toLowerCase().includes(".m3u8")) {
    const content = await upstream.text();
    response.type("application/vnd.apple.mpegurl");
    response.send(rewritePlaylist(request, target, content));
    return;
  }

  if (!upstream.body) {
    response.end();
    return;
  }

  const reader = upstream.body.getReader();
  const stream = new ReadableStream({
    async pull(controller) {
      const { done, value } = await reader.read();
      if (done) {
        controller.close();
        return;
      }
      controller.enqueue(value);
    },
  });
  const webStream = stream;
  const nodeStream = await import("node:stream");
  nodeStream.Readable.fromWeb(webStream).pipe(response);
}

function resolveAgainst(baseUrl, uri) {
  try {
    return new URL(uri, baseUrl).toString();
  } catch {
    return uri;
  }
}

function buildManifestUrl(targetUrl) {
  return `/api/manifest?target=${encodeURIComponent(targetUrl)}`;
}

/**
 * Point every segment, key and init map at the CDN directly. The client fetches
 * media without touching this server, which is the whole reason to clean the
 * manifest here rather than proxy the video.
 *
 * #EXT-X-MAP is rewritten too, unlike in the proxy path, where an fMP4 source
 * would leak a direct init-segment fetch.
 */
function absolutizeMediaPlaylist(sourceUrl, content) {
  return content
    .split("\n")
    .map((line) => {
      const trimmed = line.trim();
      if (!trimmed) return line;
      if (trimmed.startsWith("#")) {
        if (trimmed.startsWith("#EXT-X-KEY") || trimmed.startsWith("#EXT-X-MAP")) {
          return line.replace(/URI="([^"]+)"/, (_, uri) => `URI="${resolveAgainst(sourceUrl, uri)}"`);
        }
        return line;
      }
      return resolveAgainst(sourceUrl, trimmed);
    })
    .join("\n");
}

/**
 * A master playlist has no segments to filter, so it is only redirected: each
 * variant and rendition comes back through this endpoint so that whichever one
 * the player picks arrives cleaned.
 */
function rewriteMasterPlaylist(sourceUrl, content) {
  return content
    .split("\n")
    .map((line) => {
      const trimmed = line.trim();
      if (!trimmed) return line;
      if (trimmed.startsWith("#")) {
        if (trimmed.startsWith("#EXT-X-MEDIA") || trimmed.startsWith("#EXT-X-I-FRAME-STREAM-INF")) {
          return line.replace(/URI="([^"]+)"/, (_, uri) => `URI="${buildManifestUrl(resolveAgainst(sourceUrl, uri))}"`);
        }
        return line;
      }
      // A bare URI line in a master playlist is a variant playlist.
      return buildManifestUrl(resolveAgainst(sourceUrl, trimmed));
    })
    .join("\n");
}

/**
 * Serve a playlist with spliced ad runs removed and every media URL absolute.
 *
 * AVPlayer and ExoPlayer cannot run the browser's hls.js playlist loader, so
 * without this a native client plays the ads the web player hides. Only the
 * manifest passes through here; segments stream device-to-CDN.
 *
 * The body deliberately carries no access token — unlike /api/stream, which has
 * to embed one because hls.js cannot put a header on a segment request. Here
 * the segment URLs are the CDN's own and need no credential of ours.
 */
/**
 * Fetches a playlist and cleans it, or returns the cached result.
 *
 * @throws {BlockedTargetError|Object} an error carrying `statusCode`.
 */
async function cleanedManifestFor(target) {
  const cached = caches.cleanedManifest.get(target);
  if (cached) return cached;

  let upstream;
  let landedOn;
  try {
    ({ response: upstream, finalUrl: landedOn } = await safeFetch(target, {
      headers: {
        "user-agent": UA,
        referer: new URL(target).origin + "/",
      },
      signal: AbortSignal.timeout(STREAM_PROXY_TIMEOUT_MS),
    }));
  } catch (error) {
    if (error instanceof BlockedTargetError) throw error;
    const failure = new Error("Upstream playlist unreachable.");
    failure.statusCode = 502;
    throw failure;
  }

  if (!upstream.ok) {
    const failure = new Error(`Upstream responded ${upstream.status}.`);
    failure.statusCode = upstream.status;
    throw failure;
  }

  const text = await upstream.text();
  if (!isValidM3u8Content(text)) {
    const failure = new Error("Target is not an HLS playlist.");
    failure.statusCode = 415;
    throw failure;
  }

  // Relative URIs resolve against wherever the redirect chain landed, not
  // against the URL we asked for. Redirects are followed by hand so each hop can
  // be checked, so this comes back from safeFetch rather than from response.url.
  const baseUrl = landedOn || target;
  const isMaster = !text.includes("#EXTINF");

  const result = isMaster
    ? {
        isMaster: true,
        body: rewriteMasterPlaylist(baseUrl, text),
        cuts: [],
        removedSeconds: 0,
        reason: null,
        // Kept because the ads live in the variants, not here, and the rewritten
        // body no longer contains their real addresses.
        variants: extractVariantPlaylistUrls(baseUrl, text),
      }
    : (() => {
        const stripped = stripAds(text, baseUrl);
        return {
          isMaster: false,
          body: absolutizeMediaPlaylist(baseUrl, stripped.text),
          cuts: stripped.cuts,
          removedSeconds: stripped.removedSeconds,
          reason: stripped.reason,
        };
      })();

  caches.cleanedManifest.set(target, result);
  return result;
}

export async function handleCleanManifest(request, response) {
  const target = request.query.target;
  if (typeof target !== "string") {
    response.status(400).json({ error: "Invalid target URL." });
    return;
  }

  let result;
  try {
    result = await cleanedManifestFor(target);
  } catch (error) {
    response.status(error.statusCode || 502).json({ error: error.message });
    return;
  }

  response.type("application/vnd.apple.mpegurl");
  // No credentials in the body, but the route is per-user gated, so keep it out
  // of shared caches all the same.
  response.setHeader("cache-control", "private, max-age=30");
  if (!result.isMaster) {
    response.setHeader("x-adfilter-removed-seconds", String(result.removedSeconds));
    if (result.reason) response.setHeader("x-adfilter-reason", result.reason);
  }
  response.send(result.body);
}

/**
 * Where the ads were, so a player can mark them on its scrub bar.
 *
 * The positions cannot come off the manifest response, because the player
 * fetches that itself and the app never sees it. They also cannot come from the
 * source list, which measures a 256 KB prefix rather than the whole playlist.
 * Both this and the manifest read the same cache entry, so asking for cuts
 * before playback costs no extra fetch.
 *
 * `at` is a position in the *cleaned* timeline, which is the one the player is
 * showing.
 */
export async function handleAdCuts(request, response) {
  const target = request.query.target;
  if (typeof target !== "string") {
    response.status(400).json({ error: "Invalid target URL." });
    return;
  }

  let result;
  try {
    result = await cleanedManifestFor(target);

    // A master playlist has no segments of its own. The ads are inside whichever
    // variant the player picks, so the variants are inspected and the one
    // carrying the most advertising answers — the same choice the source list
    // makes when it reports a runtime.
    if (result.isMaster) {
      const inspected = await Promise.all(
        (result.variants || []).slice(0, 5).map((url) =>
          cleanedManifestFor(url).catch(() => null),
        ),
      );
      result = inspected
        .filter((variant) => variant && !variant.isMaster)
        .reduce((best, variant) => (variant.removedSeconds > (best?.removedSeconds ?? -1) ? variant : best), null)
        ?? result;
    }
  } catch (error) {
    response.status(error.statusCode || 502).json({ error: error.message });
    return;
  }

  response.setHeader("cache-control", "private, max-age=30");
  response.json({
    removedSeconds: result.removedSeconds,
    reason: result.reason,
    cuts: result.cuts,
  });
}

export async function handlePosterProxy(request, response) {
  const target = request.query.target;
  if (typeof target !== "string") {
    response.status(400).json({ error: "Invalid target URL." });
    return;
  }

  let upstream;
  try {
    ({ response: upstream } = await safeFetch(target, {
      headers: {
        "user-agent": UA,
        referer: new URL(target).origin + "/",
      },
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    }));
  } catch (error) {
    if (error instanceof BlockedTargetError) {
      response.status(400).json({ error: error.message });
      return;
    }
    throw error;
  }

  if (!upstream.ok) {
    response.status(upstream.status).send(await upstream.text());
    return;
  }

  const contentType = upstream.headers.get("content-type") || "application/octet-stream";
  copyHeaders(upstream.headers, response);
  response.status(upstream.status);
  response.setHeader("access-control-allow-origin", "*");
  response.setHeader("cache-control", "public, max-age=600");
  response.type(POSTER_CONTENT_TYPES.find((type) => contentType.includes(type)) || contentType);

  if (!upstream.body) {
    response.end();
    return;
  }

  const nodeStream = await import("node:stream");
  nodeStream.Readable.fromWeb(upstream.body).pipe(response);
}
