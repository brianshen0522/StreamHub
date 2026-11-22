import { Readable, pipeline } from "node:stream";
import { once } from "node:events";
import { getBearerToken } from "./auth.js";
import { caches } from "./cache.js";
import { REQUEST_TIMEOUT_MS, STREAM_PROXY_TIMEOUT_MS } from "./config.js";
import { analyzePlaylist, stripAds } from "./utils/adfilter.js";
import { BlockedTargetError, assertPublicUrl, readLimited, safeFetch } from "./utils/safe-fetch.js";
import { isVidsrcRefererOrigin } from "./providers/vidsrc.js";
import { lookupUpstreamReferer, registerUpstreamReferer } from "./upstream-referers.js";

/**
 * What a proxied response may carry over from upstream. An allowlist because
 * these responses are served from the app's own origin: anything else a remote
 * host sets, cookies and security policy included, would apply to it.
 */
const FORWARDED_HEADERS = ["accept-ranges", "content-range", "etag", "last-modified"];

const POSTER_CONTENT_TYPES = ["image/jpeg", "image/png", "image/webp", "image/avif", "image/gif"];

const SAFE_MEDIA_TYPE =
  /^(?:video\/|audio\/|image\/(?!svg)|application\/(?:octet-stream|mp4|vnd\.apple\.mpegurl|x-mpegurl)\b|binary\/octet-stream\b)/i;

/** A playlist is text and small. Past this it is not a playlist worth holding in memory. */
const MAX_PLAYLIST_BYTES = 8 * 1024 * 1024;
const MAX_UPSTREAM_ERROR_BYTES = 64 * 1024;

/**
 * Proxied bytes are chosen by whoever runs the upstream host and arrive on the
 * same origin as the app and its stored session. They are media, never a
 * document: the sandbox stops one from running if it is opened directly.
 */
function applyProxyHeaders(upstreamHeaders, response) {
  for (const name of FORWARDED_HEADERS) {
    const value = upstreamHeaders.get(name);
    if (value !== null) response.setHeader(name, value);
  }
  response.setHeader("x-content-type-options", "nosniff");
  response.setHeader("content-security-policy", "sandbox; default-src 'none'");
}

function safeMediaType(contentType) {
  return SAFE_MEDIA_TYPE.test(contentType) ? contentType : "application/octet-stream";
}

async function sendUpstreamError(upstream, response) {
  const text = await readLimited(upstream.body, MAX_UPSTREAM_ERROR_BYTES);
  applyProxyHeaders(new Headers(), response);
  response.status(upstream.status).type("text/plain").send(text);
}

/**
 * pipeline rather than pipe: a stream that errors with no listener takes the
 * whole process down, and an upstream connection can drop at any moment. It also
 * releases the upstream fetch when the client goes away.
 */
/**
 * Peek at the first bytes of a body without decoding them: an m3u8 is text,
 * but the same road also carries MPEG-TS that the CDN labels text/html. Only
 * a byte-level look can tell them apart.
 */
async function readPrefix(upstream, sniffBytes) {
  const reader = upstream.body.getReader();
  const chunks = [];
  let total = 0;
  while (total < sniffBytes) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value);
    total += value.byteLength;
  }
  return { reader, chunks, total };
}

/** Relays whatever the reader has left, byte for byte, then ends the response. */
async function relayReader(reader, response) {
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      if (!response.write(Buffer.from(value))) await once(response, "drain");
    }
  } catch { /* the client went away; the response is over either way */ }
  response.end();
}

function relayBody(upstream, response) {
  const source = Readable.fromWeb(upstream.body);
  // pipeline listens for errors only until it settles. The fetch's
  // AbortSignal.timeout can still fire after that — the client already gone,
  // fromWeb's destroy waiting on the body's cancel() — and Readable.fromWeb
  // then emits the TimeoutError with nobody listening, which took the whole
  // server down (four restarts in an afternoon of poster bursts). By then
  // the request is over; there is nothing left to do with the error.
  source.on("error", () => {});
  pipeline(source, response, () => {});
}

/**
 * Segment, key and variant URLs inside a rewritten playlist point back at this
 * proxy, and hls.js fetches them as plain media requests that cannot carry an
 * Authorization header. The token has to ride along in the query string the
 * same way it does on the playlist request that got us here — without it every
 * segment answers 401 and proxy playback stalls on a black screen.
 */
