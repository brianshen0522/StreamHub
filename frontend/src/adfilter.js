/**
 * HLS ad stripping.
 *
 * The providers we scrape do not emit SCTE-35 / #EXT-X-CUE-OUT, so ad breaks
 * have to be inferred from structure. The reliable signal observed across
 * these CDNs is that spliced ad segments are served from a *different
 * directory* than the feature, bracketed by #EXT-X-DISCONTINUITY:
 *
 *   content  https://cdn/20241124/eGnbjltL/4146kb/hls/....ts    (29.97 fps)
 *   ad       https://cdn/20260811/hjJjF6Wa/10097kb/hls/....ts   (25 fps)
 *
 * Removing those runs reproduces the ad-free variant's duration exactly
 * (2908.3s - 88.2s = 2820.1s, matching the clean source at 2820.4s).
 *
 * Discontinuity count alone is NOT a signal: one sampled playlist had 80
 * discontinuities of which only 5 were ads (the rest were routine ~40s
 * encoder splits). Playlists whose ads live in the same directory are left
 * untouched — there is no dependable manifest-level evidence for those.
 */

const PROXY_PATH = "/api/stream";

/** Guards — if any fails the playlist is passed through unmodified. */
const MIN_CONTENT_SHARE = 0.6;      // dominant directory must hold most of the runtime
const MAX_AD_RUN_SECONDS = 240;     // a long foreign run is more likely content than an ad
const MAX_STRIP_SHARE = 0.35;       // never remove more than this much of the playlist

/** Segments reach us wrapped by /api/stream when playback fell back to the proxy. */
function unwrapProxy(url) {
  try {
    const parsed = new URL(url, window.location.origin);
    if (parsed.pathname === PROXY_PATH) {
      const target = parsed.searchParams.get("target");
      if (target) return target;
    }
  } catch { /* not a URL we can parse — fall through */ }
  return url;
}

function directoryOf(uri, baseUrl) {
  try {
    const absolute = new URL(unwrapProxy(uri), unwrapProxy(baseUrl));
    return absolute.origin + absolute.pathname.replace(/\/[^/]*$/, "/");
  } catch {
    return "";
  }
}

/**
 * Split a media playlist into runs delimited by #EXT-X-DISCONTINUITY,
 * remembering the encryption key / init map in effect for every segment.
 */
function parsePlaylist(lines) {
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
 * @returns {{text: string, cuts: Array<{at: number, removed: number}>, reason: string|null,
 *            removedSeconds: number, contentDirectory: string|null}}
 *   `cuts[].at` is the position in the *cleaned* timeline where a break was removed.
 */
export function stripAds(text, playlistUrl) {
  const unchanged = (reason) => ({ text, cuts: [], reason, removedSeconds: 0, contentDirectory: null });

  if (typeof text !== "string" || !text.includes("#EXTINF")) {
    return unchanged("not-a-media-playlist");
  }

  const { header, runs, trailer } = parsePlaylist(text.split(/\r?\n/));
  if (runs.length < 2) return unchanged("no-discontinuities");

  // Weight every directory by how much runtime it accounts for.
  const byDirectory = new Map();
  let totalDuration = 0;
  for (const run of runs) {
    const directory = directoryOf(run.segments[0].uri, playlistUrl);
    const seconds = runDuration(run);
    run.directory = directory;
    run.seconds = seconds;
    totalDuration += seconds;
    byDirectory.set(directory, (byDirectory.get(directory) || 0) + seconds);
  }

  if (byDirectory.size < 2) return unchanged("single-directory");

  let contentDirectory = null;
  let contentSeconds = 0;
  for (const [directory, seconds] of byDirectory) {
    if (seconds > contentSeconds) {
      contentSeconds = seconds;
      contentDirectory = directory;
    }
  }

  if (!totalDuration || contentSeconds / totalDuration < MIN_CONTENT_SHARE) {
    return unchanged("no-dominant-directory");
  }

  const isAd = (run) => run.directory !== contentDirectory && run.seconds <= MAX_AD_RUN_SECONDS;
  const adRuns = runs.filter(isAd);
  if (!adRuns.length) return unchanged("no-foreign-runs");

  const removedSeconds = adRuns.reduce((total, run) => total + run.seconds, 0);
  if (removedSeconds / totalDuration > MAX_STRIP_SHARE) {
    return unchanged("would-remove-too-much");
  }

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

  for (const run of runs) {
    if (isAd(run)) {
      discontinuityPending = true;
      pendingRemoved += run.seconds;
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
    removedSeconds: Number(removedSeconds.toFixed(3)),
    contentDirectory,
  };
}

/**
 * Builds an hls.js playlist loader that filters ads out of every media
 * playlist before hls.js parses it, so ad segments are never requested and
 * never appear on the timeline.
 */
export function createAdFilterLoader(Hls, onResult) {
  const BaseLoader = Hls.DefaultConfig.loader;

  return class AdFilterLoader extends BaseLoader {
    load(context, config, callbacks) {
      const originalOnSuccess = callbacks.onSuccess;

      const patched = {
        ...callbacks,
        onSuccess: (response, stats, ctx, networkDetails) => {
          try {
            if (typeof response.data === "string") {
              const result = stripAds(response.data, response.url || ctx?.url || "");
              if (result.cuts.length) {
                response.data = result.text;
                onResult?.(result);
              }
            }
          } catch {
            // Never let filtering break playback — fall through with the original.
          }
          originalOnSuccess(response, stats, ctx, networkDetails);
        },
      };

      super.load(context, config, patched);
    }
  };
}
