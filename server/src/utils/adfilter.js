/**
 * Ad classification for HLS media playlists — the measuring half of the
 * filter the player applies in the browser.
 *
 * These providers emit no SCTE-35 / #EXT-X-CUE-OUT, so ad breaks are inferred
 * structurally: spliced ad segments live in a *different directory* than the
 * feature and are bracketed by #EXT-X-DISCONTINUITY.
 *
 * KEEP IN SYNC with frontend/src/adfilter.js — it uses the same runs, the same
 * dominant-directory rule and the same three guards. If they diverge, the
 * duration shown in the source list stops matching the player's timeline.
 * (They are separate files because the Docker build contexts are ./server and
 * ./frontend, so neither can import across the boundary.)
 */

const MIN_CONTENT_SHARE = 0.6;
const MAX_AD_RUN_SECONDS = 240;
const MAX_STRIP_SHARE = 0.35;

function directoryOf(uri, baseUrl) {
  try {
    const absolute = new URL(uri, baseUrl);
    return absolute.origin + absolute.pathname.replace(/\/[^/]*$/, "/");
  } catch {
    return "";
  }
}

/** Splits a media playlist into runs delimited by #EXT-X-DISCONTINUITY. */
function parseRuns(text) {
  const runs = [];
  let current = [];
  let duration = 0;

  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) continue;

    if (line.startsWith("#EXT-X-DISCONTINUITY")) {
      if (current.length) runs.push(current);
      current = [];
    } else if (line.startsWith("#EXTINF")) {
      const match = /^#EXTINF:([\d.]+)/.exec(line);
      duration = match ? Number.parseFloat(match[1]) : 0;
    } else if (!line.startsWith("#")) {
      current.push({ uri: line, duration: Number.isFinite(duration) ? duration : 0 });
      duration = 0;
    }
  }
  if (current.length) runs.push(current);
  return runs;
}

/**
 * @returns {{totalSeconds: number, contentSeconds: number, adSeconds: number}}
 *   `contentSeconds` equals `totalSeconds` whenever no ads can be identified,
 *   so callers can use it unconditionally.
 */
export function analyzePlaylist(text, playlistUrl) {
  const runs = parseRuns(text);
  const totalSeconds = runs.reduce(
    (sum, run) => sum + run.reduce((inner, segment) => inner + segment.duration, 0),
    0,
  );
  const unchanged = { totalSeconds, contentSeconds: totalSeconds, adSeconds: 0 };

  if (runs.length < 2 || !totalSeconds) return unchanged;

  const byDirectory = new Map();
  const meta = runs.map((run) => {
    const directory = directoryOf(run[0].uri, playlistUrl);
    const seconds = run.reduce((sum, segment) => sum + segment.duration, 0);
    byDirectory.set(directory, (byDirectory.get(directory) || 0) + seconds);
    return { directory, seconds };
  });

  if (byDirectory.size < 2) return unchanged;

  let contentDirectory = null;
  let contentSeconds = 0;
  for (const [directory, seconds] of byDirectory) {
    if (seconds > contentSeconds) {
      contentSeconds = seconds;
      contentDirectory = directory;
    }
  }

  if (contentSeconds / totalSeconds < MIN_CONTENT_SHARE) return unchanged;

  const adSeconds = meta
    .filter((run) => run.directory !== contentDirectory && run.seconds <= MAX_AD_RUN_SECONDS)
    .reduce((sum, run) => sum + run.seconds, 0);

  if (!adSeconds || adSeconds / totalSeconds > MAX_STRIP_SHARE) return unchanged;

  return {
    totalSeconds,
    contentSeconds: totalSeconds - adSeconds,
    adSeconds,
  };
}
