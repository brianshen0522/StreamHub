/**
 * Server-side half of the ad filter: parse a media playlist and report how much
 * of it is advertising, so the duration shown in the source list matches the
 * timeline the player will produce.
 *
 * The decision itself lives in shared/adfilter-core.js, which the browser
 * filter also uses — previously the two carried separate copies of the rules
 * and could drift apart.
 */

import { classifyRuns } from "../../../shared/adfilter-core.js";

/** Splits a media playlist into runs delimited by #EXT-X-DISCONTINUITY. */
function parseRuns(text) {
  const runs = [];
  let segments = [];
  let duration = 0;

  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) continue;

    if (line.startsWith("#EXT-X-DISCONTINUITY")) {
      if (segments.length) runs.push({ segments });
      segments = [];
    } else if (line.startsWith("#EXTINF")) {
      const match = /^#EXTINF:([\d.]+)/.exec(line);
      duration = match ? Number.parseFloat(match[1]) : 0;
    } else if (!line.startsWith("#")) {
      segments.push({ uri: line, duration: Number.isFinite(duration) ? duration : 0 });
      duration = 0;
    }
  }
  if (segments.length) runs.push({ segments });
  return runs;
}

/**
 * @returns {{totalSeconds: number, contentSeconds: number, adSeconds: number}}
 *   `contentSeconds` equals `totalSeconds` when no ads can be identified, so
 *   callers can use it unconditionally.
 */
export function analyzePlaylist(text, playlistUrl) {
  const verdict = classifyRuns(parseRuns(text), playlistUrl);
  return {
    totalSeconds: verdict.totalSeconds,
    contentSeconds: verdict.totalSeconds - verdict.adSeconds,
    adSeconds: verdict.adSeconds,
  };
}