function buildProxyUrl(request, targetUrl, referer = null) {
  const url = `/api/stream?target=${encodeURIComponent(targetUrl)}`;
  const token = getBearerToken(request, { allowQuery: true });
  const params = token ? `&accessToken=${encodeURIComponent(token)}` : "";
  // On the referer road every nested playlist and segment needs the same
  // upstream Referer or it 403s. It is written from the referer this request
  // resolved — not copied from the incoming query — because a native client
  // reaches the road through the host registry and sent no `r` at all.
  return `${url}${params}${referer ? `&r=${encodeRefererParam(referer)}` : ""}`;
}

function encodeRefererParam(referer) {
  return encodeURIComponent(Buffer.from(referer).toString("base64url"));
}

/**
 * The `r` query parameter: base64url of a player-page URL, honoured only for
 * hosts on the VidSrc allowlist so no caller can turn the proxy into a
 * referer-forger for an arbitrary site.
 *
 * @returns {{ referer: string|null } | { error: string }}
 */
function decodeRefererParam(request) {
  const raw = typeof request.query.r === "string" ? request.query.r : "";
  if (!raw) return { referer: null };
  let decoded;
  try {
    decoded = Buffer.from(raw, "base64url").toString("utf8");
  } catch {
    return { error: "Invalid referer parameter." };
  }
  if (!isVidsrcRefererOrigin(decoded)) return { error: "Referer not allowed for this target." };
  return { referer: decoded };
}

/**
 * The Referer a media request must go upstream with, when the target is on
 * the referer road: the request's own (validated) `r`, else whatever the
 * provider registered for the target's host. Null means the ordinary road.
 */
function resolveRoadReferer(request, target) {
  const fromParam = decodeRefererParam(request);
  if (fromParam.error) return fromParam;
  return { referer: fromParam.referer ?? lookupUpstreamReferer(target) };
}

function parseAbsoluteUrl(value) {
  try {
    const url = new URL(value);
    return url.protocol === "http:" || url.protocol === "https:" ? url : null;
  } catch {
    return null;
  }
}

/**
 * VidSrc's playback token rides on the master's query string and its CDN
 * 403s any nested request without it, so on the referer road a nested URI
 * inherits it — but only a URI on the same host as the URL that carried it.
 * A token is a credential for one CDN; copying it to every host a playlist
 * names would hand it to each of them.
 */
function inheritToken(tokenSourceUrl, resolved) {
  let source;
  try {
    source = new URL(tokenSourceUrl);
  } catch {
    return resolved;
  }
  const token = source.searchParams.get("token");
  if (token && resolved.hostname === source.hostname && !resolved.searchParams.get("token")) {
    resolved.searchParams.set("token", token);
  }
  return resolved;
}

/** Resolves a playlist URI to absolute; on the referer road it also inherits the token. */
function resolveNested(sourceUrl, uri, onRoad) {
  try {
    const resolved = new URL(uri, sourceUrl);
    return (onRoad ? inheritToken(sourceUrl, resolved) : resolved).toString();
  } catch {
    return null;
  }
}

function rewritePlaylist(request, sourceUrl, content, referer = null) {
  const onRoad = Boolean(referer);
  const absolute = (uri) => resolveNested(sourceUrl, uri, onRoad);

  // Keys, init segments and media renditions carry their URL in a URI="…"
  // attribute rather than on a line of its own.
  const rewriteUriAttributes = (line) => line.replace(/URI="([^"]+)"/g, (match, uri) => {
    const target = absolute(uri);
    return target ? `URI="${buildProxyUrl(request, target, referer)}"` : match;
  });

  return content
    .split("\n")
    .map((line) => {
      const trimmed = line.trim();
      if (!trimmed) return line;
      if (trimmed.startsWith("#")) {
        return /^#EXT-X-(?:KEY|MAP|MEDIA|SESSION-DATA|I-FRAME-STREAM-INF)/.test(trimmed)
          ? rewriteUriAttributes(line)
          : line;
      }
      const target = absolute(trimmed);
      return target ? buildProxyUrl(request, target, referer) : line;
    })
    .join("\n");
}

const UA =
  "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

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

/**
 * Whether a stream URL should be treated as an HLS playlist. VidSrc's masters
 * live at extension-less paths (/pl/<hash>), so the extension test alone
 * misses them; a referer-bearing stream that is not a media file is a playlist.
 */
