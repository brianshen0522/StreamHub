import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Hls from "hls.js";
import { resolveLanguage, translations } from "./i18n.js";
import { apiJson, apiNdjsonStream, getAccessToken } from "./api.js";
import { usePortalChrome, usePortalLanguage } from "./portal-chrome.js";
import { useCast } from "./cast.js";
import { CastButton, RemotePanel } from "./CastControls.jsx";
import VideoPlayer from "./VideoPlayer.jsx";
import { EpisodeRail, SeasonSelect, SourceSelect } from "./WatchPanels.jsx";
import { createAdFilterLoader } from "./adfilter.js";
import { subscribeRealtime } from "./realtime.js";
import { downloadIdentity, downloadStream, partialDownload, saveFinishedDownload } from "./download.js";
import ImeSafeInput from "./ImeSafeInput.jsx";
import { announceNow, consumePlayRequest, detachReceiver, useBrowserReceiver } from "./castReceiver.js";

const providerOptions = ["movieffm", "777tv", "dramasq"];

/**
 * `exact` is the difference between "take me to this" and "take me to this
 * title". A row of history means the first — it is a bookmark of one viewing —
 * so its season and episode are honoured as given. Everywhere else they are
 * only a fallback for a title with no progress; see `getResumeSeason`.
 *
 * Duplicated in UserPortal.jsx with different parameter names and an identical
 * wire format. Change both.
 */
function encodeViewState({ provider, url, title, mediaType, posterUrl, seasonUrl, episode, exact }) {
  try {
    const obj = { p: provider, u: url, t: title, m: mediaType };
    if (posterUrl) obj.ps = posterUrl;
    if (seasonUrl) obj.s = seasonUrl;
    if (episode)   obj.ep = episode;
    if (exact)     obj.x = 1;
    // Unicode-safe: percent-encode → Latin1 bytes → base64
    const latin1 = encodeURIComponent(JSON.stringify(obj)).replace(/%([0-9A-F]{2})/gi, (_, h) => String.fromCharCode(parseInt(h, 16)));
    return btoa(latin1).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  } catch {
    return "";
  }
}

function decodeViewState(encoded) {
  try {
    const padded = encoded + "===".slice((encoded.length + 3) % 4);
    const latin1 = atob(padded.replace(/-/g, "+").replace(/_/g, "/"));
    const json = decodeURIComponent(latin1.split("").map(c => "%" + c.charCodeAt(0).toString(16).padStart(2, "0")).join(""));
    const obj = JSON.parse(json);
    return { provider: obj.p, url: obj.u, title: obj.t, mediaType: obj.m, posterUrl: obj.ps || "", seasonUrl: obj.s || null, episode: obj.ep || null, exact: Boolean(obj.x) };
  } catch {
    return null;
  }
}

function toQuery(params) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "") {
      search.set(key, value);
    }
  });
  return search.toString();
}

function normalizeMediaTypeLabel(mediaType, t) {
  return mediaType === "movie" ? t.typeMovie : t.typeTv;
}

/**
 * Providers sometimes list one stream under two labels, so a source is only
 * identified by label *and* URL together — comparing URLs alone highlighted
 * both rows as playing.
 */
function sourceKey(source) {
  return source ? `${source.sourceLabel}:${source.url}` : "";
}

function getSourcePlaybackMode(source, activeSource, playbackMode) {
  if (!source) return "";
  if (sourceKey(activeSource) === sourceKey(source)) return playbackMode || "direct";
  return "direct";
}

function formatSourceDuration(seconds, t) {
  const safe = Math.max(0, Math.floor(Number(seconds) || 0));
  if (!safe) return t.sourceDurationUnknown;
  const hours = Math.floor(safe / 3600);
  const minutes = Math.floor((safe % 3600) / 60);
  const secs = safe % 60;
  if (hours > 0) return `${hours}h ${String(minutes).padStart(2, "0")}m ${String(secs).padStart(2, "0")}s`;
  return `${minutes}m ${String(secs).padStart(2, "0")}s`;
}

async function fetchPreferredSourceLabel(providerKey, mediaType, title) {
  if (!providerKey || !title) return "";
  try {
    const payload = await apiJson(
      `/api/me/source-preference?${toQuery({ providerKey, mediaType, title })}`,
    );
    return payload.preference?.sourceLabel || "";
  } catch {
    return "";
  }
}

function withCurrentOrigin(url) {
  if (!url) return url;
  try {
    return new URL(url, window.location.origin).toString();
  } catch {
    return url;
  }
}

function normalizeSourceList(sources) {
  return (sources || []).map((source) => ({
    ...source,
    directUrl: withCurrentOrigin(source.directUrl || source.url),
    proxyUrl: withCurrentOrigin(
      source.proxyUrl
        ? `${source.proxyUrl}${source.proxyUrl.includes("?") ? "&" : "?"}accessToken=${encodeURIComponent(getAccessToken())}`
        : "",
    ),
  }));
}

function normalizeSourceItem(source) {
  return normalizeSourceList([source])[0];
}

function progressKey(seasonUrl, episodeLabel) {
  return `${seasonUrl || ""}::${episodeLabel || ""}`;
}

function buildProgressMap(entries) {
  const map = {};
  for (const entry of entries) {
    map[progressKey(entry.seasonUrl, entry.episodeLabel)] = entry;
  }
  return map;
}

function getSeasonStatus(seasonUrl, progressMap) {
  const entries = Object.values(progressMap).filter(
    (e) => (e.seasonUrl || "") === (seasonUrl || ""),
  );
  if (!entries.length) return "";
  if (entries.every((e) => e.isCompleted)) return "pill-completed";
  if (entries.some((e) => !e.isCompleted && (e.progressPercent || 0) > 0)) return "pill-in-progress";
  return "";
}

/**
 * The season to open when a title is picked.
 *
 * A title is opened from several places — a search result, a favourite, a row
 * of history — and only some of them carry a position. None of that should
 * decide where a person lands: what they watched last should, and it should be
 * the same answer whichever door they came through. So the season holding the
 * most recent progress wins, and `fallbackSeasonUrl` is consulted only when
 * nothing has been watched.
 *
 * That fallback is what a favourite remembers: the season that was on screen
 * when the heart was tapped. It is a reasonable guess for a title never watched
 * and simply wrong for one that has been, which is why it loses to progress.
 *
 * Mirrors `ResumeRules.resumeSeason` in android/core and ios/StreamHub/Core.
 */
function getResumeSeason(seasons, progressMap, fallbackSeasonUrl) {
  if (!seasons.length) return null;
  const latest = Object.values(progressMap)
    .filter((entry) => entry.seasonUrl)
    .sort((a, b) => new Date(b.lastWatchedAt) - new Date(a.lastWatchedAt))[0];
  // A season can go missing between watching it and coming back: providers
  // renumber and re-list. Falling through beats landing on nothing.
  return seasons.find((s) => s.url === latest?.seasonUrl)
    || seasons.find((s) => s.url === fallbackSeasonUrl)
    || seasons[0];
}

// Returns the episode label to resume, or null if the season is fully done.
function getResumeEpisode(episodes, seasonUrl, progressMap) {
  if (!episodes.length) return null;
  const entries = episodes
    .map((ep) => progressMap[progressKey(seasonUrl, ep)])
    .filter(Boolean)
    .sort((a, b) => new Date(b.lastWatchedAt) - new Date(a.lastWatchedAt));
  if (!entries.length) return episodes[0];
  const last = entries[0];
  if (last.isCompleted) {
    const idx = episodes.indexOf(last.episodeLabel);
    return episodes[idx + 1] ?? null;
  }
  return last.episodeLabel;
}

// Most sources for an episode are the same encode, so the most common runtime
// is the true content length. Sources that run longer are carrying ads the
// playlist filter could not identify (they serve ad segments from the same
// directory as the feature), and shorter ones are truncated rips.
const MIN_MODAL_AGREEMENT = 2;
const SOURCE_PICK_GRACE_MS = 2500;

function modalDuration(list) {
  const counts = new Map();
  for (const source of list) {
    const seconds = source.durationSeconds;
    if (Number.isFinite(seconds) && seconds > 0) counts.set(seconds, (counts.get(seconds) || 0) + 1);
  }
  let best = null;
  let bestCount = 0;
  for (const [seconds, count] of counts) {
    if (count > bestCount || (count === bestCount && best !== null && seconds < best)) {
      best = seconds;
      bestCount = count;
    }
  }
  return bestCount >= MIN_MODAL_AGREEMENT ? best : null;
}

function insertSourceSorted(prev, source) {
  const next = [...prev, source];
  const modal = modalDuration(next);
  return next.sort((a, b) => {
    if (modal) {
      const aOff = Math.abs((Number.isFinite(a.durationSeconds) ? a.durationSeconds : 0) - modal);
      const bOff = Math.abs((Number.isFinite(b.durationSeconds) ? b.durationSeconds : 0) - modal);
      if (aOff !== bOff) return aOff - bOff;
      // Same distance from the consensus: proven-clean ahead of presumed-clean.
      const aProven = (a.adSeconds || 0) > 0 ? 0 : 1;
      const bProven = (b.adSeconds || 0) > 0 ? 0 : 1;
      if (aProven !== bProven) return aProven - bProven;
    }
    const aDur = Number.isFinite(a.durationSeconds) ? a.durationSeconds : -1;
    const bDur = Number.isFinite(b.durationSeconds) ? b.durationSeconds : -1;
    return bDur - aDur;
  });
}

/**
 * `arrived` keeps stream order so ties resolve to whichever responded first.
 *
 * Matching the modal runtime is the primary gate: a source carrying ads the
 * filter cannot see runs longer than the consensus, and that holds even for
 * sources where *some* ads were detected — 5 of 18 sampled still sat 12-15s
 * above the modal after stripping, so `adSeconds > 0` alone is not a safe
 * signal. Within the modal group it does earn a tiebreak though: ads seen and
 * removed is proof of a clean result, where `adSeconds === 0` is only an
 * absence of evidence.
 */
function pickAutoSource(arrived, preferredLabel) {
  const modal = modalDuration(arrived);

  if (preferredLabel) {
    const preferred = arrived.find((source) => source.sourceLabel === preferredLabel);
    // Honour the saved choice unless it runs longer than the consensus, which
    // means it carries ads the filter cannot see. Picking it once should not
    // pin every later episode to an ad-laden source.
    if (preferred && (!modal || (preferred.durationSeconds ?? 0) <= modal)) {
      return { source: preferred, fromPreference: true };
    }
  }

  if (modal) {
    const onModal = arrived.filter((source) => source.durationSeconds === modal);
    if (onModal.length) {
      const proven = onModal.find((source) => (source.adSeconds || 0) > 0);
      return { source: proven || onModal[0], fromPreference: false };
    }
  }
  return { source: arrived[0] || null, fromPreference: false };
}

function posterProxyUrl(url) {
  if (!url) return "";
  return withCurrentOrigin(`/api/poster?target=${encodeURIComponent(url)}&accessToken=${encodeURIComponent(getAccessToken())}`);
}

/**
 * A poster that has no image draws one: the title's own initial on a gradient
 * derived from the title, so every fallback tile is stable for its title and
 * different from its neighbours'. Printing "No Image" made the gap the most
 * prominent text on the card.
 */
/**
 * Whether this document has already mounted the watch page once. The first
 * mount of a page load is a restore — a reload, a reopened tab, a Home Screen
 * app coming back — and restores must never send playback anywhere. Every
 * later mount can only be reached by navigating inside the app, which is a
 * person clicking a title.
 */
let appNavigatedOnce = false;

function PosterPlaceholder({ alt, className }) {
  const text = String(alt || "").trim();
  let hash = 0;
  for (let i = 0; i < text.length; i += 1) hash = (hash * 31 + text.charCodeAt(i)) >>> 0;
  const hue = hash % 360;
  return (
    <div
      className={`${className || ""} poster-placeholder`}
      aria-label={alt}
      style={{
        background: `linear-gradient(160deg, hsl(${hue} 42% 26%), hsl(${(hue + 45) % 360} 48% 13%))`,
      }}
    >
      <span>{text ? [...text][0].toUpperCase() : "▶"}</span>
    </div>
  );
}

function PosterImage({ src, alt, className, fallbackClassName }) {
  const [failed, setFailed] = useState(false);
  if (!src || failed) {
    return <PosterPlaceholder alt={alt} className={fallbackClassName ?? className} />;
  }
  return (
    <img
      src={posterProxyUrl(src)}
      alt={alt}
      className={className}
      loading="lazy"
      onError={() => setFailed(true)}
    />
  );
}

