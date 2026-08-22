/**
 * Downloads an HLS stream to a single file with ad segments removed — and
 * survives being stopped.
 *
 * Every decrypted segment is put into IndexedDB as it arrives, and a record
 * beside them says how far the download got. Cancelling, closing the tab, even
 * the browser crashing all leave the same thing behind: a record that resumes
 * from the next segment. Starting the same episode-and-source again continues
 * it — cancelling at 80% no longer costs the 80%.
 *
 * That is also why the file appears at the end rather than streaming to disk
 * as it downloads (which is what this file used to do, on browsers with the
 * File System Access API): a file handle cannot be reopened after a reload
 * without prompting, but IndexedDB can. The segments are assembled into one
 * Blob — disk-backed, not memory — and saved when the last one lands.
 *
 * The playlist is run through the same stripAds() filter the player uses, so
 * the saved file matches what was watched. Segments are concatenated as raw
 * MPEG-TS; the result plays in VLC / mpv / IINA.
 */

import { stripAds } from "./adfilter.js";

const DB_NAME = "streamhub-downloads";
const DB_VERSION = 1;

// ── IndexedDB plumbing ──────────────────────────────────────────────────────

let dbPromise = null;

function openDb() {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains("records")) {
        db.createObjectStore("records", { keyPath: "id" });
      }
      if (!db.objectStoreNames.contains("chunks")) {
        db.createObjectStore("chunks", { keyPath: ["id", "index"] });
      }
    };
    request.onsuccess = () => {
      prune(request.result).catch(() => {});
      resolve(request.result);
    };
    request.onerror = () => reject(request.error);
  });
  return dbPromise;
}

/**
 * Partials someone walked away from are dropped after two weeks. Without this
 * every abandoned download holds its megabytes forever; with it, anything
 * genuinely being resumed has long since been touched again.
 */
const STALE_AFTER_MS = 14 * 24 * 60 * 60 * 1000;

async function prune(db) {
  const records = await new Promise((resolve, reject) => {
    const request = db.transaction("records").objectStore("records").getAll();
    request.onsuccess = () => resolve(request.result ?? []);
    request.onerror = () => reject(request.error);
  });
  for (const record of records) {
    if (Date.now() - (record.createdAt || 0) > STALE_AFTER_MS) {
      await tx(db, "chunks", "readwrite", (store) => {
        for (let index = 0; index < record.segments.length; index += 1) store.delete([record.id, index]);
      });
      await tx(db, "records", "readwrite", (store) => store.delete(record.id));
    }
  }
}

function tx(db, store, mode, run) {
  return new Promise((resolve, reject) => {
    const transaction = db.transaction(store, mode);
    const result = run(transaction.objectStore(store));
    transaction.oncomplete = () => resolve(result?.result ?? result);
    transaction.onerror = () => reject(transaction.error);
    transaction.onabort = () => reject(transaction.error);
  });
}

async function readRecord(id) {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const request = db.transaction("records").objectStore("records").get(id);
    request.onsuccess = () => resolve(request.result ?? null);
    request.onerror = () => reject(request.error);
  });
}

async function writeRecord(record) {
  const db = await openDb();
  return tx(db, "records", "readwrite", (store) => store.put(record));
}

async function putChunk(id, index, blob) {
  const db = await openDb();
  return tx(db, "chunks", "readwrite", (store) => store.put({ id, index, blob }));
}

async function readChunks(id, count) {
  const db = await openDb();
  const store = db.transaction("chunks").objectStore("chunks");
  const parts = new Array(count);
  await Promise.all(
    Array.from({ length: count }, (_, index) => new Promise((resolve, reject) => {
      const request = store.get([id, index]);
      request.onsuccess = () => { parts[index] = request.result?.blob; resolve(); };
      request.onerror = () => reject(request.error);
    })),
  );
  if (parts.some((part) => !part)) throw new Error("A downloaded segment is missing from storage.");
  return parts;
}

async function clearDownload(id, segmentCount) {
  const db = await openDb();
  await tx(db, "chunks", "readwrite", (store) => {
    for (let index = 0; index < segmentCount; index += 1) store.delete([id, index]);
  });
  await tx(db, "records", "readwrite", (store) => store.delete(id));
}

// ── identity ────────────────────────────────────────────────────────────────

/**
 * One identity per episode-and-source, matching the phone app's rule: asking
 * for the same thing twice continues the first attempt instead of starting a
 * second one beside it.
 */
export async function downloadIdentity({ providerKey, itemUrl, seasonUrl, episodeLabel, sourceLabel }) {
  const seed = [providerKey, itemUrl, seasonUrl || "", episodeLabel || "", sourceLabel || ""].join("|");
  const digest = await crypto.subtle.digest("SHA-1", new TextEncoder().encode(seed));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("").slice(0, 24);
}