function looksLikePlaylist(stream) {
  return stream.url.toLowerCase().includes(".m3u8")
    || (Boolean(stream.referer) && !/\.(?:ts|mp4|m4s|m4v|jpg|png|webp)(?:\?|$)/i.test(stream.url));
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

function extractVariantPlaylistUrls(sourceUrl, text, onRoad = false) {
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
    const resolved = resolveNested(sourceUrl, nextLine, onRoad);
    if (resolved) urls.push(resolved);
  }
  return urls;
}

async function fetchTextSnippet(url, maxBytes = 256_000, referer = null) {
  // Variant URLs come out of the playlist body, so every one of these is
  // checked here rather than by the caller.
  const { response } = await safeFetch(url, {
    headers: {
      "user-agent": UA,
      ...(referer ? { referer } : {}),
      range: `bytes=0-${Math.max(1024, maxBytes - 1)}`,
    },
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
  });
  if (response.status !== 200 && response.status !== 206) {
    throw new Error(`Upstream responded ${response.status}`);
  }
  return readLimited(response.body, maxBytes);
}

/**
 * VidSrc variant playlists run to ~1.1 MB, so the usual 256 KB prefix would
 * count a quarter of the film. Referer-road probes read further rather than
 * report a runtime that is wrong.
 */
const METADATA_PREFIX_BYTES = 256_000;
const ROAD_METADATA_PREFIX_BYTES = 2 * 1024 * 1024;

