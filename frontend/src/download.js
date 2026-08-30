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
      const db = request.result;
      // Safari closes idle IndexedDB connections on its own; holding on to a
      // dead one made the next download's very first read blow up. Forgetting
      // the cache here means the next caller simply opens a fresh connection.
      db.onclose = () => { dbPromise = null; };
      db.onversionchange = () => { try { db.close(); } catch { /* closing */ } dbPromise = null; };
      prune(db).catch(() => {});
      resolve(db);
    };
    request.onerror = () => reject(request.error ?? new Error("IndexedDB open failed"));
  });
  return dbPromise;
}

/**
 * Runs one operation against the database, retrying once through a fresh
 * connection. The close handler above covers Safari announcing the idle
 * close; this covers it not bothering to — the first touch of a silently
 * dead connection throws InvalidStateError (or fails with a null error,
 * which is how "The operation cannot be completed" reached the screen), and
 * the retry reopens and succeeds.
 */
async function idb(run) {
  for (let attempt = 0; attempt < 2; attempt += 1) {
    const db = await openDb();
    try {
      return await run(db);
    } catch (error) {
      if (attempt === 0 && (error == null || error?.name === "InvalidStateError")) {
        dbPromise = null;
        continue;
      }
      throw error ?? new Error("IndexedDB request failed");
    }
  }
  throw new Error("IndexedDB request failed");
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
    transaction.onerror = () => reject(transaction.error ?? new Error("IndexedDB transaction failed"));
    transaction.onabort = () => reject(transaction.error ?? new Error("IndexedDB transaction aborted"));
  });
}

function readRecord(id) {
  return idb((db) => new Promise((resolve, reject) => {
    const request = db.transaction("records").objectStore("records").get(id);
    request.onsuccess = () => resolve(request.result ?? null);
    request.onerror = () => reject(request.error);
  }));
}

function writeRecord(record) {
  return idb((db) => tx(db, "records", "readwrite", (store) => store.put(record)));
}

/**
 * Chunks are stored as raw bytes, never as Blobs: Safari's IndexedDB rejects
 * Blob writes outright (with an error object so empty it surfaced on screen
 * as "This operation cannot be completed"), and even where they do store,
 * a download URL later stitched from IDB-backed blobs is what WebKit's
 * "WebKitBlobResource error 1" is about. Buffers write everywhere.
 */
function putChunk(id, index, data) {
  return idb((db) => tx(db, "chunks", "readwrite", (store) => store.put({ id, index, data })));
}

function readChunks(id, count) {
  return idb(async (db) => {
    const store = db.transaction("chunks").objectStore("chunks");
    const parts = new Array(count);
    await Promise.all(
      Array.from({ length: count }, (_, index) => new Promise((resolve, reject) => {
        const request = store.get([id, index]);
        request.onsuccess = () => {
          const stored = request.result;
          // Wrapped into a Blob one chunk at a time, so the buffer can be
          // collected as soon as the browser has copied it — the final file
          // never has to sit in memory whole. `blob` is the pre-buffer
          // format, read for compatibility with partials from older builds.
          parts[index] = stored?.data ? new Blob([stored.data]) : stored?.blob ?? null;
          resolve();
        };
        request.onerror = () => reject(request.error);
      })),
    );
    if (parts.some((part) => !part)) throw new Error("A downloaded segment is missing from storage.");
    return parts;
  });
}

