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
 * structurally, by three signals over the runs between #EXT-X-DISCONTINUITY:
 * the foreign directory (ads spliced from somewhere else than the feature),
 * the repeated run (the same URI sequence recurring — a feature never plays
 * the same segments twice, an ad break spliced into every act does), and the
 * sandwiched interloper (a short run wedged between two feature-length
 * blocks, for the providers that re-encode ads into the feature's own
 * directory). Discontinuity count alone is not usable — one sampled playlist
 * had 80 of them, only 5 being ads; the rest were routine ~40s encoder
 * splits — which is what the fences on each signal are for.
 */

/** Guards — if any fails the playlist is treated as carrying no ads. */
export const MIN_CONTENT_SHARE = 0.6;   // dominant directory must hold most of the runtime
export const MAX_AD_RUN_SECONDS = 240;  // a long foreign run is more likely content than an ad
export const MAX_STRIP_SHARE = 0.35;    // never treat more than this much as advertising

/**
 * The sandwich signal's fences. A run is only called an ad on shape alone
 * when it is short, both neighbours are unmistakably feature-sized, and the
 * playlist is made of a handful of large blocks rather than confetti — the
 * routine-discontinuity playlists (one every 20 segments, or one every 6)
 * fail the neighbour and run-count fences and are left untouched, along with
 * whatever hides in them.
 */
export const MAX_SANDWICH_RUN_SECONDS = 60;
export const MIN_SANDWICH_NEIGHBOR_SECONDS = 300;
export const MAX_SANDWICH_RUN_COUNT = 9;

/**
 * The cadence signal's fences. Some encoders cut a discontinuity every N
 * segments like clockwork; in those playlists an ad re-encoded into the
 * feature's own stream — same directory, URIs numbered seamlessly into the
 * feature's sequence — still betrays itself by breaking the beat with a
 * short run of fewer segments. Only trusted when the beat is strong (most
 * runs carry exactly the modal count) and long enough to mean something;
 * the first and last runs are exempt, since starting and ending off-beat
 * is just how a file begins and ends.
 */