async function inspectM3u8Metadata(url, depth = 0, referer = null) {
  const text = await fetchTextSnippet(
    url,
    referer ? ROAD_METADATA_PREFIX_BYTES : METADATA_PREFIX_BYTES,
    referer,
  );
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

  const variantUrls = extractVariantPlaylistUrls(url, text, Boolean(referer));
  if (variantUrls.length === 0) {
    return { durationSeconds: null, adSeconds: 0, playlistType: "master" };
  }

  const inspectedVariants = await Promise.all(
    variantUrls.slice(0, 5).map(async (variantUrl) => {
      try {
        return await inspectM3u8Metadata(variantUrl, depth + 1, referer);
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
  if (looksLikePlaylist(stream)) {
    try {
      await assertPublicUrl(stream.url);
      const inspected = await inspectM3u8Metadata(stream.url, 0, stream.referer ?? null);
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

async function checkM3u8(url, referer = null) {
  let response;
  try {
    ({ response } = await safeFetch(url, {
      headers: {
        "user-agent": UA,
        ...(referer ? { referer } : {}),
        range: "bytes=0-4095",
      },
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    }));
    if (response.status !== 200 && response.status !== 206) {
      return { ok: false, statusCode: response.status };
    }
    const text = await readLimited(response.body, 4096);
    return { ok: isValidM3u8Content(text), statusCode: response.status };
  } catch {
    return { ok: false, statusCode: null };
  }
}

async function checkGeneric(url, referer = null) {
  let response;
  try {
    ({ response } = await safeFetch(url, {
      method: "HEAD",
      headers: {
        "user-agent": UA,
        ...(referer ? { referer } : {}),
      },
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    }));
    if ([403, 405, 500, 501].includes(response.status)) {
      ({ response } = await safeFetch(url, {
        method: "GET",
        headers: {
          range: "bytes=0-0",
          "user-agent": UA,
          ...(referer ? { referer } : {}),
        },
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      }));
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

  // An extension-less VidSrc master sent to checkGeneric drew a 403: its HEAD
  // is not a playlist fetch. See looksLikePlaylist.
  const result = looksLikePlaylist(stream)
    ? await checkM3u8(stream.url, stream.referer ?? null)
    : await checkGeneric(stream.url, stream.referer ?? null);

  caches.streamCheck.set(stream.url, result);
  return { ...stream, ...result };
}

export async function streamCheckedSources(rawStreams, preferredLabel, onSource) {
  if (rawStreams.length === 0) return;

  async function processOne(stream) {
    const roadReferer = stream.referer && isVidsrcRefererOrigin(stream.referer) ? stream.referer : null;
    // The provider registers its CDN hosts too; doing it here as well means a
    // native client handed the bare directUrl reaches the referer road even
    // if a provider forgot to.
    if (roadReferer) registerUpstreamReferer(stream.url, roadReferer);
    const enriched = await getStreamMetadata(stream);
    const checked = await checkStream(enriched);
    if (checked.ok) {
      // directUrl is always the CDN's own absolute URL (for VidSrc it already
      // carries ?token=): native clients wrap it in /api/manifest or
      // /api/stream, which find the referer through the host registry.
      // proxyUrl carries the referer explicitly as `r`, allowlist-checked by
      // the proxy, so the proxied road works without the caller knowing.
      const proxyUrl = `/api/stream?target=${encodeURIComponent(checked.url)}`
        + (roadReferer ? `&r=${encodeRefererParam(roadReferer)}` : "");
      // VidSrc streams cannot play from the browser directly — hls.js can send
      // neither the Referer nor the token cross-origin — so preferProxy tells
      // the client to start on proxyUrl. maxQuality pins the top rung, stepping
      // down only when that rung will not play.
      const preferProxy = Boolean(stream.preferProxy);
      onSource({
        ...checked,
        directUrl: checked.url,
        proxyUrl,
        ...(preferProxy ? { preferProxy: true, maxQuality: true } : {}),
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

  const targetUrl = parseAbsoluteUrl(target);
  if (!targetUrl) {
    response.status(400).json({ error: "Invalid target URL." });
    return;
  }

  // VidSrc's CDN 403s anything whose Referer is not the player page the chain
  // handed out: that is the `r` param (allowlisted) or the host registry.
  const road = resolveRoadReferer(request, target);
  if (road.error) {
    response.status(400).json({ error: road.error });
    return;
  }
  const upstreamReferer = road.referer;

  let upstream;
  try {
    ({ response: upstream } = await safeFetch(target, {
      headers: {
        "user-agent": UA,
        referer: upstreamReferer ?? targetUrl.origin + "/",
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
    await sendUpstreamError(upstream, response);
    return;
  }

  const contentType = upstream.headers.get("content-type") || "";
  applyProxyHeaders(upstream.headers, response);
  response.status(upstream.status);
  response.setHeader("access-control-allow-origin", "*");
  // Private, not public: rewritePlaylist writes this user's access token into
  // segment URLs, and a shared cache in front of a public domain would keep the
  // document — token and all — for the next person through.
  response.setHeader("cache-control", "private, max-age=60");

  if (contentType.includes("mpegurl") || target.toLowerCase().includes(".m3u8")) {
    const content = await readLimited(upstream.body, MAX_PLAYLIST_BYTES);
    response.type("application/vnd.apple.mpegurl");
    response.send(rewritePlaylist(request, target, content, upstreamReferer));
    return;
  }

  // VidSrc serves its playlists at extension-less paths under text/plain, so
  // the test above misses them — and it serves MPEG-TS the same way, as
  // text/html. On the referer road the first bytes are read before deciding:
  // an HLS signature means text to rewrite, anything else is a segment and is
  // relayed as the raw bytes it is (decoding a segment as text destroys it).
  if (upstreamReferer && contentType.startsWith("text/")) {
    const { reader, chunks, total } = await readPrefix(upstream, 16);
    const prefix = Buffer.concat(chunks.map((chunk) => Buffer.from(chunk))).subarray(0, 7).toString("latin1");
    if (prefix !== "#EXTM3U") {
      response.setHeader("content-type", "application/octet-stream");
      if (chunks.length) response.write(Buffer.concat(chunks.map((chunk) => Buffer.from(chunk))));
      await relayReader(reader, response);
      return;
    }
    // A playlist: the rest of it is text, up to the same cap as any other.
    const rest = [];
    let size = total;
    while (size < MAX_PLAYLIST_BYTES) {
      const { done, value } = await reader.read();
      if (done) break;
      rest.push(Buffer.from(value));
      size += value.byteLength;
    }
    await reader.cancel().catch(() => {});
    const content = Buffer.concat([...chunks.map((chunk) => Buffer.from(chunk)), ...rest]).toString("utf8");
    response.type("application/vnd.apple.mpegurl");
    response.send(rewritePlaylist(request, target, content, upstreamReferer));
    return;
  }

  response.setHeader("content-type", safeMediaType(contentType));

  if (!upstream.body) {
    response.end();
    return;
  }

  relayBody(upstream, response);
}

function resolveAgainst(baseUrl, uri) {
  try {
    return new URL(uri, baseUrl).toString();
  } catch {
    return uri;
  }
}

/**
 * Every URL is written without the caller's access token: the cleaned body is
 * cached and the token is added per request in handleCleanManifest.
 */
function buildManifestUrl(targetUrl, referer = null) {
  const url = `/api/manifest?target=${encodeURIComponent(targetUrl)}`;
  return referer ? `${url}&r=${encodeRefererParam(referer)}` : url;
}

function buildRoadSegmentUrl(targetUrl, referer) {
  return `/api/stream?target=${encodeURIComponent(targetUrl)}&r=${encodeRefererParam(referer)}`;
}

/**
 * Point every segment, key and init map at the CDN directly. The client fetches
 * media without touching this server, which is the whole reason to clean the
 * manifest here rather than proxy the video.
 *
 * #EXT-X-MAP is rewritten too, unlike in the proxy path, where an fMP4 source
 * would leak a direct init-segment fetch.
 *
 * The referer road is the exception: that CDN refuses any segment request
 * without the player-page Referer, which a device cannot send, so there each
 * media URI goes through /api/stream with `r` instead, inheriting the
 * playlist's token for same-host URIs.
 */
function absolutizeMediaPlaylist(sourceUrl, content, referer = null, tokenSourceUrl = sourceUrl) {
  const resolve = referer
    ? (uri) => {
        try {
          const resolved = inheritToken(tokenSourceUrl, new URL(uri, sourceUrl));
          return buildRoadSegmentUrl(resolved.toString(), referer);
        } catch {
          return uri;
        }
      }
    : (uri) => resolveAgainst(sourceUrl, uri);
  return content
    .split("\n")
    .map((line) => {
      const trimmed = line.trim();
      if (!trimmed) return line;
      if (trimmed.startsWith("#")) {
        if (trimmed.startsWith("#EXT-X-KEY") || trimmed.startsWith("#EXT-X-MAP")) {
          return line.replace(/URI="([^"]+)"/, (_, uri) => `URI="${resolve(uri)}"`);
        }
        return line;
      }
      return resolve(trimmed);
    })
    .join("\n");
}

/**
 * A master playlist has no segments to filter, so it is only redirected: each
 * variant and rendition comes back through this endpoint so that whichever one
 * the player picks arrives cleaned. On the referer road the variant URLs keep
 * the master's token (same host only) and carry `r`, so the variant fetch goes
 * upstream with the referer too.
 */
function rewriteMasterPlaylist(sourceUrl, content, referer = null, tokenSourceUrl = sourceUrl) {
  const resolve = referer
    ? (uri) => {
        try {
          return buildManifestUrl(inheritToken(tokenSourceUrl, new URL(uri, sourceUrl)).toString(), referer);
        } catch {
          return uri;
        }
      }
    : (uri) => buildManifestUrl(resolveAgainst(sourceUrl, uri));
  return content
    .split("\n")
    .map((line) => {
      const trimmed = line.trim();
      if (!trimmed) return line;
      if (trimmed.startsWith("#")) {
        if (trimmed.startsWith("#EXT-X-MEDIA") || trimmed.startsWith("#EXT-X-I-FRAME-STREAM-INF")) {
          return line.replace(/URI="([^"]+)"/, (_, uri) => `URI="${resolve(uri)}"`);
        }
        return line;
      }
      // A bare URI line in a master playlist is a variant playlist.
      return resolve(trimmed);
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
 * the segment URLs are the CDN's own and need no credential of ours. (Except on
 * the referer road, where segments must come through /api/stream; see
 * handleCleanManifest.)
 */
/**
 * Fetches a playlist and cleans it, or returns the cached result.
 *
 * @param {string|null} referer the referer-road Referer, or null
 * @throws {BlockedTargetError|Object} an error carrying `statusCode`.
 */
async function cleanedManifestFor(target, referer = null) {
  const cacheKey = referer ? `${target}\u0000${referer}` : target;
  const cached = caches.cleanedManifest.get(cacheKey);
  if (cached) return cached;

  const targetUrl = parseAbsoluteUrl(target);
  if (!targetUrl) {
    const failure = new Error("Invalid target URL.");
    failure.statusCode = 400;
    throw failure;
  }

  let upstream;
  let landedOn;
  try {
    ({ response: upstream, finalUrl: landedOn } = await safeFetch(target, {
      headers: {
        "user-agent": UA,
        referer: referer ?? targetUrl.origin + "/",
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

  const text = await readLimited(upstream.body, MAX_PLAYLIST_BYTES);
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
        body: rewriteMasterPlaylist(baseUrl, text, referer, target),
        cuts: [],
        removedSeconds: 0,
        reason: null,
        // Kept because the ads live in the variants, not here, and the rewritten
        // body no longer contains their real addresses.
        variants: extractVariantPlaylistUrls(baseUrl, text, Boolean(referer)).map((url) => (
          referer ? inheritToken(target, new URL(url)).toString() : url
        )),
      }
    : (() => {
        // Classify against the CDN's own URIs, before anything is pointed at
        // the proxy: rewriting first would erase the directory signal.
        const stripped = stripAds(text, baseUrl);
        return {
          isMaster: false,
          body: absolutizeMediaPlaylist(baseUrl, stripped.text, referer, target),
          cuts: stripped.cuts,
          removedSeconds: stripped.removedSeconds,
          reason: stripped.reason,
        };
      })();

  caches.cleanedManifest.set(cacheKey, result);
  return result;
}

export async function handleCleanManifest(request, response) {
  const target = request.query.target;
  if (typeof target !== "string") {
    response.status(400).json({ error: "Invalid target URL." });
    return;
  }

  const road = resolveRoadReferer(request, target);
  if (road.error) {
    response.status(400).json({ error: road.error });
    return;
  }

  let result;
  try {
    result = await cleanedManifestFor(target, road.referer);
  } catch (error) {
    response.status(error.statusCode || 502).json({ error: error.message });
    return;
  }

  response.type("application/vnd.apple.mpegurl");
  response.setHeader("x-content-type-options", "nosniff");
  // No credentials in the body, but the route is per-user gated, so keep it out
  // of shared caches all the same.
  response.setHeader("cache-control", "private, max-age=30");
  if (!result.isMaster) {
    response.setHeader("x-adfilter-removed-seconds", String(result.removedSeconds));
    if (result.reason) response.setHeader("x-adfilter-reason", result.reason);
  }

  // A master playlist's variants point back at this endpoint, and a media
  // element fetches them without headers — so a caller that authenticated by
  // query token gets that token echoed onto the variant URLs. Applied here,
  // per request, never into the cache: the cleaned body is cached by target
  // and shared across the account's users, and a token baked in there would
  // hand one user's credential to the next. Segment URLs are untouched —
  // they are the CDN's own and carry nothing of ours.
  //
  // On the referer road the segments are /api/stream URLs of ours and need a
  // credential as much as the variants do, and a player that sent the
  // manifest request with an Authorization header may not send one for each
  // segment — so there the token is taken from either place and written onto
  // every URI this server wrote.
  let body = result.body;
  if (road.referer) {
    const token = getBearerToken(request, { allowQuery: true });
    if (token) {
      body = body.replace(
        /(^|URI=")(\/api\/(?:manifest|stream)\?target=[^"\s,]+)/gm,
        (_, prefix, uri) => `${prefix}${uri}&accessToken=${encodeURIComponent(token)}`,
      );
    }
  } else {
    const queryToken = String(request.query.accessToken || "").trim();
    if (queryToken && result.isMaster) {
      // Only where this server wrote the URI: at the start of a line or of a URI
      // attribute. A tag it leaves alone may name another host entirely.
      body = body.replace(
        /(^|URI=")(\/api\/manifest\?target=[^"\s,]+)/gm,
        (_, prefix, uri) => `${prefix}${uri}&accessToken=${encodeURIComponent(queryToken)}`,
      );
    }
  }
  response.send(body);
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

  const road = resolveRoadReferer(request, target);
  if (road.error) {
    response.status(400).json({ error: road.error });
    return;
  }

  let result;
  try {
    result = await cleanedManifestFor(target, road.referer);

    // A master playlist has no segments of its own. The ads are inside whichever
    // variant the player picks, so the variants are inspected and the one
    // carrying the most advertising answers — the same choice the source list
    // makes when it reports a runtime.
    if (result.isMaster) {
      const inspected = await Promise.all(
        (result.variants || []).slice(0, 5).map((url) =>
          cleanedManifestFor(url, road.referer).catch(() => null),
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
    await sendUpstreamError(upstream, response);
    return;
  }

  const contentType = upstream.headers.get("content-type") || "application/octet-stream";
  applyProxyHeaders(upstream.headers, response);
  response.status(upstream.status);
  response.setHeader("access-control-allow-origin", "*");
  response.setHeader("cache-control", "public, max-age=600");
  response.setHeader(
    "content-type",
    POSTER_CONTENT_TYPES.find((type) => contentType.includes(type)) || safeMediaType(contentType),
  );

  if (!upstream.body) {
    response.end();
    return;
  }

  relayBody(upstream, response);
}
