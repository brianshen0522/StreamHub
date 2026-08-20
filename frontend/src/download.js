/**
 * Downloads an HLS stream to a single file with ad segments removed.
 *
 * The playlist is run through the same stripAds() filter the player uses, so
 * the saved file matches what was watched. Segments are concatenated as raw
 * MPEG-TS — no remux to MP4, which would require pulling in ffmpeg.wasm or
 * mux.js. The result plays in VLC / mpv / IINA.
 *
 * Where the browser supports the File System Access API the bytes stream
 * straight to disk at constant memory; otherwise they accumulate in a Blob,
 * which is only viable for shorter videos.
 */

import { stripAds } from "./adfilter.js";

function resolve(uri, base) {
  try {
    return new URL(uri, base).toString();
  } catch {
    return uri;
  }
}

function parseAttributes(line) {
  const attrs = {};
  const body = line.slice(line.indexOf(":") + 1);
  for (const match of body.matchAll(/([A-Z0-9-]+)=("[^"]*"|[^,]*)/g)) {
    attrs[match[1]] = match[2].replace(/^"|"$/g, "");
  }
  return attrs;
}

function hexToBytes(hex) {
  const clean = hex.replace(/^0x/i, "");
  const out = new Uint8Array(clean.length / 2);
  for (let i = 0; i < out.length; i += 1) out[i] = Number.parseInt(clean.substr(i * 2, 2), 16);
  return out;
}

/** HLS defaults the IV to the segment's media sequence number. */
function sequenceIv(sequence) {
  const iv = new Uint8Array(16);
  new DataView(iv.buffer).setUint32(12, sequence >>> 0, false);
  return iv;
}

function parseSegments(text, playlistUrl) {
  const segments = [];
  let sequence = 0;
  let key = null;

  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) continue;

    if (line.startsWith("#EXT-X-MEDIA-SEQUENCE")) {
      sequence = Number.parseInt(line.split(":")[1], 10) || 0;
      continue;
    }
    if (line.startsWith("#EXT-X-KEY")) {
      const attrs = parseAttributes(line);
      key = attrs.METHOD === "NONE" || !attrs.URI
        ? null
        : { method: attrs.METHOD, uri: resolve(attrs.URI, playlistUrl), iv: attrs.IV ? hexToBytes(attrs.IV) : null };
      continue;
    }
    if (line.startsWith("#")) continue;

    segments.push({ url: resolve(line, playlistUrl), key, sequence });
    sequence += 1;
  }
  return segments;
}

/** Resolves a master playlist down to the media playlist hls.js would pick. */
async function toMediaPlaylist(url, signal) {
  let currentUrl = url;
  for (let hop = 0; hop < 3; hop += 1) {
    const response = await fetch(currentUrl, { signal });
    if (!response.ok) throw new Error(`Playlist request failed (${response.status})`);
    const text = await response.text();
    if (text.includes("#EXTINF")) return { text, url: currentUrl };

    const variant = text.split(/\r?\n/).map((l) => l.trim()).find((l) => l && !l.startsWith("#"));
    if (!variant) throw new Error("No playable variant in playlist.");
    currentUrl = resolve(variant, currentUrl);
  }
  throw new Error("Too many playlist redirects.");
}

async function importKey(bytes) {
  return crypto.subtle.importKey("raw", bytes, { name: "AES-CBC" }, false, ["decrypt"]);
}

export function canStreamToDisk() {
  return typeof window !== "undefined" && typeof window.showSaveFilePicker === "function";
}

export function sanitizeFileName(name) {
  return String(name).replace(/[\\/:*?"<>|]+/g, "_").replace(/\s+/g, " ").trim().slice(0, 120) || "video";
}

/**
 * @param {object}   options
 * @param {string}   options.url          playlist URL (master or media)
 * @param {string}   options.fileName     suggested name, without extension
 * @param {Function} options.onProgress   ({done, total, bytes, phase}) => void
 * @param {AbortSignal} options.signal
 * @returns {Promise<{bytes: number, segments: number, removedSeconds: number, fileName: string}>}
 */
export async function downloadStream({ url, fileName, onProgress, signal }) {
  const report = (patch) => onProgress?.(patch);

  report({ phase: "playlist", done: 0, total: 0, bytes: 0 });
  const { text, url: mediaUrl } = await toMediaPlaylist(url, signal);

  const filtered = stripAds(text, mediaUrl);
  const segments = parseSegments(filtered.text, mediaUrl);
  if (!segments.length) throw new Error("Playlist contained no segments.");

  const safeName = `${sanitizeFileName(fileName)}.ts`;

  // Pick a sink before doing any work so a cancelled save picker costs nothing.
  let writer = null;
  let parts = null;
  if (canStreamToDisk()) {
    const handle = await window.showSaveFilePicker({
      suggestedName: safeName,
      types: [{ description: "MPEG-TS video", accept: { "video/mp2t": [".ts"] } }],
    });
    writer = await handle.createWritable();
  } else {
    parts = [];
  }

  const keyCache = new Map();
  let bytes = 0;

  try {
    for (let index = 0; index < segments.length; index += 1) {
      if (signal?.aborted) throw new DOMException("Aborted", "AbortError");
      const segment = segments[index];

      const response = await fetch(segment.url, { signal });
      if (!response.ok) throw new Error(`Segment ${index + 1} failed (${response.status})`);
      let data = new Uint8Array(await response.arrayBuffer());

      if (segment.key?.method === "AES-128") {
        let cryptoKey = keyCache.get(segment.key.uri);
        if (!cryptoKey) {
          const keyResponse = await fetch(segment.key.uri, { signal });
          if (!keyResponse.ok) throw new Error(`Key request failed (${keyResponse.status})`);
          cryptoKey = await importKey(new Uint8Array(await keyResponse.arrayBuffer()));
          keyCache.set(segment.key.uri, cryptoKey);
        }
        const iv = segment.key.iv || sequenceIv(segment.sequence);
        data = new Uint8Array(await crypto.subtle.decrypt({ name: "AES-CBC", iv }, cryptoKey, data));
      }

      if (writer) await writer.write(data);
      else parts.push(data);

      bytes += data.byteLength;
      report({ phase: "downloading", done: index + 1, total: segments.length, bytes });
    }

    if (writer) {
      await writer.close();
    } else {
      const blob = new Blob(parts, { type: "video/mp2t" });
      const href = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = href;
      anchor.download = safeName;
      anchor.click();
      setTimeout(() => URL.revokeObjectURL(href), 60_000);
    }
  } catch (error) {
    if (writer) { try { await writer.abort(); } catch { /* sink already gone */ } }
    throw error;
  }

  report({ phase: "done", done: segments.length, total: segments.length, bytes });
  return { bytes, segments: segments.length, removedSeconds: filtered.removedSeconds, fileName: safeName };
}
