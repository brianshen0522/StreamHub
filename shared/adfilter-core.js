/**
 * Ad classification shared by the browser filter and the server's duration
 * probe. Both used to carry their own copy of these rules; when they drift the
 * runtime shown in the source list stops matching the player's timeline, so
 * the decision now lives in exactly one place.
 *
 * Pure ESM with no platform APIs beyond URL, so it loads unchanged in Node and
 * in the bundle.
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
