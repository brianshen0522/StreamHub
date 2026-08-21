/**
 * Server-side entry points to the ad filter.
 *
 * The rules, the parser and the rewriter all live in shared/adfilter-core.js,
 * which the browser uses too — the two used to carry separate copies and could
 * drift apart, which showed up as a source list advertising a runtime the
 * player's timeline did not match.
 *
 * `analyzePlaylist` measures; `stripAds` rewrites. The measurement feeds the
 * runtime shown in the source list, so both have to agree about what counts as
 * an ad, which is exactly why they now share one implementation.
 */

import { classifyRuns, parsePlaylist, stripAds } from "../../../shared/adfilter-core.js";

export { stripAds };

/**
 * @returns {{totalSeconds: number, contentSeconds: number, adSeconds: number}}
 *   `contentSeconds` equals `totalSeconds` when no ads can be identified, so
 *   callers can use it unconditionally.
 */
export function analyzePlaylist(text, playlistUrl) {
  const { runs } = parsePlaylist(text.split(/\r?\n/));
  const verdict = classifyRuns(runs, playlistUrl);
  return {
    totalSeconds: verdict.totalSeconds,
    contentSeconds: verdict.totalSeconds - verdict.adSeconds,
    adSeconds: verdict.adSeconds,
  };
}