function App() {
  // The portal owns the language so the sidebar and the other pages follow the
  // switch rendered here; the local state is only the standalone fallback.
  const portalLanguage = usePortalLanguage();
  const [localLanguage, setLocalLanguage] = useState(resolveLanguage());
  const language = portalLanguage?.language ?? localLanguage;
  const setLanguage = portalLanguage?.setLanguage ?? setLocalLanguage;
  const t = translations[language];
  const [query, setQuery] = useState("");
  const [providerFilter, setProviderFilter] = useState("all");
  const [results, setResults] = useState([]);
  const [searching, setSearching] = useState(false);
  const [pendingSearchProviders, setPendingSearchProviders] = useState([]);
  const [error, setError] = useState("");
  const [selectedItem, setSelectedItem] = useState(null);
  const [itemDetail, setItemDetail] = useState(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [episodes, setEpisodes] = useState([]);
  const [selectedSeason, setSelectedSeason] = useState(null);
  const [selectedEpisode, setSelectedEpisode] = useState("");
  const [sources, setSources] = useState([]);
  const [sourcesLoading, setSourcesLoading] = useState(false);
  const [activeSource, setActiveSource] = useState(null);
  const cast = useCast();
  const castTargetId = cast.target?.sessionId || null;
  const castStateRef = useRef({});

  /**
   * Whether this page has been *asked* to play something on the television.
   *
   * "idle": connected at most as a remote — a restored page starts here, and
   * stays here however many sources auto-select. "armed": a gesture asked for
   * playback; the next committed source goes to the set. "sent": it went.
   * Being connected never implies armed — that conflation was the bug where
   * reopening the app hijacked whatever the television was playing.
   */
  const [castSendState, setCastSendState] = useState("idle");

  // The one place a play command leaves this page. Armed only by gestures —
  // tapping a source, an episode, a neighbour, a title, or the explicit
  // play-on-television button — and consumed by the send, so however many
  // times the page reloads or reattaches afterwards, the television is left
  // to what it was doing.
  useEffect(() => {
    if (!castTargetId || !activeSource || castSendState !== "armed") return;
    if (!sendToTelevision(activeSource)) return;
    setCastSendState("sent");

    // Handing the episode over stops this tab outright: hls.js is destroyed
    // rather than paused because a paused instance keeps filling its buffer,
    // and the element is emptied so nothing can resume it by accident.
    const video = videoRef.current;
    if (video) {
      video.onerror = null;
      video.pause();
      hlsRef.current?.destroy();
      hlsRef.current = null;
      video.removeAttribute("src");
      video.load();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [castTargetId, activeSource, castSendState]);

  // Losing or leaving the television clears the ask: whatever is chosen next
  // should be judged on its own gesture, not inherit an old one.
  useEffect(() => {
    if (!castTargetId) setCastSendState("idle");
  }, [castTargetId]);

  // Choosing where to play happens over the video, so the video waits for the
  // answer. Paused on the picker opening; resumed only when the picker closes
  // with nothing chosen — picking a television keeps it paused, because from
  // that moment the episode belongs to the television. Only a pause this
  // effect made is undone, so a picker opened over an already-paused video
  // does not start it by being dismissed.
  const pausedForPickerRef = useRef(false);
  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    if (cast.pickerOpen) {
      if (!video.paused) {
        pausedForPickerRef.current = true;
        video.pause();
      }
    } else if (pausedForPickerRef.current) {
      pausedForPickerRef.current = false;
      if (cast.target) {
        // The picker opened over a *playing* video and closed on a chosen
        // television: that is a hand-off, asked for as plainly as tapping a
        // source. A restored page never gets here — its video was already
        // paused when the picker opened, so nothing was interrupted and
        // nothing is sent.
        setCastSendState("armed");
      } else {
        void video.play().catch(() => {});
      }
    }
  }, [cast.pickerOpen, cast.target]);

  // Stop means stop. Both Stop and "Play here" leave this tab with no
  // television, and the player effect starts playing the moment there is none —
  // which is right for "Play here" and precisely wrong for Stop. Forgetting the
  // source is what tells the two apart.
  //
  // Adjusted during render rather than in an effect on purpose: an effect would
  // run in the same commit as the player's, which would already have started
  // loading a video that the next commit then tore down — audible as a stab of
  // sound from the laptop on the way to silence.
  const [lastCastStop, setLastCastStop] = useState(cast.stopped);
  if (cast.stopped !== lastCastStop) {
    setLastCastStop(cast.stopped);
    setActiveSource(null);
  }

  /**
   * Hand the source that just became active to the connected television.
   *
   * Everything it sends comes out of a ref, because its caller is the player
   * effect and that effect must run on the source changing and on nothing else.
   * Returns whether the command actually went out, so a closed socket can fall
   * back to playing here.
   */
  function sendToTelevision(source) {
    const { play, payload, resume, nextEpisodeLabel, prevEpisodeLabel, targetState } = castStateRef.current;
    if (!play || !payload || !source) return false;

    // Switching the source of the episode the television is already showing
    // continues from where the television *is*, not from the account's saved
    // progress — progress pings lag the picture by several seconds, and a
    // thirty-second courtesy rewind on top made every source switch feel
    // like a seek backwards. A different episode still resumes the saved way.
    const liveHandover = targetState?.itemUrl
      && targetState.itemUrl === payload.itemUrl
      && (targetState.episodeLabel || null) === (payload.episodeLabel || null)
      && targetState.positionMs > 0;

    return play({
      ...payload,
      sourceLabel: source.sourceLabel,
      directUrl: source.directUrl || source.url,
      // The same thirty-second rewind the local player applies, so handing a
      // title to a television lands where it would have landed here.
      resumeAtSeconds: liveHandover
        ? Math.max(0, targetState.positionMs / 1000 - 2)
        : resume?.isCompleted
          ? 0
          : Math.max(0, (resume?.positionSeconds || 0) - 30),
      nextEpisodeLabel,
      prevEpisodeLabel,
    });
  }

  const [playbackMode, setPlaybackMode] = useState("");
  // Which machinery is actually playing: hls.js over ManagedMediaSource
  // (iOS 17.1+), hls.js over MediaSource, or the native HLS stack. Shown
  // beside the mode chip so a report from a phone names its path.
  const [playbackEngine, setPlaybackEngine] = useState("");
  const [autoSelectedFromPreference, setAutoSelectedFromPreference] = useState(false);
  const [itemProgressMap, setItemProgressMap] = useState({});
  const [playerError, setPlayerError] = useState("");
  const [availableProviders, setAvailableProviders] = useState([]);
  const [favoriteEntries, setFavoriteEntries] = useState([]);
  const [resumeProgress, setResumeProgress] = useState(null);
  const [markBulkDialog, setMarkBulkDialog] = useState(null);
  const [nextEpPrompt, setNextEpPrompt] = useState(null);
  const [isPromptDismissed, setIsPromptDismissed] = useState(false);
  const [hlsInstance, setHlsInstance] = useState(null);
  const hlsRef = useRef(null);
  // Read by the fatal-error handler, which outlives the render that made it:
  // a reused instance keeps its first handler, and without this it would fall
  // back to the previous episode's proxy.
  const sourceUrlsRef = useRef({ directUrl: null, proxyUrl: null });
  const [adCuts, setAdCuts] = useState([]);
  const [download, setDownload] = useState(null);
  const downloadAbortRef = useRef(null);
  const videoRef = useRef(null);
  const tRef = useRef(t);
  const restoredFromUrlRef = useRef(false);
  const searchRequestIdRef = useRef(0);
  // What question the cached result groups answer, and what is fetched or in
  // flight — read by the provider filter, which tops results up rather than
  // re-asking for what is already on screen.
  const lastSearchQueryRef = useRef("");
  const resultsRef = useRef([]);
  const pendingSearchRef = useRef([]);
  const lastProgressSentRef = useRef(0);
  const sourcesAbortRef = useRef(null);
  useEffect(() => { tRef.current = t; }, [t]);

  resultsRef.current = results;
  pendingSearchRef.current = pendingSearchProviders;

  const groupedResults = useMemo(
    () => results.filter((group) => group.items.length > 0),
    [results],
  );
  const providerFilterOptions = useMemo(
    () => ["all", ...availableProviders.map((provider) => provider.key)],
    [availableProviders],
  );
  const visibleSearchGroups = useMemo(
    () => results.filter((group) =>
      (providerFilter === "all" || group.provider === providerFilter)
      && (group.items.length > 0 || pendingSearchProviders.includes(group.provider))),
    [results, pendingSearchProviders, providerFilter],
  );
  const currentPlaybackPayload = useMemo(() => {
    if (!selectedItem) return null;
    return {
      providerKey: selectedItem.provider,
      mediaType: itemDetail?.mediaType || selectedItem.mediaType || "unknown",
      title: selectedItem.title,
      posterUrl: selectedItem.posterUrl || "",
      itemUrl: selectedItem.url,
      detailUrl: itemDetail?.detailUrl || itemDetail?.seasonUrl || selectedItem.url,
      seasonUrl: selectedSeason?.url || itemDetail?.seasonUrl || null,
      seasonLabel: selectedSeason?.label || null,
      episodeLabel: selectedEpisode || null,
      sourceLabel: activeSource?.sourceLabel || null,
    };
  }, [selectedItem, itemDetail, selectedSeason, selectedEpisode, activeSource]);
  const currentEpIsCompleted = !!resumeProgress?.isCompleted;

  const isCurrentFavorite = useMemo(() => {
    if (!selectedItem) return false;
    return favoriteEntries.some((entry) => (
      entry.providerKey === selectedItem.provider
      && entry.itemUrl === selectedItem.url
      && (entry.seasonUrl || null) === (selectedSeason?.url || itemDetail?.seasonUrl || null)
      && (entry.episodeLabel || null) === (selectedEpisode || null)
    ));
  }, [favoriteEntries, selectedItem, selectedSeason, itemDetail, selectedEpisode]);

  // Destroying hls.js is what ends a Picture-in-Picture session: destroy()
  // detaches the media element, an emptied element closes the PiP window, and
  // the browser does not always say so. Doing it on every source change meant
  // continuing to the next episode killed the window while the control stayed
  // lit over nothing. It is torn down here instead — once, when the page goes.
  useEffect(() => () => {
    hlsRef.current?.destroy();
    hlsRef.current = null;
  }, []);

  useEffect(() => {
    const video = videoRef.current;
    if (!video || (!activeSource?.directUrl && !activeSource?.proxyUrl)) {
      return undefined;
    }

    // Connected to a television, nothing loads here — playback either went to
    // the set (the effect below) or nobody asked for any, and both cases mean
    // this element stays dark. The sending itself lives in its own effect,
    // keyed on whether a gesture armed it: a restored page auto-selecting a
    // source is not a request to play anything anywhere.
    //
    // A *lost* television holds the same silence. The target is derived from
    // the live receiver list, so a blip of this tab's own socket — not the
    // television's — used to null it for a moment, and this effect answered
    // by blasting the episode out of the local speakers. Only the person
    // choosing to play here (which forgets the device) moves playback back.
    if (castTargetId || cast.lost) {
      return undefined;
    }

    setPlayerError("");
    setPlaybackMode("");
    setAdCuts([]);
    setPlaybackEngine(
      Hls.isSupported()
        ? (typeof ManagedMediaSource !== "undefined" && typeof MediaSource === "undefined" ? "MMS" : "MSE")
        : "native",
    );

    const directUrl = activeSource.directUrl || activeSource.url;
    const proxyUrl = activeSource.proxyUrl;
    sourceUrlsRef.current = { directUrl, proxyUrl };

    function setMode(mode) { setPlaybackMode(mode); }

    function loadNative(url, mode) {
      setMode(mode);
      setHlsInstance(null);
      video.src = url;
      void video.play().catch(() => {});
    }

    function loadWithHls(url, mode, onFatalError) {
      // Buffer the whole film, not the next thirty seconds. These sources
      // come off scraped CDNs that stall and vanish mid-episode, and every
      // buffered minute is a minute those failures cannot touch — so the
      // target is the full runtime and the real limit is memory. The byte
      // cap scales with what the device reports (Chrome caps the report at
      // 8 GB); past it, hls.js's own quota handling pauses filling until
      // the playhead frees room, so an over-ask degrades into exactly the
      // old behaviour rather than an error.
      const memoryGb = navigator.deviceMemory || 4;
      const hls = new Hls({
        // Strips spliced ad segments out of each media playlist before
        // hls.js parses it, so they are never fetched and never reach the
        // timeline.
        pLoader: createAdFilterLoader(Hls, (result) => setAdCuts(result.cuts)),
        maxBufferLength: 4 * 3600,
        maxMaxBufferLength: 4 * 3600,
        maxBufferSize: Math.min(memoryGb * 150, 1500) * 1_000_000,
        // What has played stays buffered: rewinding inside a film should
        // never refetch, and the quota handler above reclaims it first when
        // memory actually runs short.
        backBufferLength: Infinity,
      });
      setMode(mode);
      setHlsInstance(hls);
      hlsRef.current = hls;
      hls.loadSource(url);
      hls.attachMedia(video);
      hls.on(Hls.Events.MANIFEST_PARSED, () => {
        void video.play().catch(() => {});
      });
      // Where a break was is known twice over: as a sum of #EXTINF values, and
      // as the index of the segment that follows it. Once hls.js has the level
      // it can be asked where that segment actually starts, and its answer is
      // the timeline the seek bar is drawn against — the sum is not, from the
      // first discontinuity onwards, because after one of those the player
      // times the rest from the media's own timestamps. Re-anchoring here is
      // what stops a mark sitting a little further off the longer an episode
      // runs.
      hls.on(Hls.Events.LEVEL_LOADED, (_event, data) => {
        const fragments = data?.details?.fragments;
        if (!fragments?.length) return;
        setAdCuts((cuts) => cuts.map((cut) => {
          const fragment = fragments[cut.segment];
          return fragment && Number.isFinite(fragment.start)
            ? { ...cut, at: fragment.start }
            : cut;
        }));
      });
      hls.on(Hls.Events.ERROR, (_event, data) => {
        if (data.fatal) onFatalError?.(hls, data);
      });
      return hls;
    }

    // What actually broke, spelled out beside the fallback notice. An iPhone
    // has no console: this line on the screen is the only way a failure in
    // the field can name itself.
    function describeHlsError(data) {
      const url = data?.frag?.url || data?.context?.url || "";
      let host = "";
      try { host = url ? new URL(url).host : ""; } catch { /* not a URL */ }
      return [
        [data?.type, data?.details].filter(Boolean).join("/"),
        data?.response?.code ? `HTTP ${data.response.code}` : "",
        host,
      ].filter(Boolean).join(" · ");
    }

    // hls.js first: Chrome/Edge on macOS answer "maybe" to canPlayType for
    // HLS but cannot actually demux it, so trusting the native check strands
    // playback on DEMUXER_ERROR_COULD_NOT_PARSE. Native is the fallback for
    // engines without MSE (notably iOS Safari), which really do play HLS.
    if (Hls.isSupported()) {
      const existing = hlsRef.current;

      // Still attached to this element: point it at the new stream rather than
      // replacing it. loadSource keeps the media attached, so anything holding
      // on to the element — a Picture-in-Picture window above all — survives
      // the change of episode.
      if (existing && existing.media === video) {
        setMode(directUrl ? "direct" : "proxy");
        existing.loadSource(directUrl || proxyUrl);
        void video.play().catch(() => {});
        return undefined;
      }

      // Attached to an element that has since gone — leaving the watch page
      // and coming back to another title. Nothing is holding it now.
      if (existing) {
        existing.destroy();
        hlsRef.current = null;
      }

      loadWithHls(directUrl || proxyUrl, directUrl ? "direct" : "proxy", (instance, data) => {
        const urls = sourceUrlsRef.current;
        instance.destroy();
        hlsRef.current = null;
        const detail = describeHlsError(data);
        if (urls.proxyUrl && urls.directUrl && urls.directUrl !== urls.proxyUrl) {
          setPlayerError(detail ? `${tRef.current.playbackFallback} — ${detail}` : tRef.current.playbackFallback);
          loadWithHls(urls.proxyUrl, "proxy", (proxyInstance, proxyData) => {
            const proxyDetail = describeHlsError(proxyData);
            setPlayerError(proxyDetail ? `${tRef.current.statusError} — ${proxyDetail}` : tRef.current.statusError);
            proxyInstance.destroy();
            hlsRef.current = null;
          });
        } else {
          setPlayerError(detail ? `${tRef.current.statusError} — ${detail}` : tRef.current.statusError);
        }
      });
      return undefined;
    }

    if (video.canPlayType("application/vnd.apple.mpegurl") && directUrl) {
      // The one path with no hls.js and therefore no ad filter. Fed the raw
      // playlist, an iPhone was the only client that actually played the
      // spliced ad runs — and their segments live on hosts a phone's network
      // often cannot reach, so direct playback died mid-ad while the proxy,
      // whose fetches leave from the server, sailed. The cleaned manifest
      // removes those runs server-side; the segment URLs stay the CDN's own,
      // so the server serves a playlist, not the film.
      const cleaned = withCurrentOrigin(
        `/api/manifest?target=${encodeURIComponent(directUrl)}&accessToken=${encodeURIComponent(getAccessToken())}`,
      );
      loadNative(cleaned, "direct");
      video.onerror = () => {
        const err = video.error;
        const detail = err ? `MediaError ${err.code}${err.message ? ` ${err.message}` : ""}` : "";
        if (proxyUrl && video.src !== proxyUrl) {
          setPlayerError(detail ? `${tRef.current.playbackFallback} — ${detail}` : tRef.current.playbackFallback);
          loadNative(proxyUrl, "proxy");
        } else {
          setPlayerError(detail ? `${tRef.current.statusError} — ${detail}` : tRef.current.statusError);
        }
      };
      return () => { video.onerror = null; };
    }

    setPlayerError(t.statusError);
    return undefined;
    // The session id, not the device object: that object is rebuilt from the
    // receiver list every time the server republishes it, and an effect that
    // now sends a play command must not re-fire on a list that has not changed.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeSource, castTargetId, cast.lost]);

  useEffect(() => {
    let cancelled = false;

    async function loadProviders() {
      try {
        const payload = await apiJson("/api/me/providers");
        if (cancelled) return;
        const providers = payload.providers || [];
        setAvailableProviders(providers);
        setProviderFilter((current) => (
          current !== "all" && !providers.some((provider) => provider.key === current)
            ? "all"
            : current
        ));
      } catch {
        if (!cancelled) {
          setAvailableProviders([]);
        }
      }
    }

    loadProviders();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!currentPlaybackPayload) {
      setResumeProgress(null);
      return;
    }

    let cancelled = false;
    async function loadUserState() {
      try {
        const [favoritesData, progressData] = await Promise.all([
          apiJson("/api/me/favorites"),
          apiJson("/api/me/progress"),
        ]);
        if (cancelled) return;
        setFavoriteEntries(favoritesData.favorites || []);
        const matchingProgress = (progressData.progress || []).find((entry) => (
          entry.providerKey === currentPlaybackPayload.providerKey
          && entry.itemUrl === currentPlaybackPayload.itemUrl
          && (entry.seasonUrl || null) === (currentPlaybackPayload.seasonUrl || null)
          && (entry.episodeLabel || null) === (currentPlaybackPayload.episodeLabel || null)
        ));
        setResumeProgress(matchingProgress || null);
      } catch {
        if (!cancelled) {
          setResumeProgress(null);
        }
      }
    }

    loadUserState();
    return () => {
      cancelled = true;
    };
  }, [currentPlaybackPayload]);

  // Another tab (or device) may star this title or clear its progress; keep the
  // heart and the resume point in step without a reload.
  useEffect(() => subscribeRealtime((event) => {
    if (event.type !== "favorites" && event.type !== "progress") return;
    apiJson("/api/me/favorites")
      .then((data) => setFavoriteEntries(data.favorites || []))
      .catch(() => {});
    if (selectedItem) {
      fetchItemProgress(selectedItem.provider, selectedItem.url)
        .then(setItemProgressMap)
        .catch(() => {});
    }
  }), [selectedItem]);

  useEffect(() => {
    const video = videoRef.current;
    if (!video || !resumeProgress) return undefined;

    const resumeAt = Math.max(0, (resumeProgress.positionSeconds || 0) - 30);
    if (resumeAt <= 0) return undefined;

    function doSeek() {
      if (Math.abs(video.currentTime - resumeAt) > 5) {
        video.currentTime = resumeAt;
      }
    }

    if (video.readyState >= 1) {
      doSeek();
      return undefined;
    }
    video.addEventListener("loadedmetadata", doSeek, { once: true });
    return () => video.removeEventListener("loadedmetadata", doSeek);
  }, [resumeProgress, activeSource]);

  useEffect(() => {
    const video = videoRef.current;
    if (!video || !currentPlaybackPayload || !activeSource) {
      return undefined;
    }

    async function syncProgress(event = "progress") {
      if (!video.duration || Number.isNaN(video.duration)) return;
      try {
        await apiJson("/api/me/progress", {
          method: "PUT",
          body: JSON.stringify({
            ...currentPlaybackPayload,
            durationSeconds: Math.floor(video.duration || 0),
            positionSeconds: Math.floor(video.currentTime || 0),
            sourceLabel: activeSource?.sourceLabel || currentPlaybackPayload.sourceLabel || null,
            event,
          }),
        });
      } catch {}
    }

    function handleTimeUpdate() {
      const now = Date.now();
      const timeLeft = video.duration - video.currentTime;

      // Auto-play trigger logic (120s)
      if (itemDetail?.mediaType === "tv" && video.duration > 0) {
        if (timeLeft < 120 && !nextEpPrompt && !isPromptDismissed) {
          const currentEpIdx = episodes.indexOf(selectedEpisode);
          if (currentEpIdx !== -1) {
            let nextEp = null;
            let nextSeason = null;

            if (currentEpIdx < episodes.length - 1) {
              nextEp = episodes[currentEpIdx + 1];
            } else if (itemDetail.provider === "movieffm" && Array.isArray(itemDetail.seasons)) {
              const currentSeasonIdx = itemDetail.seasons.findIndex((s) => s.url === selectedSeason?.url);
              if (currentSeasonIdx !== -1 && currentSeasonIdx < itemDetail.seasons.length - 1) {
                nextSeason = itemDetail.seasons[currentSeasonIdx + 1];
              }
            }

            if (nextEp || nextSeason) {
              setNextEpPrompt({
                episode: nextEp || null,
                season: nextSeason || null,
                countdown: 120,
              });
            }
          }
        } else if (timeLeft >= 120 && nextEpPrompt) {
          // Reset if user seeks back
          setNextEpPrompt(null);
        }
      }

      if (now - lastProgressSentRef.current < 15_000) return;
      lastProgressSentRef.current = now;
      void syncProgress("progress");
    }

    function handlePause() {
      void syncProgress("pause");
    }

    function handleEnded() {
      void syncProgress("ended");
      // If we are at the end and no prompt is shown (e.g. video shorter than 60s or trigger missed),
      // we can trigger immediately or just let the prompt handle it. 
      // User said trigger "结尾前几秒", so we stick to the prompt.
    }

    function handleBeforeUnload() {
      void syncProgress("pause");
    }

    video.addEventListener("timeupdate", handleTimeUpdate);
    video.addEventListener("pause", handlePause);
    video.addEventListener("ended", handleEnded);
    window.addEventListener("beforeunload", handleBeforeUnload);

    return () => {
      void syncProgress("switch");
      video.removeEventListener("timeupdate", handleTimeUpdate);
      video.removeEventListener("pause", handlePause);
      video.removeEventListener("ended", handleEnded);
      window.removeEventListener("beforeunload", handleBeforeUnload);
    };
  }, [currentPlaybackPayload, activeSource, itemDetail, episodes, selectedEpisode, selectedSeason, nextEpPrompt]);

  /** The same identity rule as the phone app: one download per episode-and-source. */
  const downloadIdentityFor = useCallback(async () => {
    if (!selectedItem || !activeSource) return null;
    return downloadIdentity({
      providerKey: selectedItem.provider,
      itemUrl: selectedItem.url,
      seasonUrl: selectedSeason?.url || itemDetail?.seasonUrl || null,
      episodeLabel: selectedEpisode || null,
      sourceLabel: activeSource.sourceLabel || null,
    });
  }, [selectedItem, activeSource, selectedSeason, itemDetail, selectedEpisode]);

  const handleDownload = useCallback(async () => {
    if (download?.active) {
      // Stopping keeps every segment already fetched; the button turns into
      // "resume from N%". Losing 80% to a mis-tap is the old behaviour, and
      // it is the one thing this feature exists to not do.
      downloadAbortRef.current?.abort();
      return;
    }
    if (!activeSource) return;

    const url = activeSource.directUrl || activeSource.url || activeSource.proxyUrl;
    if (!url) return;

    const id = await downloadIdentityFor();
    if (!id) return;

    // A finished download whose automatic save was swallowed: this click is a
    // real gesture, so save it now.
    if (download?.finished) {
      const saved = await saveFinishedDownload(id);
      if (saved) setDownload({ active: false, percent: 100, label: t.dlDone });
      return;
    }

    const controller = new AbortController();
    downloadAbortRef.current = controller;
    setDownload({ active: true, percent: download?.partial ?? 0, label: t.dlPreparing });

    const name = [
      itemDetail?.title || selectedItem?.title,
      selectedEpisode,
      activeSource.sourceLabel,
    ].filter(Boolean).join(" ");

    let lastPercent = download?.partial ?? 0;
    try {
      const result = await downloadStream({
        id,
        url,
        fileName: name,
        signal: controller.signal,
        onProgress: ({ phase, done, total, bytes }) => {
          if (phase === "playlist") {
            setDownload({ active: true, percent: 0, label: t.dlPreparing });
            return;
          }
          if (phase === "assembling") {
            setDownload({ active: true, percent: 100, label: t.dlAssembling });
            return;
          }
          const percent = total ? Math.round((done / total) * 100) : 0;
          lastPercent = percent;
          setDownload({
            active: phase !== "done",
            percent,
            label: `${percent}% · ${(bytes / 1048576).toFixed(0)} MB`,
          });
        },
      });
      setDownload({
        active: false,
        percent: 100,
        label: result.removedSeconds
          ? t.dlDoneNoAds.replace("{s}", Math.round(result.removedSeconds))
          : t.dlDone,
      });
    } catch (error) {
      if (error?.name === "AbortError") {
        // Not a failure and not gone: the partial sits in storage, and saying
        // so beside the resume percentage is the promise that it does.
        setDownload({ active: false, percent: lastPercent, partial: lastPercent,
          label: t.dlPaused.replace("{p}", lastPercent) });
      } else {
        // Never trust the shape of what was thrown: Safari's IndexedDB has
        // rejected with a literal null, and reading .message off it here
        // crashed the very handler that would have shown the failure —
        // leaving the button stuck on "Preparing…" forever.
        setDownload({ active: false, percent: 0, partial: lastPercent,
          error: error?.message || String(error) || "Download failed" });
      }
    } finally {
      downloadAbortRef.current = null;
    }
  }, [download, activeSource, itemDetail, selectedItem, selectedEpisode, downloadIdentityFor, t]);

  // Switching source or episode stops the transfer but keeps its storage, then
  // asks what is already on disk for the new one — so coming back to a
  // half-downloaded source greets you with "resume from N%", including after a
  // full page reload.
  useEffect(() => {
    downloadAbortRef.current?.abort();
    setDownload(null);
    let stale = false;
    (async () => {
      const id = await downloadIdentityFor();
      if (!id || stale) return;
      const partial = await partialDownload(id);
      if (stale || !partial) return;
      setDownload(partial.finished
        ? { active: false, percent: 100, finished: true, label: t.dlSaveAgain }
        : { active: false, percent: partial.percent, partial: partial.percent,
            label: t.dlPaused.replace("{p}", partial.percent) });
    })();
    return () => { stale = true; };
  }, [activeSource, downloadIdentityFor, t]);

  // Builds the scrub-preview stream. Kept here because App owns hls.js and the
  // ad filter — the preview must use the *same* filter or its timeline would be
  // offset from the main one by the stripped ad seconds.
  const createPreview = useCallback((video) => {
    if (!activeSource) return null;
    const directUrl = activeSource.directUrl || activeSource.url;
    const proxyUrl = activeSource.proxyUrl;
    const url = playbackMode === "proxy" ? (proxyUrl || directUrl) : (directUrl || proxyUrl);
    if (!url) return null;

    if (Hls.isSupported()) {
      const hls = new Hls({
        pLoader: createAdFilterLoader(Hls, () => {}),
        // Preview only ever seeks, so keep it from buffering ahead.
        maxBufferLength: 4,
        maxMaxBufferLength: 6,
        backBufferLength: 0,
        startLevel: 0,
      });
      hls.loadSource(url);
      hls.attachMedia(video);
      return () => { try { hls.destroy(); } catch { /* already destroyed */ } };
    }

    if (video.canPlayType("application/vnd.apple.mpegurl")) {
      video.src = url;
      return () => { video.removeAttribute("src"); video.load(); };
    }
    return null;
  }, [activeSource, playbackMode]);

  const episodeNeighbours = useMemo(() => {
    if (itemDetail?.mediaType !== "tv" || !episodes.length) return { prev: null, next: null };
    const index = episodes.indexOf(selectedEpisode);
    if (index === -1) return { prev: null, next: null };

    const prev = index > 0 ? { episode: episodes[index - 1], season: null, label: episodes[index - 1] } : null;
    if (index < episodes.length - 1) {
      const label = episodes[index + 1];
      return { prev, next: { episode: label, season: null, label } };
    }

    // Last episode of a movieffm season rolls over into the next season.
    if (itemDetail.provider === "movieffm" && Array.isArray(itemDetail.seasons)) {
      const seasonIndex = itemDetail.seasons.findIndex((season) => season.url === selectedSeason?.url);
      const nextSeason = itemDetail.seasons[seasonIndex + 1];
      if (seasonIndex !== -1 && nextSeason) {
        return { prev, next: { episode: null, season: nextSeason, label: nextSeason.label } };
      }
    }
    return { prev, next: null };
  }, [itemDetail, episodes, selectedEpisode, selectedSeason]);

  // What the television needs to be told, kept where the player effect can read
  // it without depending on it. Naming these as dependencies would restart the
  // set every time a progress ping came back with a new record.
  castStateRef.current = {
    play: cast.play,
    payload: currentPlaybackPayload,
    resume: resumeProgress,
    nextEpisodeLabel: episodeNeighbours?.next?.label || null,
    prevEpisodeLabel: episodeNeighbours?.prev?.label || null,
    // What the receiver last said it was doing — a source switch mid-episode
    // reads its position from here so the picture carries straight on.
    targetState: cast.target?.state ?? null,
  };

  // ── the receiver role: this tab can be driven like a television ──────────
  // Who is holding this tab's remote right now, for the on-screen badge. A
  // controller that goes quiet for a while is no longer visibly in charge.
  const [controlledBy, setControlledBy] = useState(null);
  const controlledByRef = useRef(null);
  controlledByRef.current = controlledBy;
  useEffect(() => {
    if (!controlledBy) return undefined;
    const timer = window.setTimeout(() => setControlledBy(null), 12_000);
    return () => window.clearTimeout(timer);
  }, [controlledBy]);

  // Confirmation that "stop remote control" took, shown briefly where the
  // fullscreen hint appears. The action itself is silent — the badge just
  // disappears — and silence after a press reads as a broken button.
  const [detachedNote, setDetachedNote] = useState(false);
  useEffect(() => {
    if (!detachedNote) return undefined;
    const timer = window.setTimeout(() => setDetachedNote(false), 6_000);
    return () => window.clearTimeout(timer);
  }, [detachedNote]);
  const handleDetach = () => {
    detachReceiver();
    setControlledBy(null);
    setImmersive(false);
    setDetachedNote(true);
  };

  /**
   * Whether this tab is showing the handed-over video edge to edge.
   *
   * A title arriving from another device means someone across the room chose
   * this screen to *watch on* — a video playing inside the page layout, rail
   * and topbar and all, is not that. Entered automatically on a remote play
   * and toggleable from the remote's fullscreen button.
   *
   * The viewport layout is the guaranteed floor; *browser* fullscreen sits on
   * top of it, taken wherever the rules allow. A remote command is not a
   * local gesture, and without one a browser refuses requestFullscreen — so
   * entering immersive merely *attempts* it (television browsers and webviews
   * are often lenient), and failing that, the first tap anywhere on the
   * immersive screen is spent on finishing the job rather than on whatever it
   * landed on. Leaving needs no gesture at all, so the remote's toggle-off
   * always collapses browser fullscreen too.
   */
  const [immersive, setImmersive] = useState(false);
  const playerCardRef = useRef(null);
  // A remote play tears the old source down before the new one is up, and the
  // moment of no-source in between must not read as "playback over" — that
  // was exactly the moment the immersive layout used to be torn down when a
  // hand-over landed on a page already playing something else.
  const remotePlayPendingRef = useRef(false);
  // Whether the browser itself is fullscreen — read from the document, since
  // the person can enter and leave it (Esc) without going through us.
  const [realFullscreen, setRealFullscreen] = useState(false);
  useEffect(() => {
    const onChange = () => setRealFullscreen(Boolean(document.fullscreenElement));
    document.addEventListener("fullscreenchange", onChange);
    document.addEventListener("webkitfullscreenchange", onChange);
    return () => {
      document.removeEventListener("fullscreenchange", onChange);
      document.removeEventListener("webkitfullscreenchange", onChange);
    };
  }, []);
  // The upgrade tap has to be discovered somehow: the hint names it for a few
  // seconds each time immersive comes up, and vanishes for good the moment
  // browser fullscreen actually lands.
  const [fsHint, setFsHint] = useState(false);
  useEffect(() => {
    if (!immersive || realFullscreen) {
      setFsHint(false);
      return undefined;
    }
    if (!(document.fullscreenEnabled || document.webkitFullscreenEnabled)) return undefined;
    setFsHint(true);
    const timer = window.setTimeout(() => setFsHint(false), 8_000);
    return () => window.clearTimeout(timer);
  }, [immersive, realFullscreen]);
  useEffect(() => {
    if (activeSource) {
      remotePlayPendingRef.current = false;
      return;
    }
    if (!remotePlayPendingRef.current) setImmersive(false);
  }, [activeSource]);
  useEffect(() => {
    if (!immersive) {
      if (document.fullscreenElement) document.exitFullscreen?.().catch(() => {});
      return undefined;
    }
    document.body.classList.add("has-immersive-player");

    const tryBrowserFullscreen = () => {
      const el = playerCardRef.current;
      if (!el || document.fullscreenElement) return;
      try {
        const attempt = el.requestFullscreen?.() ?? el.webkitRequestFullscreen?.();
        attempt?.catch?.(() => { /* no gesture yet — the first tap will finish it */ });
      } catch { /* same */ }
    };
    tryBrowserFullscreen();

    // The upgrade tap. Captured at the document so the player never sees it:
    // a tap spent on entering fullscreen must not also pause the film. Taps
    // on real controls are left alone — reaching for the exit button has to
    // press the exit button, not vanish into a fullscreen request. Only wired
    // where element fullscreen exists at all (an iPhone has none), or every
    // tap on the picture would be eaten for nothing.
    const canFullscreen = document.fullscreenEnabled || document.webkitFullscreenEnabled;
    const upgrade = (event) => {
      if (document.fullscreenElement) return;
      if (event.target.closest?.("button, input, a, select, [role=button]")) return;
      event.stopPropagation();
      event.preventDefault?.();
      tryBrowserFullscreen();
    };
    if (canFullscreen) document.addEventListener("click", upgrade, true);

    // A television remote's OK button arrives as a keydown, not a click —
    // Enter, on every TV browser seen so far — so the same upgrade rides it.
    // Only Enter: the player's own shortcuts (K, F, space…) must keep working
    // while immersive, and Enter is the one key the player does not use.
    const upgradeKey = (event) => {
      if (event.key !== "Enter" || document.fullscreenElement) return;
      if (event.target.closest?.("button, input, a, select, [role=button]")) return;
      upgrade(event);
    };
    if (canFullscreen) document.addEventListener("keydown", upgradeKey, true);

    const onKey = (event) => { if (event.key === "Escape") setImmersive(false); };
    window.addEventListener("keydown", onKey);
    return () => {
      document.body.classList.remove("has-immersive-player");
      if (canFullscreen) {
        document.removeEventListener("click", upgrade, true);
        document.removeEventListener("keydown", upgradeKey, true);
      }
      window.removeEventListener("keydown", onKey);
      if (document.fullscreenElement) document.exitFullscreen?.().catch(() => {});
    };
  }, [immersive]);

  // Transitions are announced the moment they happen, not on the heartbeat:
  // the person holding the remote pressed the button and is watching *their*
  // screen for the answer. The heartbeat stays as the position ticker.
  useEffect(() => {
    const video = videoRef.current;
    if (!video || !activeSource || castTargetId || cast.lost) return undefined;
    const names = ["play", "playing", "pause", "seeked", "waiting", "ended", "durationchange", "loadedmetadata"];
    const onTransition = () => announceNow();
    for (const name of names) video.addEventListener(name, onTransition);
    return () => {
      for (const name of names) video.removeEventListener(name, onTransition);
    };
  }, [activeSource, castTargetId, cast.lost]);

  const receiverHandlersRef = useRef({});
  receiverHandlersRef.current = {
    goToNeighbour,
    handleSelectItem,
    neighbours: episodeNeighbours,
    sourceLabel: activeSource?.sourceLabel || null,
  };

  // Another device handed a title to this browser, the way a phone hands one
  // to a television. Applied in-app rather than by reloading, so the socket —
  // and with it this receiver role — stays up. If the browser's autoplay
  // policy refuses the un-gestured start, the page is left on the episode
  // with its play button up, which is as far as a web page is allowed to go.
  const applyPlayRequest = (playback) => {
    if (!playback?.itemUrl || !playback?.provider) return;
    // A page holding some other device's remote is still a valid target:
    // being told to play *here* means putting that remote down first —
    // walking away, not stopping what the other device is showing. Without
    // this, the player effect defers to the held target and the command
    // lands as a detail page with nothing playing.
    if (castTargetId) cast.disconnect();
    // Chosen from across the room as the screen to watch on: fill it.
    remotePlayPendingRef.current = true;
    setImmersive(true);
    void receiverHandlersRef.current.handleSelectItem(
      {
        url: playback.itemUrl,
        provider: playback.provider,
        title: playback.title || "",
        mediaType: "unknown",
        posterUrl: playback.posterUrl || "",
      },
      playback.episodeUrl || null,
      playback.episodeLabel || null,
      true,
      // Not a gesture of this page's user: must not arm a cast send, or a
      // browser that happens to hold a television's remote would forward the
      // title onward instead of playing it.
      false,
    );
  };
  const applyPlayRef = useRef(applyPlayRequest);
  applyPlayRef.current = applyPlayRequest;

  // A play that arrived while this page was not mounted: the portal shell
  // accepted it, stashed it, and navigated here. Honour it now.
  useEffect(() => {
    const pending = consumePlayRequest();
    if (pending?.playback) {
      applyPlayRef.current(pending.playback);
      setControlledBy(pending.fromName || null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useBrowserReceiver({
    // Holding a remote — even one whose device just blinked off the list —
    // means nothing is playing *here*, and announcing this tab's empty video
    // element as a receiver would put a phantom device in every picker.
    active: Boolean(activeSource) && !castTargetId && !cast.lost,
    getState: () => {
      const video = videoRef.current;
      const payload = castStateRef.current.payload;
      const handlers = receiverHandlersRef.current;
      if (!video || !payload) return null;
      return {
        provider: payload.providerKey,
        itemUrl: payload.itemUrl,
        title: payload.title,
        subtitle: handlers.sourceLabel,
        posterUrl: payload.posterUrl,
        episodeLabel: payload.episodeLabel,
        positionMs: Math.round((video.currentTime || 0) * 1000),
        durationMs: Math.round((Number.isFinite(video.duration) ? video.duration : 0) * 1000),
        paused: video.paused,
        buffering: video.readyState < 3 && !video.paused,
        hasNext: Boolean(handlers.neighbours?.next),
        hasPrevious: Boolean(handlers.neighbours?.prev),
        // Who is driving, so a second remote holding this device can see the
        // first one's hand on it. Follows the badge's own 12-second fade.
        controlledBy: controlledByRef.current,
      };
    },
    onCommand: (command, fromName) => {
      // Being driven should be visible on the driven screen: the badge names
      // whoever is holding the remote, and fades once they go quiet.
      setControlledBy(fromName || null);
      const video = videoRef.current;
      const handlers = receiverHandlersRef.current;
      switch (command.action) {
        case "pause":
          video?.pause();
          break;
        case "resume":
          void video?.play().catch(() => {});
          break;
        case "seek":
          if (video) video.currentTime = Math.max(0, (command.positionMs || 0) / 1000);
          break;
        case "next":
          if (handlers.neighbours?.next) void handlers.goToNeighbour(handlers.neighbours.next);
          break;
        case "previous":
          if (handlers.neighbours?.prev) void handlers.goToNeighbour(handlers.neighbours.prev);
          break;
        case "stop": {
          if (video) {
            video.onerror = null;
            video.pause();
            hlsRef.current?.destroy();
            hlsRef.current = null;
            video.removeAttribute("src");
            video.load();
          }
          setActiveSource(null);
          remotePlayPendingRef.current = false;
          setImmersive(false);
          break;
        }
        case "fullscreen":
          setImmersive((value) => !value);
          break;
        case "play":
          applyPlayRef.current(command.playback || {});
          break;
        default:
          break;
      }
    },
  });

  // ── watch-panel rows ────────────────────────────────────────
  // WatchPanels stays presentational, so the progress bookkeeping and the two
  // provider-specific TV shapes are resolved into plain rows here.
  const seasonOptions = useMemo(() => {
    const seasons = itemDetail?.mediaType === "tv" && itemDetail?.provider === "movieffm"
      ? itemDetail.seasons || []
      : [];
    return seasons.map((season) => {
      const status = getSeasonStatus(season.url, itemProgressMap);
      const mark = status === "pill-completed" ? "✓ " : status === "pill-in-progress" ? "▸ " : "";
      return { url: season.url, label: season.label, optionLabel: `${mark}${season.label}` };
    });
  }, [itemDetail, itemProgressMap]);

  const activeSeasonUrl = itemDetail?.provider === "movieffm"
    ? (selectedSeason?.url || itemDetail?.seasonUrl || null)
    : (itemDetail?.seasonUrl || null);

  const episodeRows = useMemo(() => {
    if (itemDetail?.mediaType !== "tv") return [];
    return episodes.map((label) => {
      const progress = itemProgressMap[progressKey(activeSeasonUrl, label)];
      const remaining = progress && !progress.isCompleted
        ? Math.max(0, (progress.durationSeconds || 0) - (progress.positionSeconds || 0))
        : 0;
      return {
        label,
        // Only worth showing once there is more than a minute left to resume.
        title: remaining > 60
          ? t.timeLeft.replace("{t}", t.minutesShort.replace("{n}", Math.round(remaining / 60)))
          : "",
        isActive: selectedEpisode === label,
        isCompleted: !!progress?.isCompleted,
        percent: progress?.isCompleted ? 100 : (progress?.progressPercent || 0),
      };
    });
  }, [itemDetail, episodes, activeSeasonUrl, selectedEpisode, itemProgressMap, t]);

  const sourceRows = useMemo(() => sources.map((source) => ({
    key: sourceKey(source),
    source,
    label: source.sourceLabel,
    duration: formatSourceDuration(source.durationSeconds, t),
    mode: getSourcePlaybackMode(source, activeSource, playbackMode),
    // Ads found in this source's playlist, which the filter strips before it
    // reaches the player. It is a property of the source, so it belongs on the
    // row: it is one of the two things worth knowing when picking between
    // twenty of them, and it was only ever shown for the one already playing —
    // which is too late to be a reason to choose it.
    adSeconds: source.adSeconds || 0,
  })), [sources, activeSource, playbackMode, t]);

  const showRail = itemDetail?.mediaType === "tv" && (episodeRows.length > 0 || seasonOptions.length > 0);

  function handleSelectEpisodeLabel(label) {
    setCastSendState("armed");
    if (!itemDetail) return;
    loadEpisodeSources(
      itemDetail.provider,
      itemDetail.provider === "777tv" ? itemDetail.detailUrl : (selectedSeason?.url || itemDetail.seasonUrl),
      label,
      itemDetail.title,
      itemDetail.mediaType,
    );
  }

  async function goToNeighbour(target) {
    if (!target || !itemDetail) return;
    setCastSendState("armed");
    if (target.episode) {
      await loadEpisodeSources(
        itemDetail.provider,
        selectedSeason?.url || itemDetail.seasonUrl || itemDetail.detailUrl,
        target.episode,
        itemDetail.title,
        itemDetail.mediaType,
      );
    } else if (target.season) {
      await handleSelectSeason(target.season);
    }
  }

  async function handleTriggerNextEpisode(prompt) {
    if (!itemDetail) return;

    // Mark current episode as watched (100%) before switching
    if (currentPlaybackPayload) {
      const video = videoRef.current;
      const duration =
        video?.duration && !Number.isNaN(video.duration) && video.duration > 0
          ? Math.floor(video.duration)
          : 7200;
      
      const key = progressKey(
        currentPlaybackPayload.seasonUrl || null,
        currentPlaybackPayload.episodeLabel || null,
      );

      try {
        const data = await apiJson("/api/me/progress", {
          method: "PUT",
          body: JSON.stringify({
            ...currentPlaybackPayload,
            durationSeconds: duration,
            positionSeconds: duration,
            event: "ended",
          }),
        });
        const entry = data?.progress || {
          seasonUrl: currentPlaybackPayload.seasonUrl || "",
          episodeLabel: currentPlaybackPayload.episodeLabel || "",
          durationSeconds: duration,
          positionSeconds: duration,
          progressPercent: 100,
          isCompleted: true,
          lastWatchedAt: new Date().toISOString(),
        };
        setItemProgressMap((prev) => ({ ...prev, [key]: entry }));
      } catch (err) {
        console.error("Failed to mark as watched during auto-play:", err);
      }
    }

    setNextEpPrompt(null);
    setCastSendState("armed");
    if (prompt.episode) {
      await loadEpisodeSources(
        itemDetail.provider,
        selectedSeason?.url || itemDetail.seasonUrl,
        prompt.episode,
        itemDetail.title,
        itemDetail.mediaType,
      );
    } else if (prompt.season) {
      await handleSelectSeason(prompt.season);
    }
  }

  // Countdown effect for auto-play
  useEffect(() => {
    if (!nextEpPrompt) return undefined;
    const timer = setInterval(() => {
      setNextEpPrompt((prev) => {
        if (!prev) return null;
        if (prev.countdown <= 1) {
          clearInterval(timer);
          handleTriggerNextEpisode(prev);
          return null;
        }
        return { ...prev, countdown: prev.countdown - 1 };
      });
    }, 1000);
    return () => clearInterval(timer);
  }, [nextEpPrompt, itemDetail]); // eslint-disable-line react-hooks/exhaustive-deps

  // Restore state from URL on initial load
  useEffect(() => {
    if (restoredFromUrlRef.current) return;
    restoredFromUrlRef.current = true;
    const raw = new URLSearchParams(window.location.search).get("v");
    if (!raw) return;
    const state = decodeViewState(raw);
    if (!state?.url || !state?.provider) return;
    const fromUser = appNavigatedOnce;
    appNavigatedOnce = true;
    handleSelectItem(
      { url: state.url, provider: state.provider, title: state.title, mediaType: state.mediaType, posterUrl: state.posterUrl },
      state.seasonUrl,
      state.episode,
      state.exact,
      fromUser,
    );
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // Keep URL in sync with current item / season / episode
  useEffect(() => {
    if (!selectedItem || detailLoading) return;
    const v = encodeViewState({
      provider: selectedItem.provider,
      url: selectedItem.url,
      title: selectedItem.title,
      mediaType: selectedItem.mediaType,
      posterUrl: selectedItem.posterUrl,
      seasonUrl: selectedSeason?.url,
      episode: selectedEpisode,
      // Exact, because this describes what is open right now: reloading the
      // page, or sharing the address, should land back here and not somewhere
      // progress happens to point.
      exact: true,
    });
    history.replaceState(null, "", v ? `?v=${v}` : window.location.pathname);
  }, [selectedItem, selectedSeason, selectedEpisode, detailLoading]);

  function handleGoHome() {
    sourcesAbortRef.current?.abort();
    setNextEpPrompt(null);
    setIsPromptDismissed(false);
    setSelectedItem(null);
    setItemDetail(null);
    setEpisodes([]);
    setSelectedSeason(null);
    setSelectedEpisode("");
    setSources([]);
    setActiveSource(null);
    setPlaybackMode("");
    setError("");
    setResults([]);
    setQuery("");
    history.replaceState(null, "", window.location.pathname);
    window.scrollTo({ top: 0, behavior: "smooth" });
  }

  async function handleSearch(event) {
    event.preventDefault();
    if (!query.trim()) return;
    sourcesAbortRef.current?.abort();
    const providerNames = providerFilter === "all" ? availableProviders.map((provider) => provider.key) : [providerFilter];
    if (providerNames.length === 0) {
      setError("No providers are enabled for this account.");
      return;
    }
    setSelectedItem(null);
    setItemDetail(null);
    setEpisodes([]);
    setSelectedSeason(null);
    setSelectedEpisode("");
    setSources([]);
    setActiveSource(null);
    setPlaybackMode("");
    history.replaceState(null, "", window.location.pathname);
    runProviderSearch(query.trim(), providerNames, { fresh: true });
  }

  /**
   * The provider filter is a lens over what has already been fetched, not a
   * reason to fetch it again. Narrowing to a provider whose results are on
   * screen re-queries nothing; widening — to "all", or to a provider the
   * current search never asked — fetches only the providers that are missing
   * and leaves every cached group exactly where it was. Only the Search
   * button itself starts over.
   */
  function handleProviderFilter(option) {
    setProviderFilter(option);
    const searchedQuery = lastSearchQueryRef.current;
    // Nothing searched yet, or the box has moved on from what the cached
    // results answer: the filter only scopes the next search.
    if (!searchedQuery || searchedQuery !== query.trim()) return;
    const have = new Set(resultsRef.current.map((group) => group.provider));
    const pending = new Set(pendingSearchRef.current);
    const wanted = option === "all" ? availableProviders.map((provider) => provider.key) : [option];
    const missing = wanted.filter((provider) => !have.has(provider) && !pending.has(provider));
    if (missing.length) runProviderSearch(searchedQuery, missing, { fresh: false });
  }

  function runProviderSearch(searchQuery, providerNames, { fresh }) {
    const requestId = fresh ? searchRequestIdRef.current + 1 : searchRequestIdRef.current;
    searchRequestIdRef.current = requestId;
    lastSearchQueryRef.current = searchQuery;
    setSearching(true);
    setError("");
    if (fresh) {
      setPendingSearchProviders(providerNames);
      setResults(providerNames.map((provider) => ({ provider, items: [] })));
    } else {
      // Topping up: the groups already fetched stay untouched, the new ones
      // join them with a spinner each.
      setPendingSearchProviders((current) => [...current, ...providerNames]);
      setResults((current) => [
        ...current.filter((group) => !providerNames.includes(group.provider)),
        ...providerNames.map((provider) => ({ provider, items: [] })),
      ]);
    }
    const errors = [];
    let finished = 0;
    let hasAnyResults = false;

    providerNames.forEach(async (providerName) => {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 12_000);
      try {
        const data = await apiJson(`/api/search?${toQuery({ q: searchQuery, provider: providerName })}`, { signal: controller.signal });
        if (searchRequestIdRef.current !== requestId) return;
        const nextGroup = data.results?.[0] || { provider: providerName, items: [] };
        if ((nextGroup.items || []).length > 0) {
          hasAnyResults = true;
        }
        setResults((current) => current.map((group) => (
          group.provider === providerName ? { provider: providerName, items: nextGroup.items || [] } : group
        )));
      } catch (searchError) {
        if (searchRequestIdRef.current !== requestId) return;
        errors.push(searchError.message);
        setResults((current) => current.map((group) => (
          group.provider === providerName ? { provider: providerName, items: [] } : group
        )));
      } finally {
        clearTimeout(timeoutId);
        if (searchRequestIdRef.current !== requestId) return;
        finished += 1;
        setPendingSearchProviders((current) => current.filter((provider) => provider !== providerName));
        if (finished === providerNames.length) {
          setSearching(false);
          if (errors.length > 0 && !hasAnyResults) {
            setError(errors[0]);
          }
        }
      }
    });
  }

  async function fetchItemProgress(providerKey, itemUrl) {
    try {
      const data = await apiJson(`/api/me/progress?${toQuery({ providerKey, itemUrl })}`);
      return buildProgressMap(data.progress || []);
    } catch {
      return {};
    }
  }

  async function saveSourcePreference(source) {
    if (!selectedItem || !source?.sourceLabel) return;
    const title = itemDetail?.title || selectedItem.title;
    const mediaType = itemDetail?.mediaType || selectedItem.mediaType || "unknown";
    try {
      await apiJson("/api/me/source-preference", {
        method: "POST",
        body: JSON.stringify({
          providerKey: selectedItem.provider,
          mediaType,
          title,
          sourceLabel: source.sourceLabel,
        }),
      });
    } catch {}
  }

  async function loadSourcesFromRawStreams(streams, provider, title, mediaType) {
    sourcesAbortRef.current?.abort();
    const controller = new AbortController();
    sourcesAbortRef.current = controller;

    setSourcesLoading(true);
    setSources([]);
    setActiveSource(null);
    setPlaybackMode("");
    setAutoSelectedFromPreference(false);

    const preferredLabel = await fetchPreferredSourceLabel(provider, mediaType, title);
    // Give slow-but-clean sources a brief chance to arrive before committing,
    // instead of always playing whichever responded first.
    const arrived = [];
    let activeSelected = false;
    let graceTimer = null;

    let committed = null;
    let committedAutomatically = false;

    function commitSelection() {
      if (activeSelected || controller.signal.aborted || !arrived.length) return;
      activeSelected = true;
      window.clearTimeout(graceTimer);
      const { source, fromPreference } = pickAutoSource(arrived, preferredLabel);
      committed = source;
      committedAutomatically = !fromPreference;
      if (source) {
        setActiveSource(source);
        setAutoSelectedFromPreference(fromPreference);
      }
    }

    /**
     * The grace window commits on whatever has arrived, so on a slow link the
     * consensus can be drawn from a handful of sources. Once the stream ends,
     * recompute over the full set and correct an off-consensus pick — but only
     * while it is still automatic and playback has barely begun.
     */
    function reconcileSelection() {
      if (controller.signal.aborted || !committedAutomatically || !committed) return;
      const modal = modalDuration(arrived);
      if (!modal || committed.durationSeconds === modal) return;
      const { source } = pickAutoSource(arrived, preferredLabel);
      if (!source || source === committed) return;
      if ((videoRef.current?.currentTime ?? 0) > 10) return;
      // Functional update so a manual pick made in the meantime always wins.
      setActiveSource((current) => (current === committed ? source : current));
    }

    try {
      await apiNdjsonStream(
        "/api/check-sources",
        { method: "POST", body: JSON.stringify({ streams, provider, preferredLabel }), signal: controller.signal },
        (source) => {
          const normalized = normalizeSourceItem(source);
          arrived.push(normalized);
          setSources((prev) => insertSourceSorted(prev, normalized));
          // An exact preference hit needs no further waiting.
          if (preferredLabel && normalized.sourceLabel === preferredLabel) {
            commitSelection();
            return;
          }
          if (!graceTimer) graceTimer = window.setTimeout(commitSelection, SOURCE_PICK_GRACE_MS);
        },
      );
      commitSelection();
      reconcileSelection();
    } catch (sourceError) {
      if (sourceError.name !== "AbortError") setError(sourceError.message);
    } finally {
      window.clearTimeout(graceTimer);
      setSourcesLoading(false);
    }
  }

  async function loadEpisodeSources(provider, sourceUrl, episode, title, mediaType) {
    sourcesAbortRef.current?.abort();
    const controller = new AbortController();
    sourcesAbortRef.current = controller;

    setNextEpPrompt(null);
    setIsPromptDismissed(false);
    setSourcesLoading(true);
    setSources([]);
    setActiveSource(null);
    setPlaybackMode("");
    setSelectedEpisode(episode);
    setAutoSelectedFromPreference(false);

    const preferredLabel = await fetchPreferredSourceLabel(provider, mediaType, title);
    // Give slow-but-clean sources a brief chance to arrive before committing,
    // instead of always playing whichever responded first.
    const arrived = [];
    let activeSelected = false;
    let graceTimer = null;

    let committed = null;
    let committedAutomatically = false;

    function commitSelection() {
      if (activeSelected || controller.signal.aborted || !arrived.length) return;
      activeSelected = true;
      window.clearTimeout(graceTimer);
      const { source, fromPreference } = pickAutoSource(arrived, preferredLabel);
      committed = source;
      committedAutomatically = !fromPreference;
      if (source) {
        setActiveSource(source);
        setAutoSelectedFromPreference(fromPreference);
      }
    }

    /**
     * The grace window commits on whatever has arrived, so on a slow link the
     * consensus can be drawn from a handful of sources. Once the stream ends,
     * recompute over the full set and correct an off-consensus pick — but only
     * while it is still automatic and playback has barely begun.
     */
    function reconcileSelection() {
      if (controller.signal.aborted || !committedAutomatically || !committed) return;
      const modal = modalDuration(arrived);
      if (!modal || committed.durationSeconds === modal) return;
      const { source } = pickAutoSource(arrived, preferredLabel);
      if (!source || source === committed) return;
      if ((videoRef.current?.currentTime ?? 0) > 10) return;
      // Functional update so a manual pick made in the meantime always wins.
      setActiveSource((current) => (current === committed ? source : current));
    }

    try {
      await apiNdjsonStream(
        `/api/sources?${toQuery({ provider, sourceUrl, episode, preferredLabel })}`,
        { signal: controller.signal },
        (source) => {
          const normalized = normalizeSourceItem(source);
          arrived.push(normalized);
          setSources((prev) => insertSourceSorted(prev, normalized));
          // An exact preference hit needs no further waiting.
          if (preferredLabel && normalized.sourceLabel === preferredLabel) {
            commitSelection();
            return;
          }
          if (!graceTimer) graceTimer = window.setTimeout(commitSelection, SOURCE_PICK_GRACE_MS);
        },
      );
      commitSelection();
      reconcileSelection();
    } catch (sourceError) {
      if (sourceError.name !== "AbortError") setError(sourceError.message);
    } finally {
      window.clearTimeout(graceTimer);
      setSourcesLoading(false);
    }
  }

  async function handleSelectItem(item, targetSeasonUrl = null, targetEpisode = null, exact = false, fromUser = true) {
    sourcesAbortRef.current?.abort();
    // A person opening a title is asking to play it — wherever playback goes.
    // A restore is not a person.
    setCastSendState(fromUser ? "armed" : "idle");
    setSelectedItem(item);
    setItemDetail(null);
    setEpisodes([]);
    setSelectedSeason(null);
    setSelectedEpisode("");
    setSources([]);
    setActiveSource(null);
    setPlaybackMode("");
    setAutoSelectedFromPreference(false);
    setItemProgressMap({});
    setDetailLoading(true);
    setError("");
    window.scrollTo({ top: 0, behavior: "smooth" });

    try {
      const detail = await apiJson(`/api/item?${toQuery(item)}`);
      setItemDetail(detail);
      setDetailLoading(false);

      if (detail.mediaType === "movie") {
        await loadSourcesFromRawStreams(detail.streams || [], detail.provider, detail.title, detail.mediaType);
        return;
      }

      // TV: load progress map first so we can determine resume episode
      const progressMap = await fetchItemProgress(item.provider, item.url);
      setItemProgressMap(progressMap);

      if (detail.provider === "movieffm" && Array.isArray(detail.seasons) && detail.seasons.length > 0) {
        // Determine which season to land on. Only a pointer at one viewing —
        // a row of history — overrides what has been watched; anything else
        // offers its season as a fallback and progress decides.
        const season = exact && targetSeasonUrl
          ? (detail.seasons.find((s) => s.url === targetSeasonUrl) || detail.seasons[0])
          : getResumeSeason(detail.seasons, progressMap, targetSeasonUrl);
        setSelectedSeason(season);

        const episodesData = await apiJson(
          `/api/episodes?${toQuery({ provider: detail.provider, sourceUrl: season.url })}`,
        );
        const nextEpisodes = episodesData.episodes || [];
        setEpisodes(nextEpisodes);

        // Determine which episode to start on. The episode that came in is
        // honoured when it points at one viewing, and otherwise only while it
        // cannot contradict anything: on the season it was recorded against,
        // and only if that season has never been watched. A favourite made at
        // episode one must not undo six episodes of progress.
        const seasonWatched = Object.values(progressMap)
          .some((entry) => (entry.seasonUrl || "") === (season.url || ""));
        const usePassedEpisode = exact || (!seasonWatched && season.url === targetSeasonUrl);

        let episode;
        const targetEpProg = targetEpisode
          ? progressMap[progressKey(season.url, targetEpisode)]
          : null;
        if (usePassedEpisode && targetEpisode && nextEpisodes.includes(targetEpisode) && !targetEpProg?.isCompleted) {
          episode = targetEpisode;
        } else {
          const resumeEp = getResumeEpisode(nextEpisodes, season.url, progressMap);
          if (resumeEp !== null) {
            episode = resumeEp;
          } else {
            // Season complete — advance to next season (Q1a)
            const seasonIdx = detail.seasons.findIndex((s) => s.url === season.url);
            const nextSeason = detail.seasons[seasonIdx + 1];
            if (nextSeason) {
              setSelectedSeason(nextSeason);
              const nextSeasonData = await apiJson(
                `/api/episodes?${toQuery({ provider: detail.provider, sourceUrl: nextSeason.url })}`,
              );
              const nextSeasonEps = nextSeasonData.episodes || [];
              setEpisodes(nextSeasonEps);
              if (nextSeasonEps[0]) {
                await loadEpisodeSources(detail.provider, nextSeason.url, nextSeasonEps[0], detail.title, detail.mediaType);
              }
              return;
            }
            // No next season — all done, reset to S1 E1 (Q2a)
            if (season.url !== detail.seasons[0].url) {
              setSelectedSeason(detail.seasons[0]);
              const s1Data = await apiJson(
                `/api/episodes?${toQuery({ provider: detail.provider, sourceUrl: detail.seasons[0].url })}`,
              );
              const s1Eps = s1Data.episodes || [];
              setEpisodes(s1Eps);
              episode = s1Eps[0];
            } else {
              episode = nextEpisodes[0];
            }
          }
        }

        if (episode) {
          await loadEpisodeSources(detail.provider, season.url, episode, detail.title, detail.mediaType);
        }
        return;
      }

      // 777tv / dramasq: single-season
      const nextEpisodes = detail.episodes || [];
      setEpisodes(nextEpisodes);

      // Same rule as above: no season to get wrong here, but a favourite made
      // at episode one must still not undo progress.
      const seriesWatched = Object.values(progressMap)
        .some((entry) => (entry.seasonUrl || "") === (detail.seasonUrl || ""));

      let episode;
      const targetEpProg2 = targetEpisode
        ? progressMap[progressKey(detail.seasonUrl || null, targetEpisode)]
        : null;
      if ((exact || !seriesWatched) && targetEpisode && nextEpisodes.includes(targetEpisode) && !targetEpProg2?.isCompleted) {
        episode = targetEpisode;
      } else {
        const resumeEp = getResumeEpisode(nextEpisodes, detail.seasonUrl || null, progressMap);
        episode = resumeEp !== null ? resumeEp : nextEpisodes[0];
      }

      if (episode) {
        const sourceUrl = detail.detailUrl ?? detail.seasonUrl;
        await loadEpisodeSources(detail.provider, sourceUrl, episode, detail.title, detail.mediaType);
      }
    } catch (detailError) {
      setError(detailError.message);
      setDetailLoading(false);
    }
  }

  async function handleSelectSeason(season) {
    if (!itemDetail) return;
    setCastSendState("armed");
    setSelectedSeason(season);
    setEpisodes([]);
    setSelectedEpisode("");
    setSources([]);
    setActiveSource(null);
    setSourcesLoading(true);
    try {
      const data = await apiJson(`/api/episodes?${toQuery({ provider: itemDetail.provider, sourceUrl: season.url })}`);
      const nextEpisodes = data.episodes || [];
      setEpisodes(nextEpisodes);
      // Q4b: resume within this season only; null (all done) falls back to first ep
      const resumeEp = getResumeEpisode(nextEpisodes, season.url, itemProgressMap);
      const episode = resumeEp !== null ? resumeEp : nextEpisodes[0];
      if (episode) {
        await loadEpisodeSources(itemDetail.provider, season.url, episode, itemDetail.title, itemDetail.mediaType);
      }
    } catch (seasonError) {
      setError(seasonError.message);
      setSourcesLoading(false);
    }
  }

  async function handleSelectSource(source) {
    setAutoSelectedFromPreference(false);
    setCastSendState("armed");
    await saveSourcePreference(source);
    // Where playback goes — here or on the television — is decided by the
    // player effect, which sees automatic picks as well as this one.
    setActiveSource(source);
  }

  async function handleToggleCurrentEpisodeStatus() {
    if (!currentPlaybackPayload) return;
    const key = progressKey(
      currentPlaybackPayload.seasonUrl || null,
      currentPlaybackPayload.episodeLabel || null,
    );
    const isCompleted = !!resumeProgress?.isCompleted;

    if (isCompleted) {
      try {
        await apiJson("/api/me/progress", {
          method: "DELETE",
          body: JSON.stringify({
            providerKey: currentPlaybackPayload.providerKey,
            itemUrl: currentPlaybackPayload.itemUrl,
            seasonUrl: currentPlaybackPayload.seasonUrl || "",
            episodeLabel: currentPlaybackPayload.episodeLabel || "",
          }),
        });
        setResumeProgress(null);
        setItemProgressMap((prev) => {
          const next = { ...prev };
          delete next[key];
          return next;
        });

        // Offer to clear following episodes too (TV only)
        if (itemDetail?.mediaType === "tv" && episodes.length > 0) {
          const currentIdx = episodes.indexOf(currentPlaybackPayload.episodeLabel);
          if (currentIdx >= 0 && currentIdx < episodes.length - 1) {
            const seasonUrl = currentPlaybackPayload.seasonUrl || null;
            const withProgress = episodes.slice(currentIdx + 1).filter((ep) => {
              return !!itemProgressMap[progressKey(seasonUrl, ep)];
            });
            if (withProgress.length > 0) {
              setMarkBulkDialog({ episodes: withProgress, seasonUrl, action: "unwatched" });
            }
          }
        }
      } catch {}
    } else {
      const video = videoRef.current;
      const duration =
        video?.duration && !Number.isNaN(video.duration) && video.duration > 0
          ? Math.floor(video.duration)
          : 7200;
      try {
        const data = await apiJson("/api/me/progress", {
          method: "PUT",
          body: JSON.stringify({
            ...currentPlaybackPayload,
            durationSeconds: duration,
            positionSeconds: duration,
            event: "pause",
          }),
        });
        const entry = data?.progress || {
          seasonUrl: currentPlaybackPayload.seasonUrl || "",
          episodeLabel: currentPlaybackPayload.episodeLabel || "",
          durationSeconds: duration,
          positionSeconds: duration,
          progressPercent: 100,
          isCompleted: true,
          lastWatchedAt: new Date().toISOString(),
        };
        setResumeProgress(entry);
        setItemProgressMap((prev) => ({ ...prev, [key]: entry }));

        // Offer to mark previous episodes too (TV only)
        if (itemDetail?.mediaType === "tv" && episodes.length > 0) {
          const currentIdx = episodes.indexOf(currentPlaybackPayload.episodeLabel);
          if (currentIdx > 0) {
            const seasonUrl = currentPlaybackPayload.seasonUrl || null;
            const unmarked = episodes.slice(0, currentIdx).filter((ep) => {
              return !itemProgressMap[progressKey(seasonUrl, ep)]?.isCompleted;
            });
            if (unmarked.length > 0) {
              setMarkBulkDialog({ episodes: unmarked, seasonUrl, action: "watched" });
            }
          }
        }
      } catch {}
    }
  }

  async function handleMarkBulk() {
    if (!markBulkDialog || !currentPlaybackPayload) return;
    const { episodes: bulkEps, seasonUrl, action } = markBulkDialog;
    setMarkBulkDialog(null);

    if (action === "watched") {
      const video = videoRef.current;
      const duration =
        video?.duration && !Number.isNaN(video.duration) && video.duration > 0
          ? Math.floor(video.duration)
          : 7200;
      await Promise.all(
        bulkEps.map(async (ep) => {
          try {
            const data = await apiJson("/api/me/progress", {
              method: "PUT",
              body: JSON.stringify({
                ...currentPlaybackPayload,
                episodeLabel: ep,
                seasonUrl,
                durationSeconds: duration,
                positionSeconds: duration,
                event: "pause",
              }),
            });
            const entry = data?.progress || {
              seasonUrl: seasonUrl || "",
              episodeLabel: ep,
              durationSeconds: duration,
              positionSeconds: duration,
              progressPercent: 100,
              isCompleted: true,
              lastWatchedAt: new Date().toISOString(),
            };
            setItemProgressMap((prev) => ({ ...prev, [progressKey(seasonUrl, ep)]: entry }));
          } catch {}
        }),
      );
    } else {
      await Promise.all(
        bulkEps.map(async (ep) => {
          try {
            await apiJson("/api/me/progress", {
              method: "DELETE",
              body: JSON.stringify({
                providerKey: currentPlaybackPayload.providerKey,
                itemUrl: currentPlaybackPayload.itemUrl,
                seasonUrl: seasonUrl || "",
                episodeLabel: ep,
              }),
            });
            setItemProgressMap((prev) => {
              const next = { ...prev };
              delete next[progressKey(seasonUrl, ep)];
              return next;
            });
          } catch {}
        }),
      );
    }
  }

  async function handleToggleFavorite() {
    if (!currentPlaybackPayload) return;
    if (isCurrentFavorite) {
      const target = favoriteEntries.find((entry) => (
        entry.providerKey === currentPlaybackPayload.providerKey
        && entry.itemUrl === currentPlaybackPayload.itemUrl
        && (entry.seasonUrl || null) === (currentPlaybackPayload.seasonUrl || null)
        && (entry.episodeLabel || null) === (currentPlaybackPayload.episodeLabel || null)
      ));
      if (!target) return;
      await apiJson(`/api/me/favorites/${target.id}`, { method: "DELETE" });
      setFavoriteEntries((current) => current.filter((entry) => entry.id !== target.id));
      return;
    }

    const data = await apiJson("/api/me/favorites", {
      method: "POST",
      body: JSON.stringify(currentPlaybackPayload),
    });
    setFavoriteEntries((current) => [data.favorite, ...current.filter((entry) => entry.id !== data.favorite.id)]);
  }

  // The portal owns the only top bar; Browse projects its controls into it.
  usePortalChrome(() => (
    <div className="usr-chrome">
      <form className="usr-searchform" onSubmit={handleSearch}>
        <div className="usr-searchbox">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
            <circle cx="11" cy="11" r="6.5" />
            <path d="m20 20-3.6-3.6" />
          </svg>
          <ImeSafeInput
            name="q"
            type="search"
            value={query}
            onValueChange={setQuery}
            // The Enter that confirms an IME candidate is not a request to
            // search: the field holds half romanization, half candidate at
            // that moment, and Safari happily submits the form on it. 229 is
            // the legacy keyCode every composing keystroke reports.
            onKeyDown={(e) => {
              if (e.key === "Enter" && (e.nativeEvent.isComposing || e.keyCode === 229)) {
                e.preventDefault();
              }
            }}
            placeholder={t.searchPlaceholder}
            aria-label={t.searchPlaceholder}
          />
          {query ? (
            <button
              type="button"
              className="usr-input-clear"
              onClick={(e) => {
                setQuery("");
                e.currentTarget.closest(".usr-searchbox")?.querySelector("input")?.focus();
              }}
              aria-label={t.clearInput}
            >
              ×
            </button>
          ) : null}
        </div>
        <button type="submit" className="usr-btn usr-btn-primary" disabled={searching}>
          {searching ? t.loadingResults : t.searchButton}
        </button>
      </form>

      <div className="usr-topbar-spacer" />

      <CastButton t={t} />

      {!selectedItem && providerFilterOptions.length > 1 && (
        <div className="usr-seg" role="group" aria-label={t.providerFilter}>
          {providerFilterOptions.map((option) => (
            <button
              type="button"
              key={option}
              className={providerFilter === option ? "is-active" : ""}
              onClick={() => handleProviderFilter(option)}
            >
              {option === "all" ? t.providerAll : option === "movieffm" ? t.providerMovieffm : option === "777tv" ? t.provider777tv : t.providerDramasq}
            </button>
          ))}
        </div>
      )}

      <div className="usr-lang" role="group" aria-label="Language">
        <button type="button" className={language === "zh-TW" ? "is-active" : ""} onClick={() => setLanguage("zh-TW")}>繁中</button>
        <button type="button" className={language === "en" ? "is-active" : ""} onClick={() => setLanguage("en")}>EN</button>
      </div>
    </div>
  ), [query, searching, selectedItem, providerFilter, providerFilterOptions, language, t]);

  return (
    <div className="app-shell">

      {/* ── Main ─────────────────────────────────────────────── */}
      <main className="main-content">

        {/* Hero — only when idle */}
        {!searching && groupedResults.length === 0 && !selectedItem && (
          <section className="hero-section">
            <h1 className="hero-title">Stream<span>Hub</span></h1>
            <p className="hero-sub">{t.appTag}</p>
          </section>
        )}

        {error && <div className="error-banner">{error}</div>}

        {/* ── Detail view ────────────────────────────────────── */}
        {selectedItem && (
          <section className="detail-view">
            {/* Body: poster+pickers on left, player on right */}
            {/* Player leads; season/episode rail is secondary. */}
            <div className={`watch-layout${showRail ? "" : " no-rail"}`}>
              <div className="watch-main">
                <div ref={playerCardRef} className={`player-card${immersive ? " is-immersive" : ""}`}>
                  {controlledBy && !immersive ? (
                    // The driven screen shows who is driving it. Fixed over
                    // everything, pulsing so it reads as live. It lives inside
                    // the player card — not the topbar chrome, whose snapshot
                    // would freeze it, and not the page root, which browser
                    // fullscreen of this card would stop rendering. Not shown
                    // while immersive: whoever is watching the handed-over
                    // full screen asked for a picture, not a status bar.
                    <div className="vp-driven" role="status">
                      <span className="vp-driven-dot" />
                      {(t.controlledFrom || "Controlled from {d}").replace("{d}", controlledBy)}
                      {/* The person at this screen outranks the account: one
                          press and this browser leaves every picker and goes
                          deaf to commands, until Profile turns it back on. */}
                      <button type="button" className="vp-driven-detach" onClick={handleDetach}>
                        {t.castDetach || "Stop remote control"}
                      </button>
                    </div>
                  ) : null}
                  {fsHint || detachedNote ? (
                    <div className="vp-fs-hint" role="status">
                      {detachedNote
                        ? (t.castDetached || "Remote control is off for this browser — turn it back on from Profile.")
                        : (t.castFsHint || "Tap the picture — or press OK — for browser fullscreen")}
                    </div>
                  ) : null}
                  {cast.target || cast.lostDevice ? (
                    // Connected to another device, this page *is* the remote:
                    // the player area shows what the receiver is doing and
                    // the controls that drive it, not a dark video element.
                    // The episode and source rows below keep working — while
                    // this panel is up, choosing one plays it over there.
                    <RemotePanel
                      t={t}
                      // Proxied like every other poster: providers refuse
                      // hot-linked images, so the raw URL draws nothing.
                      poster={posterProxyUrl(
                        cast.target?.state?.posterUrl
                          || cast.lostDevice?.state?.posterUrl
                          || selectedItem.posterUrl
                          || itemDetail?.posterUrl
                          || "",
                      )}
                      canSend={Boolean(cast.target) && Boolean(activeSource) && castSendState === "idle"}
                      onSendCurrent={() => setCastSendState("armed")}
                    />
                  ) : (
                  <VideoPlayer
                    videoRef={videoRef}
                    hls={hlsInstance}
                    adCuts={adCuts}
                    onCreatePreview={createPreview}
                    onDownload={handleDownload}
                    download={download}
                    t={t}
                    title={itemDetail?.title || selectedItem.title}
                    subtitle={[activeSource?.sourceLabel, selectedEpisode].filter(Boolean).join(" · ")}
                    onPrev={itemDetail?.mediaType === "tv" ? () => goToNeighbour(episodeNeighbours.prev) : null}
                    onNext={itemDetail?.mediaType === "tv" ? () => goToNeighbour(episodeNeighbours.next) : null}
                    prevLabel={episodeNeighbours.prev?.label}
                    nextLabel={episodeNeighbours.next?.label}
                    blocked={!activeSource ? (
                      <>
                        {sourcesLoading && <div className="spinner" />}
                        <p>{sourcesLoading ? t.loadingSources : t.noSources}</p>
                      </>
                    ) : null}
                    overlay={nextEpPrompt ? (
                      <div className="autoplay-prompt">
                        <div className="prompt-header">
                          <span className="prompt-title">{t.upNext}: {nextEpPrompt.episode || nextEpPrompt.season?.label}</span>
                          <button type="button" className="prompt-close" onClick={() => { setNextEpPrompt(null); setIsPromptDismissed(true); }}>×</button>
                        </div>
                        <div className="prompt-progress">
                          <div className="prompt-progress-bar" style={{ width: `${(nextEpPrompt.countdown / 120) * 100}%` }} />
                        </div>
                        <div className="prompt-actions">
                          <button type="button" className="btn-play-now" onClick={() => handleTriggerNextEpisode(nextEpPrompt)}>
                            {t.playNow} ({nextEpPrompt.countdown}s)
                          </button>
                          <button type="button" className="btn-cancel-autoplay" onClick={() => { setNextEpPrompt(null); setIsPromptDismissed(true); }}>
                            {t.cancelAutoPlay}
                          </button>
                        </div>
                      </div>
                    ) : null}
                  />
                  )}
                  {immersive ? (
                    // The way out of the handed-over full screen, for whoever
                    // is at *this* device. Esc does the same.
                    <button
                      type="button"
                      className="vp-immersive-exit"
                      onClick={() => setImmersive(false)}
                      aria-label={t.exitFullscreen}
                    >
                      ×
                    </button>
                  ) : null}
                  {playerError && !cast.target && !cast.lostDevice ? <div className="error-box">{playerError}</div> : null}
                </div>

                <div className="watch-info">
                  <div className="watch-title-row">
                    <PosterImage
                      src={selectedItem.posterUrl}
                      alt={selectedItem.title}
                      className="watch-poster"
                      fallbackClassName="watch-poster-fallback"
                    />
                    <div className="watch-title-text">
                      <h2>{itemDetail?.title || selectedItem.title}</h2>
                      <div className="watch-chips">
                        <span className="chip chip-accent">{selectedItem.provider}</span>
                        <span className="chip">{normalizeMediaTypeLabel(selectedItem.mediaType, t)}</span>
                        {selectedEpisode ? <span className="chip">{selectedEpisode}</span> : null}
                        {activeSource ? (
                          <span className="watch-mode">
                            {t.playbackMode}: {playbackMode === "proxy" ? t.playbackProxy : t.playbackDirect}
                            {playbackEngine ? ` · ${playbackEngine}` : ""}
                          </span>
                        ) : null}
                      </div>
                      {detailLoading && <p className="detail-hint">{t.loadingDetails}</p>}
                      {!detailLoading && resumeProgress?.positionSeconds > 30 && (
                        <p className="detail-hint">
                          {t.resumeFrom} {Math.floor(Math.max(0, resumeProgress.positionSeconds - 30) / 60)}m {Math.max(0, resumeProgress.positionSeconds - 30) % 60}s
                        </p>
                      )}
                    </div>
                    <div className="watch-title-actions">
                      {activeSource ? (
                        <button
                          type="button"
                          className={`btn-mark-watched ${currentEpIsCompleted ? "is-completed" : ""}`}
                          onClick={handleToggleCurrentEpisodeStatus}
                        >
                          {currentEpIsCompleted ? t.markUnwatched : t.markWatched}
                        </button>
                      ) : null}
                      {!detailLoading && (
                        <button
                          type="button"
                          className={`favorite-toggle ${isCurrentFavorite ? "active" : ""}`}
                          onClick={handleToggleFavorite}
                          aria-label={isCurrentFavorite ? "Remove from favorites" : "Add to favorites"}
                          title={isCurrentFavorite ? "Remove from favorites" : "Add to favorites"}
                        >
                          <svg viewBox="0 0 24 24" aria-hidden="true">
                            <path d="M12 21 3.8 12.8A5.6 5.6 0 0 1 11.7 4.9L12 5.2l.3-.3a5.6 5.6 0 1 1 7.9 7.9z" />
                          </svg>
                        </button>
                      )}
                    </div>
                  </div>

                  <SourceSelect
                    label={t.availableSources}
                    rows={sourceRows}
                    activeKey={sourceKey(activeSource)}
                    onSelect={handleSelectSource}
                    loading={sourcesLoading}
                    loadingText={t.loadingSources}
                    emptyText={t.noSources}
                    note={autoSelectedFromPreference && !sourcesLoading ? t.preferenceAutoSelected : ""}
                    adNote={activeSource?.adSeconds > 0
                      ? t.metaAdsExcluded.replace("{s}", activeSource.adSeconds)
                    : ""}
                    adTag={t.srcAdsStripped}
                    adTagTitle={t.srcAdsStrippedTitle}
                  />
                </div>
              </div>

              {showRail ? (
                <aside className="watch-rail">
                  <SeasonSelect
                    label={t.seasons}
                    options={seasonOptions}
                    value={selectedSeason?.url}
                    onChange={(url) => {
                      const season = (itemDetail?.seasons || []).find((entry) => entry.url === url);
                      if (season) handleSelectSeason(season);
                    }}
                  />
                  <EpisodeRail
                    heading={t.episodes}
                    rows={episodeRows}
                    onSelect={handleSelectEpisodeLabel}
                    watchedLabel={t.watched}
                    nowPlayingLabel={t.nowPlaying}
                  />
                </aside>
              ) : null}
            </div>
          </section>
        )}

        {/* ── Results grid ───────────────────────────────────── */}
        {/* A provider narrowed to nothing: the search ran, other providers
            had results, this one had none. Without a word here the page just
            goes blank under an active-looking filter. */}
        {!searching && !selectedItem && visibleSearchGroups.length === 0 && groupedResults.length > 0 ? (
          <div className="error-banner">{t.noResults}</div>
        ) : null}

        {visibleSearchGroups.length > 0 && (
          <section className="results-section">
            {visibleSearchGroups.map((group) => (
              <div className="results-group" key={group.provider}>
                <div className="group-heading">
                  {group.provider}
                  {!pendingSearchProviders.includes(group.provider) && (
                    <span className="badge">{group.items.length}</span>
                  )}
                </div>
                {pendingSearchProviders.includes(group.provider) ? (
                  <div className="poster-grid">
                    {Array.from({ length: 10 }).map((_, i) => (
                      <div
                        key={i}
                        className="skeleton-card"
                        style={{ animationDelay: `${i * 0.06}s` }}
                      />
                    ))}
                  </div>
                ) : (
                  <div className="poster-grid">
                    {group.items.map((item) => (
                      <button
                        type="button"
                        key={`${item.provider}:${item.url}`}
                        className={`result-card ${selectedItem?.url === item.url ? "active" : ""}`}
                        onClick={() => handleSelectItem(item)}
                      >
                        <span className="result-card-art">
                          <PosterImage
                            src={item.posterUrl}
                            alt={item.title}
                            className="poster-img"
                            fallbackClassName="poster-fallback"
                          />
                          <span className="result-card-chips">
                            <span className="chip chip-accent">{item.provider}</span>
                          </span>
                        </span>
                        <span className="result-card-text">
                          <span className="result-card-title">{item.title}</span>
                          <span className="result-card-meta">{normalizeMediaTypeLabel(item.mediaType, t)}</span>
                        </span>
                      </button>
                    ))}
                  </div>
                )}
              </div>
            ))}
          </section>
        )}

      </main>

      {markBulkDialog && (
        <div className="confirm-overlay" onClick={() => setMarkBulkDialog(null)}>
          <div className="confirm-card" onClick={(e) => e.stopPropagation()}>
            <p className="confirm-message">
              {markBulkDialog.action === "watched" ? t.markPrevConfirm : t.markNextConfirm}
            </p>
            <div className="confirm-actions">
              <button type="button" className="confirm-no" onClick={() => setMarkBulkDialog(null)}>
                {t.no}
              </button>
              <button type="button" className="confirm-yes" onClick={handleMarkBulk}>
                {t.yes}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export default App;
