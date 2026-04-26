import { useEffect, useMemo, useRef, useState } from "react";
import Hls from "hls.js";
import { resolveLanguage, translations } from "./i18n.js";
import { apiJson, apiNdjsonStream, getAccessToken } from "./api.js";

const providerOptions = ["movieffm", "777tv", "dramasq"];

function encodeViewState({ provider, url, title, mediaType, posterUrl, seasonUrl, episode }) {
  try {
    const obj = { p: provider, u: url, t: title, m: mediaType };
    if (posterUrl) obj.ps = posterUrl;
    if (seasonUrl) obj.s = seasonUrl;
    if (episode)   obj.ep = episode;
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
    return { provider: obj.p, url: obj.u, title: obj.t, mediaType: obj.m, posterUrl: obj.ps || "", seasonUrl: obj.s || null, episode: obj.ep || null };
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

function getSourcePlaybackMode(source, activeSource, playbackMode) {
  if (!source) return "";
  if (activeSource?.url === source.url) return playbackMode || "direct";
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

function insertSourceSorted(prev, source) {
  const next = [...prev, source];
  return next.sort((a, b) => {
    const aDur = Number.isFinite(a.durationSeconds) ? a.durationSeconds : -1;
    const bDur = Number.isFinite(b.durationSeconds) ? b.durationSeconds : -1;
    return bDur - aDur;
  });
}

function posterProxyUrl(url) {
  if (!url) return "";
  return withCurrentOrigin(`/api/poster?target=${encodeURIComponent(url)}&accessToken=${encodeURIComponent(getAccessToken())}`);
}

function PosterImage({ src, alt, className, fallbackClassName }) {
  const [failed, setFailed] = useState(false);
  if (!src || failed) {
    return (
      <div className={fallbackClassName ?? className} aria-label={alt}>
        No Image
      </div>
    );
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
  const [language, setLanguage] = useState(resolveLanguage());
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
  const [playbackMode, setPlaybackMode] = useState("");
  const [autoSelectedFromPreference, setAutoSelectedFromPreference] = useState(false);
  const [itemProgressMap, setItemProgressMap] = useState({});
  const [playerError, setPlayerError] = useState("");
  const [availableProviders, setAvailableProviders] = useState([]);
  const [favoriteEntries, setFavoriteEntries] = useState([]);
  const [resumeProgress, setResumeProgress] = useState(null);
  const [markBulkDialog, setMarkBulkDialog] = useState(null);
  const [bufferedAhead, setBufferedAhead] = useState(0);
  const [nextEpPrompt, setNextEpPrompt] = useState(null);
  const [isPromptDismissed, setIsPromptDismissed] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const videoRef = useRef(null);
  const videoFrameRef = useRef(null);
  const tRef = useRef(t);
  const restoredFromUrlRef = useRef(false);
  const searchRequestIdRef = useRef(0);
  const lastProgressSentRef = useRef(0);
  const sourcesAbortRef = useRef(null);
  useEffect(() => { tRef.current = t; }, [t]);

  const groupedResults = useMemo(
    () => results.filter((group) => group.items.length > 0),
    [results],
  );
  const providerFilterOptions = useMemo(
    () => ["all", ...availableProviders.map((provider) => provider.key)],
    [availableProviders],
  );
  const visibleSearchGroups = useMemo(
    () => results.filter((group) => group.items.length > 0 || pendingSearchProviders.includes(group.provider)),
    [results, pendingSearchProviders],
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

  useEffect(() => {
    const video = videoRef.current;
    if (!video || (!activeSource?.directUrl && !activeSource?.proxyUrl)) {
      return undefined;
    }

    setPlayerError("");
    setPlaybackMode("");

    const directUrl = activeSource.directUrl || activeSource.url;
    const proxyUrl = activeSource.proxyUrl;

    function setMode(mode) { setPlaybackMode(mode); }

    function loadNative(url, mode) {
      setMode(mode);
      video.src = url;
      void video.play().catch(() => {});
    }

    function loadWithHls(url, mode, onFatalError) {
      const hls = new Hls();
      setMode(mode);
      hls.loadSource(url);
      hls.attachMedia(video);
      hls.on(Hls.Events.MANIFEST_PARSED, () => {
        void video.play().catch(() => {});
      });
      hls.on(Hls.Events.ERROR, (_event, data) => {
        if (data.fatal) onFatalError?.(hls);
      });
      return hls;
    }

    if (video.canPlayType("application/vnd.apple.mpegurl") && directUrl) {
      loadNative(directUrl, "direct");
      video.onerror = () => {
        if (proxyUrl && video.src !== proxyUrl) {
          setPlayerError(tRef.current.playbackFallback);
          loadNative(proxyUrl, "proxy");
        } else {
          setPlayerError(tRef.current.statusError);
        }
      };
      return () => { video.onerror = null; };
    }

    if (Hls.isSupported()) {
      let fallbackHls = null;
      const primaryHls = loadWithHls(directUrl || proxyUrl, directUrl ? "direct" : "proxy", (instance) => {
        instance.destroy();
        if (proxyUrl && directUrl && directUrl !== proxyUrl) {
          setPlayerError(t.playbackFallback);
          fallbackHls = loadWithHls(proxyUrl, "proxy", (proxyInstance) => {
            setPlayerError(tRef.current.statusError);
            proxyInstance.destroy();
          });
        } else {
          setPlayerError(tRef.current.statusError);
        }
      });
      return () => {
        primaryHls.destroy();
        fallbackHls?.destroy();
      };
    }

    setPlayerError(t.statusError);
    return undefined;
  }, [activeSource]);

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
    if (!video) return undefined;
    setBufferedAhead(0);
    function update() {
      const cur = video.currentTime;
      const buf = video.buffered;
      for (let i = 0; i < buf.length; i++) {
        if (buf.start(i) <= cur + 0.5 && cur <= buf.end(i)) {
          setBufferedAhead(Math.max(0, buf.end(i) - cur));
          return;
        }
      }
      setBufferedAhead(0);
    }
    video.addEventListener("progress", update);
    video.addEventListener("timeupdate", update);
    return () => {
      video.removeEventListener("progress", update);
      video.removeEventListener("timeupdate", update);
    };
  }, [activeSource]);

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

  function handleToggleFullscreen() {
    const frame = videoFrameRef.current;
    if (!frame) return;
    if (!document.fullscreenElement) {
      void frame.requestFullscreen().catch(() => {});
    } else {
      void document.exitFullscreen().catch(() => {});
    }
  }

  useEffect(() => {
    function handleFullscreenChange() {
      setIsFullscreen(document.fullscreenElement === videoFrameRef.current);
    }

    document.addEventListener("fullscreenchange", handleFullscreenChange);
    handleFullscreenChange();
    return () => {
      document.removeEventListener("fullscreenchange", handleFullscreenChange);
    };
  }, []);

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
    handleSelectItem(
      { url: state.url, provider: state.provider, title: state.title, mediaType: state.mediaType, posterUrl: state.posterUrl },
      state.seasonUrl,
      state.episode,
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
    const requestId = searchRequestIdRef.current + 1;
    searchRequestIdRef.current = requestId;
    const providerNames = providerFilter === "all" ? availableProviders.map((provider) => provider.key) : [providerFilter];
    if (providerNames.length === 0) {
      setError("No providers are enabled for this account.");
      return;
    }
    setSearching(true);
    setPendingSearchProviders(providerNames);
    setError("");
    setResults(providerNames.map((provider) => ({ provider, items: [] })));
    setSelectedItem(null);
    setItemDetail(null);
    setEpisodes([]);
    setSelectedSeason(null);
    setSelectedEpisode("");
    setSources([]);
    setActiveSource(null);
    setPlaybackMode("");
    history.replaceState(null, "", window.location.pathname);
    const errors = [];
    let finished = 0;
    let hasAnyResults = false;
    const searchQuery = query.trim();

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
    let firstSource = null;
    let activeSelected = false;

    try {
      await apiNdjsonStream(
        "/api/check-sources",
        { method: "POST", body: JSON.stringify({ streams, provider, preferredLabel }), signal: controller.signal },
        (source) => {
          const normalized = normalizeSourceItem(source);
          setSources((prev) => insertSourceSorted(prev, normalized));
          if (!firstSource) firstSource = normalized;
          if (!activeSelected) {
            if (!preferredLabel || normalized.sourceLabel === preferredLabel) {
              setActiveSource(normalized);
              setAutoSelectedFromPreference(!!preferredLabel);
              activeSelected = true;
            }
          }
        },
      );
      if (!activeSelected && firstSource) {
        setActiveSource(firstSource);
      }
    } catch (sourceError) {
      if (sourceError.name !== "AbortError") setError(sourceError.message);
    } finally {
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
    let firstSource = null;
    let activeSelected = false;

    try {
      await apiNdjsonStream(
        `/api/sources?${toQuery({ provider, sourceUrl, episode, preferredLabel })}`,
        { signal: controller.signal },
        (source) => {
          const normalized = normalizeSourceItem(source);
          setSources((prev) => insertSourceSorted(prev, normalized));
          if (!firstSource) firstSource = normalized;
          if (!activeSelected) {
            if (!preferredLabel || normalized.sourceLabel === preferredLabel) {
              setActiveSource(normalized);
              setAutoSelectedFromPreference(!!preferredLabel);
              activeSelected = true;
            }
          }
        },
      );
      if (!activeSelected && firstSource) {
        setActiveSource(firstSource);
      }
    } catch (sourceError) {
      if (sourceError.name !== "AbortError") setError(sourceError.message);
    } finally {
      setSourcesLoading(false);
    }
  }

  async function handleSelectItem(item, targetSeasonUrl = null, targetEpisode = null) {
    sourcesAbortRef.current?.abort();
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
        // Determine which season to land on
        let season;
        if (targetSeasonUrl) {
          season = detail.seasons.find((s) => s.url === targetSeasonUrl) || detail.seasons[0];
        } else {
          const lastEntry = Object.values(progressMap)
            .sort((a, b) => new Date(b.lastWatchedAt) - new Date(a.lastWatchedAt))[0];
          season = (lastEntry && detail.seasons.find((s) => s.url === lastEntry.seasonUrl))
            || detail.seasons[0];
        }
        setSelectedSeason(season);

        const episodesData = await apiJson(
          `/api/episodes?${toQuery({ provider: detail.provider, sourceUrl: season.url })}`,
        );
        const nextEpisodes = episodesData.episodes || [];
        setEpisodes(nextEpisodes);

        // Determine which episode to start on
        let episode;
        const targetEpProg = targetEpisode
          ? progressMap[progressKey(season.url, targetEpisode)]
          : null;
        if (targetEpisode && nextEpisodes.includes(targetEpisode) && !targetEpProg?.isCompleted) {
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

      let episode;
      const targetEpProg2 = targetEpisode
        ? progressMap[progressKey(detail.seasonUrl || null, targetEpisode)]
        : null;
      if (targetEpisode && nextEpisodes.includes(targetEpisode) && !targetEpProg2?.isCompleted) {
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
    setActiveSource(source);
    setAutoSelectedFromPreference(false);
    await saveSourcePreference(source);
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

  return (
    <div className="app-shell">

      {/* ── Navbar ───────────────────────────────────────────── */}
      <nav className="navbar">
        <button type="button" className="logo" onClick={handleGoHome}>StreamHub</button>

        <form className="navbar-search" onSubmit={handleSearch}>
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t.searchPlaceholder}
          />
          <button type="submit" className="btn-search" disabled={searching}>
            {searching ? t.loadingResults : t.searchButton}
          </button>
        </form>

        <div className="navbar-controls">
          <div className="lang-switch">
            <button type="button" className={language === "zh-TW" ? "active" : ""} onClick={() => setLanguage("zh-TW")}>
              繁中
            </button>
            <button type="button" className={language === "en" ? "active" : ""} onClick={() => setLanguage("en")}>
              EN
            </button>
          </div>
        </div>
        {!selectedItem && (
          <div className="provider-filter-row">
            <div className="segmented">
              {providerFilterOptions.map((option) => (
                <button
                  type="button"
                  key={option}
                  className={providerFilter === option ? "active" : ""}
                  onClick={() => setProviderFilter(option)}
                >
                  {option === "all" ? t.providerAll : option === "movieffm" ? t.providerMovieffm : option === "777tv" ? t.provider777tv : t.providerDramasq}
                </button>
              ))}
            </div>
          </div>
        )}
      </nav>

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
            <div className="detail-body">
              <div className="detail-left">
                {/* Poster + meta */}
                <div className="detail-header">
                  <PosterImage
                    src={selectedItem.posterUrl}
                    alt={selectedItem.title}
                    className="detail-poster-thumb"
                    fallbackClassName="detail-poster-fallback"
                  />
                  <div className="detail-meta">
                    <div className="chip-row">
                      <span className="chip chip-accent">{selectedItem.provider}</span>
                      <span className="chip">{normalizeMediaTypeLabel(selectedItem.mediaType, t)}</span>
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
                    <h2>{selectedItem.title}</h2>
                    {detailLoading && <p className="detail-hint">{t.loadingDetails}</p>}
                    {!detailLoading && resumeProgress?.positionSeconds > 30 && (
                      <p className="detail-hint">
                        {t.resumeFrom} {Math.floor(Math.max(0, resumeProgress.positionSeconds - 30) / 60)}m {Math.max(0, resumeProgress.positionSeconds - 30) % 60}s
                      </p>
                    )}
                  </div>
                </div>
                {/* Seasons */}
                {itemDetail?.mediaType === "tv" && itemDetail?.provider === "movieffm" && itemDetail?.seasons?.length > 0 && (
                  <div className="picker-block">
                    <div className="picker-heading"><span>{t.seasons}</span></div>
                    <div className="pill-row">
                      {itemDetail.seasons.map((season) => {
                        const isActiveSeason = selectedSeason?.url === season.url;
                        const seasonStatusClass = isActiveSeason ? "active" : getSeasonStatus(season.url, itemProgressMap);
                        return (
                          <button
                            type="button"
                            key={season.url}
                            className={seasonStatusClass}
                            onClick={() => handleSelectSeason(season)}
                          >
                            {season.label}
                          </button>
                        );
                      })}
                    </div>
                  </div>
                )}

                {/* Episodes */}
                {itemDetail?.mediaType === "tv" && episodes.length > 0 && (
                  <div className="picker-block">
                    <div className="picker-heading">
                      <span>{t.episodes}</span>
                      <span className="count">{episodes.length}</span>
                    </div>
                    <div className="pill-row">
                      {episodes.map((episode) => {
                        const epSeasonUrl = itemDetail.provider === "movieffm"
                          ? (selectedSeason?.url || itemDetail.seasonUrl || null)
                          : (itemDetail.seasonUrl || null);
                        const epProg = itemProgressMap[progressKey(epSeasonUrl, episode)];
                        const isActiveEp = selectedEpisode === episode;
                        const epStatusClass = isActiveEp ? "active"
                          : epProg?.isCompleted ? "pill-completed"
                          : (epProg?.progressPercent || 0) > 0 ? "pill-in-progress"
                          : "";
                        return (
                          <button
                            type="button"
                            key={episode}
                            className={epStatusClass}
                            style={epStatusClass === "pill-in-progress"
                              ? { "--ep-progress": `${Math.round(epProg.progressPercent)}%` }
                              : undefined}
                            onClick={() =>
                              loadEpisodeSources(
                                itemDetail.provider,
                                itemDetail.provider === "777tv"
                                  ? itemDetail.detailUrl
                                  : selectedSeason?.url || itemDetail.seasonUrl,
                                episode,
                                itemDetail.title,
                                itemDetail.mediaType,
                              )
                            }
                          >
                            {episode}
                          </button>
                        );
                      })}
                    </div>
                  </div>
                )}

                {/* Sources */}
                <div className="picker-block">
                  <div className="picker-heading">
                    <span>{t.availableSources}</span>
                    {!sourcesLoading && <span className="count">{sources.length}</span>}
                  </div>
                  {autoSelectedFromPreference && !sourcesLoading && (
                    <span className="pref-auto-note">{t.preferenceAutoSelected}</span>
                  )}
                  {sourcesLoading && sources.length === 0 && <p className="inline-note">{t.loadingSources}</p>}
                  {!sourcesLoading && sources.length === 0 && <p className="inline-note">{t.noSources}</p>}
                  <div className="source-list">
                    {sources.map((source) => {
                      const mode = getSourcePlaybackMode(source, activeSource, playbackMode);
                      return (
                        <button
                          type="button"
                          key={`${source.sourceLabel}:${source.url}`}
                          className={`source-item ${activeSource?.url === source.url ? "active" : ""}`}
                          onClick={() => handleSelectSource(source)}
                        >
                          <span className={`mode-dot ${mode === "proxy" ? "proxy" : "direct"}`} />
                          <span className="source-item-text">
                            <span className="source-item-label">{source.sourceLabel}</span>
                            <span className="source-item-duration">{formatSourceDuration(source.durationSeconds, t)}</span>
                          </span>
                        </button>
                      );
                    })}
                  </div>
                </div>
              </div>

              {/* Player */}
              <div className="detail-right">
                <div className="player-card">
                  <div className="video-frame" ref={videoFrameRef} onDoubleClick={handleToggleFullscreen}>
                    <video ref={videoRef} controls controlsList="nofullscreen" playsInline />
                    {nextEpPrompt && (
                      <div className="autoplay-prompt">
                        <div className="prompt-header">
                          <span className="prompt-title">{t.upNext}: {nextEpPrompt.episode || nextEpPrompt.season?.label}</span>
                          <button type="button" className="prompt-close" onClick={() => { setNextEpPrompt(null); setIsPromptDismissed(true); }}>×</button>
                        </div>
                        <div className="prompt-progress">
                          <div
                            className="prompt-progress-bar"
                            style={{ width: `${(nextEpPrompt.countdown / 120) * 100}%` }}
                          />
                        </div>
                        <div className="prompt-actions">
                          <button
                            type="button"
                            className="btn-play-now"
                            onClick={() => handleTriggerNextEpisode(nextEpPrompt)}
                          >
                            {t.playNow} ({nextEpPrompt.countdown}s)
                          </button>
                          <button
                            type="button"
                            className="btn-cancel-autoplay"
                            onClick={() => { setNextEpPrompt(null); setIsPromptDismissed(true); }}
                          >
                            {t.cancelAutoPlay}
                          </button>
                        </div>
                      </div>
                    )}
                    {!activeSource && (
                      <div className="video-blocked">
                        {sourcesLoading && <div className="spinner" />}
                        <p>{sourcesLoading ? t.loadingSources : t.noSources}</p>
                      </div>
                    )}
                  </div>
                  <button
                    type="button"
                    className="btn-fullscreen-toggle"
                    onClick={handleToggleFullscreen}
                  >
                    {isFullscreen ? t.exitFullscreen : t.enterFullscreen}
                  </button>
                  {playerError && <div className="error-box">{playerError}</div>}
                  {activeSource ? (
                    <div className="player-meta">
                      <p>
                        <strong>{activeSource.sourceLabel}</strong>
                        {" · "}
                        {activeSource.episodeLabel}
                      </p>
                      <p>
                        {formatSourceDuration(activeSource.durationSeconds, t)}
                      </p>
                      <p>
                        {t.playbackMode}: {playbackMode === "proxy" ? t.playbackProxy : t.playbackDirect}
                      </p>
                      {bufferedAhead > 0 && (
                        <p>{t.bufferedAhead}: {formatSourceDuration(bufferedAhead, t)}</p>
                      )}
                      <button
                        type="button"
                        className={`btn-mark-watched ${currentEpIsCompleted ? "is-completed" : ""}`}
                        onClick={handleToggleCurrentEpisodeStatus}
                      >
                        {currentEpIsCompleted ? t.markUnwatched : t.markWatched}
                      </button>
                    </div>
                  ) : (
                    !sourcesLoading && <p className="inline-note">{t.noSources}</p>
                  )}
                </div>
              </div>
            </div>
          </section>
        )}

        {/* ── Results grid ───────────────────────────────────── */}
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
                        className={`poster-card ${selectedItem?.url === item.url ? "active" : ""}`}
                        onClick={() => handleSelectItem(item)}
                      >
                        <PosterImage
                          src={item.posterUrl}
                          alt={item.title}
                          className="poster-img"
                          fallbackClassName="poster-fallback"
                        />
                        <div className="poster-overlay">
                          <div className="overlay-chips">
                            <span className="chip chip-accent">{item.provider}</span>
                            <span className="chip">{normalizeMediaTypeLabel(item.mediaType, t)}</span>
                          </div>
                          <p className="overlay-title">{item.title}</p>
                        </div>
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
