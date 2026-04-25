import { useEffect, useMemo, useRef, useState } from "react";
import Hls from "hls.js";
import { resolveLanguage, translations } from "./i18n.js";

const providerOptions = ["all", "movieffm", "777tv"];

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
    proxyUrl: withCurrentOrigin(source.proxyUrl),
  }));
}

function posterProxyUrl(url) {
  if (!url) return "";
  return withCurrentOrigin(`/api/poster?target=${encodeURIComponent(url)}`);
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
  const [playerError, setPlayerError] = useState("");
  const videoRef = useRef(null);
  const tRef = useRef(t);
  useEffect(() => { tRef.current = t; }, [t]);

  const groupedResults = useMemo(
    () => results.filter((group) => group.items.length > 0),
    [results],
  );

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

  function handleGoHome() {
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
    window.scrollTo({ top: 0, behavior: "smooth" });
  }

  async function handleSearch(event) {
    event.preventDefault();
    if (!query.trim()) return;
    setSearching(true);
    setError("");
    setSelectedItem(null);
    setItemDetail(null);
    setEpisodes([]);
    setSelectedSeason(null);
    setSelectedEpisode("");
    setSources([]);
    setActiveSource(null);
    setPlaybackMode("");
    try {
      const response = await fetch(`/api/search?${toQuery({ q: query.trim(), provider: providerFilter })}`);
      if (!response.ok) throw new Error("Search failed.");
      const data = await response.json();
      setResults(data.results || []);
    } catch (searchError) {
      setResults([]);
      setError(searchError.message);
    } finally {
      setSearching(false);
    }
  }

  async function loadSourcesFromRawStreams(streams) {
    setSourcesLoading(true);
    setSources([]);
    setActiveSource(null);
    setPlaybackMode("");
    try {
      const response = await fetch("/api/check-sources", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ streams }),
      });
      if (!response.ok) throw new Error("Failed to check sources.");
      const data = await response.json();
      const availableSources = normalizeSourceList(data.sources);
      setSources(availableSources);
      setActiveSource(availableSources[0] || null);
    } catch (sourceError) {
      setError(sourceError.message);
    } finally {
      setSourcesLoading(false);
    }
  }

  async function loadEpisodeSources(provider, sourceUrl, episode) {
    setSourcesLoading(true);
    setSources([]);
    setActiveSource(null);
    setPlaybackMode("");
    setSelectedEpisode(episode);
    try {
      const response = await fetch(`/api/sources?${toQuery({ provider, sourceUrl, episode })}`);
      if (!response.ok) throw new Error("Failed to load sources.");
      const data = await response.json();
      const availableSources = normalizeSourceList(data.sources);
      setSources(availableSources);
      setActiveSource(availableSources[0] || null);
    } catch (sourceError) {
      setError(sourceError.message);
    } finally {
      setSourcesLoading(false);
    }
  }

  async function handleSelectItem(item) {
    setSelectedItem(item);
    setItemDetail(null);
    setEpisodes([]);
    setSelectedSeason(null);
    setSelectedEpisode("");
    setSources([]);
    setActiveSource(null);
    setPlaybackMode("");
    setDetailLoading(true);
    setError("");
    window.scrollTo({ top: 0, behavior: "smooth" });

    try {
      const response = await fetch(`/api/item?${toQuery(item)}`);
      if (!response.ok) throw new Error("Failed to load item.");
      const detail = await response.json();
      setItemDetail(detail);

      if (detail.mediaType === "movie") {
        await loadSourcesFromRawStreams(detail.streams || []);
        return;
      }

      if (detail.provider === "movieffm" && Array.isArray(detail.seasons) && detail.seasons.length > 0) {
        setSelectedSeason(detail.seasons[0]);
        const episodesResponse = await fetch(
          `/api/episodes?${toQuery({ provider: detail.provider, sourceUrl: detail.seasons[0].url })}`,
        );
        if (!episodesResponse.ok) throw new Error("Failed to load episodes.");
        const episodesData = await episodesResponse.json();
        const nextEpisodes = episodesData.episodes || [];
        setEpisodes(nextEpisodes);
        if (nextEpisodes[0]) {
          await loadEpisodeSources(detail.provider, detail.seasons[0].url, nextEpisodes[0]);
        }
        return;
      }

      const nextEpisodes = detail.episodes || [];
      setEpisodes(nextEpisodes);
      if (nextEpisodes[0]) {
        const sourceUrl = detail.provider === "777tv" ? detail.detailUrl : detail.seasonUrl;
        await loadEpisodeSources(detail.provider, sourceUrl, nextEpisodes[0]);
      }
    } catch (detailError) {
      setError(detailError.message);
    } finally {
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
      const response = await fetch(`/api/episodes?${toQuery({ provider: itemDetail.provider, sourceUrl: season.url })}`);
      if (!response.ok) throw new Error("Failed to load episodes.");
      const data = await response.json();
      const nextEpisodes = data.episodes || [];
      setEpisodes(nextEpisodes);
      if (nextEpisodes[0]) {
        await loadEpisodeSources(itemDetail.provider, season.url, nextEpisodes[0]);
      }
    } catch (seasonError) {
      setError(seasonError.message);
      setSourcesLoading(false);
    }
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
          {!selectedItem && (
            <div className="segmented">
              {providerOptions.map((option) => (
                <button
                  type="button"
                  key={option}
                  className={providerFilter === option ? "active" : ""}
                  onClick={() => setProviderFilter(option)}
                >
                  {option === "all" ? t.providerAll : option === "movieffm" ? t.providerMovieffm : t.provider777tv}
                </button>
              ))}
            </div>
          )}
          <div className="lang-switch">
            <button type="button" className={language === "zh-TW" ? "active" : ""} onClick={() => setLanguage("zh-TW")}>
              繁中
            </button>
            <button type="button" className={language === "en" ? "active" : ""} onClick={() => setLanguage("en")}>
              EN
            </button>
          </div>
        </div>
      </nav>

      {/* ── Main ─────────────────────────────────────────────── */}
      <main className="main-content">

        {/* Search skeleton */}
        {searching && (
          <section className="results-section">
            {(providerFilter === "all" ? ["movieffm", "777tv"] : [providerFilter]).map((p) => (
              <div className="results-group" key={p}>
                <div className="skeleton-heading" />
                <div className="poster-grid">
                  {Array.from({ length: 10 }).map((_, i) => (
                    <div
                      key={i}
                      className="skeleton-card"
                      style={{ animationDelay: `${i * 0.06}s` }}
                    />
                  ))}
                </div>
              </div>
            ))}
          </section>
        )}

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
                    </div>
                    <h2>{selectedItem.title}</h2>
                    {detailLoading && <p className="detail-hint">{t.loadingDetails}</p>}
                  </div>
                </div>
                {/* Seasons */}
                {itemDetail?.mediaType === "tv" && itemDetail?.provider === "movieffm" && itemDetail?.seasons?.length > 0 && (
                  <div className="picker-block">
                    <div className="picker-heading"><span>{t.seasons}</span></div>
                    <div className="pill-row">
                      {itemDetail.seasons.map((season) => (
                        <button
                          type="button"
                          key={season.url}
                          className={selectedSeason?.url === season.url ? "active" : ""}
                          onClick={() => handleSelectSeason(season)}
                        >
                          {season.label}
                        </button>
                      ))}
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
                      {episodes.map((episode) => (
                        <button
                          type="button"
                          key={episode}
                          className={selectedEpisode === episode ? "active" : ""}
                          onClick={() =>
                            loadEpisodeSources(
                              itemDetail.provider,
                              itemDetail.provider === "777tv"
                                ? itemDetail.detailUrl
                                : selectedSeason?.url || itemDetail.seasonUrl,
                              episode,
                            )
                          }
                        >
                          {episode}
                        </button>
                      ))}
                    </div>
                  </div>
                )}

                {/* Sources */}
                <div className="picker-block">
                  <div className="picker-heading">
                    <span>{t.availableSources}</span>
                    {!sourcesLoading && <span className="count">{sources.length}</span>}
                  </div>
                  {sourcesLoading && <p className="inline-note">{t.loadingSources}</p>}
                  {!sourcesLoading && sources.length === 0 && <p className="inline-note">{t.noSources}</p>}
                  <div className="source-list">
                    {sources.map((source) => {
                      const mode = getSourcePlaybackMode(source, activeSource, playbackMode);
                      return (
                        <button
                          type="button"
                          key={`${source.sourceLabel}:${source.url}`}
                          className={`source-item ${activeSource?.url === source.url ? "active" : ""}`}
                          onClick={() => setActiveSource(source)}
                        >
                          <span className={`mode-dot ${mode === "proxy" ? "proxy" : "direct"}`} />
                          {source.sourceLabel}
                        </button>
                      );
                    })}
                  </div>
                </div>
              </div>

              {/* Player */}
              <div className="detail-right">
                <div className="player-card">
                  <div className="video-frame">
                    <video ref={videoRef} controls playsInline />
                    {(sourcesLoading || !activeSource) && (
                      <div className="video-blocked">
                        {sourcesLoading ? (
                          <div className="spinner" />
                        ) : null}
                        <p>{sourcesLoading ? t.loadingSources : t.noSources}</p>
                      </div>
                    )}
                  </div>
                  {playerError && <div className="error-box">{playerError}</div>}
                  {activeSource ? (
                    <div className="player-meta">
                      <p>
                        <strong>{activeSource.sourceLabel}</strong>
                        {" · "}
                        {activeSource.episodeLabel}
                      </p>
                      <p>
                        {t.playbackMode}: {playbackMode === "proxy" ? t.playbackProxy : t.playbackDirect}
                      </p>
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
        {groupedResults.length > 0 && (
          <section className="results-section">
            {groupedResults.map((group) => (
              <div className="results-group" key={group.provider}>
                <div className="group-heading">
                  {group.provider}
                  <span className="badge">{group.items.length}</span>
                </div>
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
              </div>
            ))}
          </section>
        )}

      </main>
    </div>
  );
}

export default App;
