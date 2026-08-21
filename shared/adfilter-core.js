/**
 * HLS ad filtering, shared by every consumer: the browser's playlist loader and
 * downloader, the server's duration probe, and the server's cleaned-manifest
 * endpoint. All of them used to carry their own copy of some part of this; when
 * the copies drift the runtime shown in the source list stops matching the
 * player's timeline, so parsing, classification and rewriting all live here.
 *
 * Pure ESM with no platform APIs beyond URL, so it loads unchanged in Node and
 * in the bundle. Anything platform-specific — unwrapping proxy URLs in the
 * browser, an hls.js loader — belongs in the caller, not here.
 *
 * These providers emit no SCTE-35 / #EXT-X-CUE-OUT, so ad breaks are inferred
 * structurally: spliced ad segments live in a *different directory* than the
 * feature and are bracketed by #EXT-X-DISCONTINUITY. Discontinuity count alone
 * is not usable — one sampled playlist had 80 of them, only 5 being ads; the
 * rest were routine ~40s encoder splits.
 */

/** Guards — if any fails the playlist is treated as carrying no ads. */
export const MIN_CONTENT_SHARE = 0.6;   // dominant directory must hold most of the runtime
export const MAX_AD_RUN_SECONDS = 240;  // a long foreign run is more likely content than an ad
export const MAX_STRIP_SHARE = 0.35;    // never treat more than this much as advertising

export function directoryOf(uri, baseUrl) {
  try {
    const absolute = new URL(uri, baseUrl);
    return absolute.origin + absolute.pathname.replace(/\/[^/]*$/, "/");
  } catch {
    return "";
  }
}

function runSeconds(run) {
  return run.segments.reduce((total, segment) => total + (segment.duration || 0), 0);
}

/**
 * @param {Array<{segments: Array<{uri: string, duration: number}>}>} runs
 *   Playlist split on #EXT-X-DISCONTINUITY. Callers may hang extra data off
 *   each segment; only `uri` and `duration` are read here.
 * @param {string} playlistUrl Base for resolving relative segment URIs.
 * @returns {{ads: boolean, reason: string|null, contentDirectory: string|null,
 *            totalSeconds: number, adSeconds: number, isAd: (index: number) => boolean}}
 */
export function classifyRuns(runs, playlistUrl) {
  const totalSeconds = runs.reduce((total, run) => total + runSeconds(run), 0);
  const none = (reason) => ({
    ads: false,
    reason,
    contentDirectory: null,
    totalSeconds,
    adSeconds: 0,
    isAd: () => false,
  });

  if (runs.length < 2) return none("no-discontinuities");
  if (!totalSeconds) return none("empty-playlist");

  const byDirectory = new Map();
  const meta = runs.map((run) => {
    const directory = directoryOf(run.segments[0].uri, playlistUrl);
    const seconds = runSeconds(run);
    byDirectory.set(directory, (byDirectory.get(directory) || 0) + seconds);
    return { directory, seconds };
  });

  if (byDirectory.size < 2) return none("single-directory");

  let contentDirectory = null;
  let contentSeconds = 0;
  for (const [directory, seconds] of byDirectory) {
    if (seconds > contentSeconds) {
      contentSeconds = seconds;
      contentDirectory = directory;
    }
  }

  if (contentSeconds / totalSeconds < MIN_CONTENT_SHARE) return none("no-dominant-directory");

  const adFlags = meta.map((run) => run.directory !== contentDirectory && run.seconds <= MAX_AD_RUN_SECONDS);
  const adSeconds = meta.reduce((total, run, index) => total + (adFlags[index] ? run.seconds : 0), 0);

  if (!adSeconds) return none("no-foreign-runs");
  if (adSeconds / totalSeconds > MAX_STRIP_SHARE) return none("would-remove-too-much");

  return {
    ads: true,
    reason: null,
    contentDirectory,
    totalSeconds,
    adSeconds,
    isAd: (index) => adFlags[index] === true,
  };
}

/**
 * Split a media playlist into runs delimited by #EXT-X-DISCONTINUITY,
 * remembering the encryption key / init map in effect for every segment so the
 * state can be re-emitted correctly after a run is excised.
 *
 * @param {string[]} lines
 * @returns {{header: string[], runs: Array<{segments: Array<{uri: string,
 *            duration: number, tags: string[], key: string|null, map: string|null}>,
 *            discontinuityBefore: boolean}>, trailer: string[]}}
 */
