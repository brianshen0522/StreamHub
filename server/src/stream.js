import { caches } from "./cache.js";
import { REQUEST_TIMEOUT_MS, STREAM_PROXY_TIMEOUT_MS } from "./config.js";

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

function buildProxyUrl(request, targetUrl) {
  return `/api/stream?target=${encodeURIComponent(targetUrl)}`;
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

  const result = stream.url.toLowerCase().includes(".m3u8")
    ? await checkM3u8(stream.url)
    : await checkGeneric(stream.url);

  caches.streamCheck.set(stream.url, result);
  return { ...stream, ...result };
}

export async function handleStreamProxy(request, response) {
  const target = request.query.target;
  if (typeof target !== "string" || !target.startsWith("http")) {
    response.status(400).json({ error: "Invalid target URL." });
    return;
  }

  const upstream = await fetch(target, {
    redirect: "follow",
    headers: {
      "user-agent": UA,
      referer: new URL(target).origin + "/",
      range: request.get("range") || "",
    },
    signal: AbortSignal.timeout(STREAM_PROXY_TIMEOUT_MS),
  });

  if (!upstream.ok && upstream.status !== 206) {
    response.status(upstream.status).send(await upstream.text());
    return;
  }

  const contentType = upstream.headers.get("content-type") || "";
  copyHeaders(upstream.headers, response);
  response.status(upstream.status);
  response.setHeader("access-control-allow-origin", "*");
  response.setHeader("cache-control", "public, max-age=60");

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

export async function handlePosterProxy(request, response) {
  const target = request.query.target;
  if (typeof target !== "string" || !target.startsWith("http")) {
    response.status(400).json({ error: "Invalid target URL." });
    return;
  }

  const upstream = await fetch(target, {
    redirect: "follow",
    headers: {
      "user-agent": UA,
      referer: new URL(target).origin + "/",
    },
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
  });

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