/** What is already on disk for this identity, so the button can say "resume at 82%". */
export async function partialDownload(id) {
  try {
    const record = await readRecord(id);
    if (!record || !record.segments?.length) return null;
    return {
      id,
      percent: Math.round((record.completedSegments / record.segments.length) * 100),
      bytes: record.bytes,
      finished: record.finished,
    };
  } catch {
    return null;
  }
}

export async function discardDownload(id) {
  const record = await readRecord(id);
  if (record) await clearDownload(id, record.segments.length);
}

// ── playlist handling ───────────────────────────────────────────────────────

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
        // The IV is stored as hex so the record survives JSON-ish structured
        // cloning without a typed-array surprise.
        : { method: attrs.METHOD, uri: resolve(attrs.URI, playlistUrl), ivHex: attrs.IV ? attrs.IV.replace(/^0x/i, "") : null };
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

export function sanitizeFileName(name) {
  return String(name).replace(/[\\/:*?"<>|]+/g, "_").replace(/\s+/g, " ").trim().slice(0, 120) || "video";
}

// ── the download itself ─────────────────────────────────────────────────────

function saveBlob(blob, fileName) {
  const href = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = href;
  anchor.download = fileName;
  anchor.click();
  setTimeout(() => URL.revokeObjectURL(href), 60_000);
}

/** Assemble what is stored and hand it to the browser as a file. */
async function assembleAndSave(record) {
  const parts = await readChunks(record.id, record.segments.length);
  saveBlob(new Blob(parts, { type: "video/mp2t" }), record.fileName);
}

/**
 * Saves a finished download again — the path for a browser that swallowed the
 * automatic save, called from a real click so nothing can block it. Clears the
 * stored copy afterwards: the file has been delivered.
 */
export async function saveFinishedDownload(id) {
  const record = await readRecord(id);
  if (!record?.finished) return false;
  await assembleAndSave(record);
  await clearDownload(id, record.segments.length);
  return true;
}

/**
 * @param {object}   options
 * @param {string}   options.id           identity from downloadIdentity()
 * @param {string}   options.url          playlist URL (master or media)
 * @param {string}   options.fileName     suggested name, without extension
 * @param {Function} options.onProgress   ({done, total, bytes, phase}) => void
 * @param {AbortSignal} options.signal
 * @returns {Promise<{bytes: number, segments: number, removedSeconds: number, fileName: string}>}
 */
export async function downloadStream({ id, url, fileName, onProgress, signal }) {
  const report = (patch) => onProgress?.(patch);

  let record = await readRecord(id);

  if (!record) {
    report({ phase: "playlist", done: 0, total: 0, bytes: 0 });
    const { text, url: mediaUrl } = await toMediaPlaylist(url, signal);
    const filtered = stripAds(text, mediaUrl);
    const segments = parseSegments(filtered.text, mediaUrl);
    if (!segments.length) throw new Error("Playlist contained no segments.");

    // The segment list is captured once and the record carries it from then
    // on. Resuming re-reads this rather than the network: these providers
    // rebuild their playlists, and a resume stitched from a fresh fetch could
    // append half of a different encode onto this one.
    record = {
      id,
      fileName: `${sanitizeFileName(fileName)}.ts`,
      segments,
      completedSegments: 0,
      bytes: 0,
      removedSeconds: filtered.removedSeconds,
      finished: false,
      createdAt: Date.now(),
    };
    await writeRecord(record);
  }

  const keyCache = new Map();

  while (record.completedSegments < record.segments.length) {
    if (signal?.aborted) throw new DOMException("Aborted", "AbortError");
    const index = record.completedSegments;
    const segment = record.segments[index];

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
      const iv = segment.key.ivHex ? hexToBytes(segment.key.ivHex) : sequenceIv(segment.sequence);
      data = new Uint8Array(await crypto.subtle.decrypt({ name: "AES-CBC", iv }, cryptoKey, data));
    }

    // The chunk goes in before the record moves: killed between the two, the
    // worst case is one segment stored twice-over, which the next put simply
    // overwrites. The other order would record progress the storage cannot back.
    await putChunk(id, index, new Blob([data]));
    record = {
      ...record,
      completedSegments: index + 1,
      bytes: record.bytes + data.byteLength,
      finished: index + 1 === record.segments.length,
    };
    await writeRecord(record);

    report({
      phase: "downloading",
      done: record.completedSegments,
      total: record.segments.length,
      bytes: record.bytes,
    });
  }

  report({ phase: "assembling", done: record.completedSegments, total: record.segments.length, bytes: record.bytes });
  await assembleAndSave(record);
  await clearDownload(id, record.segments.length);

  report({ phase: "done", done: record.segments.length, total: record.segments.length, bytes: record.bytes });
  return {
    bytes: record.bytes,
    segments: record.segments.length,
    removedSeconds: record.removedSeconds,
    fileName: record.fileName,
  };
}