export const MIN_CADENCE_RUN_COUNT = 12;
export const MIN_CADENCE_SEGMENTS = 6;
export const MIN_CADENCE_SHARE = 0.75;
export const MAX_CADENCE_AD_SECONDS = 60;
// A broken-beat run must also be clearly *shorter* than the beat to be an
// ad. The splice cuts the feature mid-run, and the stub left behind breaks
// the beat too — one segment short but nearly full length. The ads observed
// run 20-26 s against a 40 s beat; the stubs 34-40 s. Cutting stubs would
// cut feature.
export const CADENCE_SHORT_RATIO = 0.7;

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

  const adFlags = meta.map(() => false);

  // ── Signal 1: the foreign directory ─────────────────────────────────────
  // Spliced ads usually live somewhere else than the feature. Only usable
  // when one directory clearly owns the runtime.
  let contentDirectory = null;
  if (byDirectory.size >= 2) {
    let contentSeconds = 0;
    for (const [directory, seconds] of byDirectory) {
      if (seconds > contentSeconds) {
        contentSeconds = seconds;
        contentDirectory = directory;
      }
    }
    if (contentSeconds / totalSeconds >= MIN_CONTENT_SHARE) {
      meta.forEach((run, index) => {
        if (run.directory !== contentDirectory && run.seconds <= MAX_AD_RUN_SECONDS) adFlags[index] = true;
      });
    } else {
      contentDirectory = null;
    }
  }

  // ── Signal 2: the repeated run ──────────────────────────────────────────
  // A feature never plays the same segments twice; an ad break spliced into
  // every act does, URI for URI. Every occurrence of a repeated short run is
  // an ad — including in playlists where the ads share the feature's
  // directory and signal 1 sees nothing.
  const signatures = new Map();
  runs.forEach((run, index) => {
    const signature = run.segments.map((segment) => segment.uri).join("\n");
    if (!signatures.has(signature)) signatures.set(signature, []);
    signatures.get(signature).push(index);
  });
  for (const indexes of signatures.values()) {
    if (indexes.length < 2) continue;
    for (const index of indexes) {
      if (meta[index].seconds <= MAX_AD_RUN_SECONDS) adFlags[index] = true;
    }
  }

  // ── Signal 3: the sandwiched interloper ─────────────────────────────────
  // Same-directory splices with unique URIs still give themselves away by
  // shape: a playlist that is a few feature-length blocks with a short run
  // wedged between two of them. Fenced hard (see the constants) so playlists
  // that are all confetti — a discontinuity every N segments — never match.
  if (runs.length >= 3 && runs.length <= MAX_SANDWICH_RUN_COUNT) {
    for (let index = 1; index < runs.length - 1; index += 1) {
      if (meta[index].seconds <= MAX_SANDWICH_RUN_SECONDS
        && meta[index - 1].seconds >= MIN_SANDWICH_NEIGHBOR_SECONDS
        && meta[index + 1].seconds >= MIN_SANDWICH_NEIGHBOR_SECONDS) {
        adFlags[index] = true;
      }
    }
  }

  // ── Signal 4: the broken cadence ────────────────────────────────────────
  // Clockwork playlists — a discontinuity every N segments — where the ad is
  // re-encoded into the feature's own stream, URIs numbered seamlessly into
  // its sequence. Directory, repetition and sandwich all see nothing; the
  // only tell left is a short interior run that breaks the beat.
  //
  // Only consulted when every other signal is silent. Where a splice is
  // already caught by directory or repetition, the beat around it is broken
  // by the *feature*: the run the ad landed in comes out as two off-beat
  // stubs either side of it, summing to one beat — and reading those as ads
  // cut forty seconds of feature at every break.
  if (!adFlags.some(Boolean) && runs.length >= MIN_CADENCE_RUN_COUNT) {
    const tally = new Map();
    for (const run of runs) {
      tally.set(run.segments.length, (tally.get(run.segments.length) || 0) + 1);
    }
    let beat = 0;
    let beatRuns = 0;
    for (const [count, occurrences] of tally) {
      if (occurrences > beatRuns) {
        beatRuns = occurrences;
        beat = count;
      }
    }
    if (beat >= MIN_CADENCE_SEGMENTS && beatRuns / runs.length >= MIN_CADENCE_SHARE) {
      const beatSeconds = runs
        .map((run, index) => (run.segments.length === beat ? meta[index].seconds : null))
        .filter((seconds) => seconds !== null)
        .sort((a, b) => a - b);
      const medianBeatSeconds = beatSeconds[Math.floor(beatSeconds.length / 2)] || 0;
      const shortEnough = Math.min(MAX_CADENCE_AD_SECONDS, medianBeatSeconds * CADENCE_SHORT_RATIO);
      for (let index = 1; index < runs.length - 1; index += 1) {
        if (runs[index].segments.length < beat && meta[index].seconds <= shortEnough) {
          adFlags[index] = true;
        }
      }
    }
  }

  const adSeconds = meta.reduce((total, run, index) => total + (adFlags[index] ? run.seconds : 0), 0);

  if (!adSeconds) {
    return none(byDirectory.size < 2 ? "single-directory" : (contentDirectory ? "no-foreign-runs" : "no-dominant-directory"));
  }
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
 * @returns {{text: string, cuts: Array<{at: number, segment: number, removed: number}>,
 *            reason: string|null, removedSeconds: number, keptSeconds: number,
 *            contentDirectory: string|null}}
 *   `cuts[].at` is the position in the *cleaned* timeline where a break was
 *   removed, as a sum of `#EXTINF` values, and `cuts[].segment` is the index of
 *   the kept segment that follows it. Anything drawing a mark on a player's
 *   timeline wants the index: a player that has crossed a discontinuity times
 *   the rest from the media's own timestamps rather than from `#EXTINF`, so the
 *   two can disagree — and it only finds that out as it reaches the region,
 *   which is while somebody is watching.
 */
export function stripAds(text, playlistUrl, { resolveUri = (uri) => uri } = {}) {
  const unchanged = (reason) => ({
    text, cuts: [], reason, removedSeconds: 0, keptSeconds: 0, contentDirectory: null,
  });

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
  // How many segments have been kept so far. A cut records this alongside its
  // time because the time is a sum of #EXTINF values, and a player that has met
  // a discontinuity stops timing by those and starts timing by the media's own
  // timestamps. The segment index survives that; the second count does not.
  let keptSegments = 0;
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
      cuts.push({
        at: keptDuration,
        segment: keptSegments,
        removed: Number(pendingRemoved.toFixed(3)),
      });
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
      keptSegments += 1;
    }
    emittedRun = true;
  }

  // A break sitting at the very end has no following content run.
  if (pendingRemoved > 0) {
    cuts.push({
      at: keptDuration,
      segment: keptSegments,
      removed: Number(pendingRemoved.toFixed(3)),
    });
  }

  out.push(...trailer);

  return {
    text: out.join("\n") + "\n",
    cuts,
    reason: null,
    removedSeconds: Number(verdict.adSeconds.toFixed(3)),
    keptSeconds: Number(keptDuration.toFixed(3)),
    contentDirectory: verdict.contentDirectory,
  };
}
