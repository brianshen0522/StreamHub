/**
 * Browser half of the ad filter.
 *
 * The parsing, classification and rewriting all live in
 * shared/adfilter-core.js, which the server uses too. What is genuinely
 * browser-only stays here: unwrapping proxy URLs, and the hls.js loader.
 *
 * For why ad breaks are inferred from segment directories rather than from
 * discontinuity markers or SCTE-35, see the comment in adfilter-core.js.
 */

import { stripAds as stripAdsCore } from "../../shared/adfilter-core.js";

const PROXY_PATH = "/api/stream";

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

/**
 * @returns {{text: string, cuts: Array<{at: number, removed: number}>, reason: string|null,
 *            removedSeconds: number, contentDirectory: string|null}}
 *   `cuts[].at` is the position in the *cleaned* timeline where a break was removed.
 */
export function stripAds(text, playlistUrl) {
  return stripAdsCore(text, playlistUrl, { resolveUri: unwrapProxy });
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
              if (result.cuts.length) response.data = result.text;
              // Reported whether or not anything was found, so a playlist with
              // no breaks in it clears the marks left by the one before rather
              // than leaving them on a timeline they no longer describe. Master
              // playlists and audio renditions are excluded by the `#EXTINF`
              // test inside stripAds, which returns "not-a-media-playlist" —
              // otherwise loading either of those would wipe the video's marks.
              if (result.reason !== "not-a-media-playlist") onResult?.(result);
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