function clearDownload(id, segmentCount) {
  return idb(async (db) => {
    await tx(db, "chunks", "readwrite", (store) => {
      for (let index = 0; index < segmentCount; index += 1) store.delete([id, index]);
    });
    await tx(db, "records", "readwrite", (store) => store.delete(id));
  });
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
  // In the document, not floating: some WebKit builds ignore the click of a
  // detached anchor. Revoked only after the browser has had ample time to
  // materialise the file — a minute was not ample for a multi-hundred-MB
  // save, and a revoked URL mid-save is a corrupted download.
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(href), 600_000);
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

  // Segments come down a window at a time rather than strictly one after
  // another: each fetch spends most of its life waiting on the CDN's round
  // trip, so six in flight is roughly six times the throughput, and the
  // decrypt of one overlaps the transfer of the next. Chunks are keyed by
  // index so arrival order does not matter to storage; what *resume* needs is
  // the record's contiguous frontier — completedSegments only advances across
  // segments with no gap below them, which keeps the old invariant intact: a
  // resumed download re-fetches at most the few in-flight segments above the
  // frontier, and re-putting a chunk simply overwrites it.
  const CONCURRENCY = 6;

  // Keys cached as promises, so six segments hitting the same key URI at once
  // fetch it once instead of racing six copies.
  const keyPromises = new Map();
  const getKey = (uri) => {
    let promise = keyPromises.get(uri);
    if (!promise) {
      promise = (async () => {
        const keyResponse = await fetch(uri, { signal });
        if (!keyResponse.ok) throw new Error(`Key request failed (${keyResponse.status})`);
        return importKey(new Uint8Array(await keyResponse.arrayBuffer()));
      })();
      keyPromises.set(uri, promise);
    }
    return promise;
  };

  const total = record.segments.length;
  let frontier = record.completedSegments;
  let frontierBytes = record.bytes;
  let reportedBytes = record.bytes;
  let storedCount = frontier;
  let nextIndex = frontier;
  const storedSizes = new Map();
  let lastPersist = 0;

  // The record is written when the frontier moves, throttled: per-segment
  // writes were half the time spent on fast links, and losing the last few
  // hundred milliseconds of bookkeeping only means re-fetching those
  // segments — their chunks are already stored and the re-put overwrites.
  const persist = async (force) => {
    const now = Date.now();
    if (!force && now - lastPersist < 800) return;
    lastPersist = now;
    record = { ...record, completedSegments: frontier, bytes: frontierBytes, finished: frontier === total };
    await writeRecord(record);
  };

  const fetchSegment = async (index) => {
    const segment = record.segments[index];
    const response = await fetch(segment.url, { signal });
    if (!response.ok) throw new Error(`Segment ${index + 1} failed (${response.status})`);
    let data = new Uint8Array(await response.arrayBuffer());

    if (segment.key?.method === "AES-128") {
      const cryptoKey = await getKey(segment.key.uri);
      const iv = segment.key.ivHex ? hexToBytes(segment.key.ivHex) : sequenceIv(segment.sequence);
      data = new Uint8Array(await crypto.subtle.decrypt({ name: "AES-CBC", iv }, cryptoKey, data));
    }

    await putChunk(id, index, data);
    storedSizes.set(index, data.byteLength);
    storedCount += 1;
    reportedBytes += data.byteLength;
    while (storedSizes.has(frontier)) {
      frontierBytes += storedSizes.get(frontier);
      storedSizes.delete(frontier);
      frontier += 1;
    }
    await persist(false);
    report({ phase: "downloading", done: storedCount, total, bytes: reportedBytes });
  };

  let failed = false;
  const workers = Array.from({ length: Math.min(CONCURRENCY, Math.max(1, total - frontier)) }, async () => {
    while (!failed && nextIndex < total) {
      if (signal?.aborted) throw new DOMException("Aborted", "AbortError");
      const index = nextIndex;
      nextIndex += 1;
      try {
        await fetchSegment(index);
      } catch (error) {
        // One bad segment stops the others from starting new work; whatever
        // they already have in flight lands harmlessly in storage.
        failed = true;
        throw error;
      }
    }
  });

  try {
    await Promise.all(workers);
  } catch (error) {
    // Keep what is contiguously ours before leaving — this is what makes a
    // cancel at 80% cost nothing.
    await persist(true).catch(() => {});
    throw error;
  }
  await persist(true);

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