export function parsePlaylist(lines) {
  const header = [];
  const runs = [];
  const trailer = [];

  let current = { segments: [], discontinuityBefore: false };
  let pendingTags = [];
  let activeKey = null;
  let activeMap = null;
  let seenSegment = false;
  let duration = 0;

  for (const raw of lines) {
    const line = raw.trim();
    if (!line) continue;

    if (line.startsWith("#EXT-X-KEY")) {
      activeKey = line;
      if (!seenSegment) header.push(line);
      continue;
    }
    if (line.startsWith("#EXT-X-MAP")) {
      activeMap = line;
      if (!seenSegment) header.push(line);
      continue;
    }
    if (line.startsWith("#EXT-X-DISCONTINUITY")) {
      if (current.segments.length) runs.push(current);
      current = { segments: [], discontinuityBefore: true };
      continue;
    }
    if (line.startsWith("#EXTINF")) {
      const match = /^#EXTINF:([\d.]+)/.exec(line);
      duration = match ? Number.parseFloat(match[1]) : 0;
      pendingTags.push(line);
      continue;
    }
    if (line.startsWith("#EXT-X-ENDLIST")) {
      trailer.push(line);
      continue;
    }
    if (line.startsWith("#")) {
      // Any other tag: header material before the first segment, otherwise it
      // belongs to the segment that follows.
      if (seenSegment || pendingTags.length) pendingTags.push(line);
      else header.push(line);
      continue;
    }

    // A URI line closes the current segment.
    current.segments.push({
      uri: line,
      duration: Number.isFinite(duration) ? duration : 0,
      tags: pendingTags,
      key: activeKey,
      map: activeMap,
    });
    pendingTags = [];
    duration = 0;
    seenSegment = true;
  }

  if (current.segments.length) runs.push(current);
  return { header, runs, trailer };
}

function runDuration(run) {
  return run.segments.reduce((total, segment) => total + segment.duration, 0);
}

/**
 * Remove spliced ad runs from a media playlist.
 *
 * @param {string} text Raw playlist body.
 * @param {string} playlistUrl Base for resolving relative segment URIs.
 * @param {{resolveUri?: (uri: string) => string}} [options]
 *   `resolveUri` maps a URI to the real upstream one before classification, for
 *   callers whose URIs are wrapped — the browser sees segments behind
 *   /api/stream once playback has fallen back to the proxy, and comparing
 *   directories on wrapped URLs would put every segment in the same directory.
 *   It affects classification only; emitted URIs are always the originals.
 * @returns {{text: string, cuts: Array<{at: number, removed: number}>, reason: string|null,
 *            removedSeconds: number, contentDirectory: string|null}}
 *   `cuts[].at` is the position in the *cleaned* timeline where a break was removed.
 */
export function stripAds(text, playlistUrl, { resolveUri = (uri) => uri } = {}) {
  const unchanged = (reason) => ({ text, cuts: [], reason, removedSeconds: 0, contentDirectory: null });

  if (typeof text !== "string" || !text.includes("#EXTINF")) {
    return unchanged("not-a-media-playlist");
  }

  const { header, runs, trailer } = parsePlaylist(text.split(/\r?\n/));

  const verdict = classifyRuns(
    runs.map((run) => ({ segments: run.segments.map((s) => ({ uri: resolveUri(s.uri), duration: s.duration })) })),
    resolveUri(playlistUrl),
  );
  if (!verdict.ads) return unchanged(verdict.reason);

  const isAd = (index) => verdict.isAd(index);

  // Rebuild, keeping one discontinuity wherever a break was excised so the
  // player still resets timestamp continuity across the splice.
  const out = header.filter((line) => !line.startsWith("#EXT-X-KEY") && !line.startsWith("#EXT-X-MAP"));
  const cuts = [];
  let lastKey = null;
  let lastMap = null;
  let keptDuration = 0;
  let emittedRun = false;
  let discontinuityPending = false;
  let pendingRemoved = 0;

  for (const [index, run] of runs.entries()) {
    if (isAd(index)) {
      discontinuityPending = true;
      pendingRemoved += runDuration(run);
      continue;
    }

    if (emittedRun && (discontinuityPending || run.discontinuityBefore)) {
      out.push("#EXT-X-DISCONTINUITY");
    }
    if (pendingRemoved > 0) {
      cuts.push({ at: keptDuration, removed: Number(pendingRemoved.toFixed(3)) });
      pendingRemoved = 0;
    }
    discontinuityPending = false;

    for (const segment of run.segments) {
      if (segment.key && segment.key !== lastKey) {
        out.push(segment.key);
        lastKey = segment.key;
      }
      if (segment.map && segment.map !== lastMap) {
        out.push(segment.map);
        lastMap = segment.map;
      }
      out.push(...segment.tags, segment.uri);
      keptDuration += segment.duration;
    }
    emittedRun = true;
  }

  // A break sitting at the very end has no following content run.
  if (pendingRemoved > 0) {
    cuts.push({ at: keptDuration, removed: Number(pendingRemoved.toFixed(3)) });
  }

  out.push(...trailer);

  return {
    text: out.join("\n") + "\n",
    cuts,
    reason: null,
    removedSeconds: Number(verdict.adSeconds.toFixed(3)),
    contentDirectory: verdict.contentDirectory,
  };
}
