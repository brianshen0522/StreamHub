import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import "./player.css";

/* ── icons ─────────────────────────────────────────────────── */

const sp = { viewBox: "0 0 24 24", fill: "none", stroke: "currentColor", strokeWidth: 1.9, strokeLinecap: "round", strokeLinejoin: "round" };
const spFill = { viewBox: "0 0 24 24", fill: "currentColor" };

const IconPlay = () => <svg {...spFill}><path d="M7 4.6v14.8a1 1 0 0 0 1.53.85l11.5-7.4a1 1 0 0 0 0-1.7L8.53 3.75A1 1 0 0 0 7 4.6Z" /></svg>;
const IconPause = () => <svg {...spFill}><rect x="6" y="4" width="4.2" height="16" rx="1.3" /><rect x="13.8" y="4" width="4.2" height="16" rx="1.3" /></svg>;
const IconBack10 = () => <svg {...sp}><path d="M11 5.5 7.5 8.5 11 11.5" /><path d="M7.8 8.5H14a5.5 5.5 0 1 1-5.5 5.5" /><text x="12" y="17.6" textAnchor="middle" fontSize="6.6" fill="currentColor" stroke="none" fontWeight="700">10</text></svg>;
const IconFwd10 = () => <svg {...sp}><path d="M13 5.5 16.5 8.5 13 11.5" /><path d="M16.2 8.5H10a5.5 5.5 0 1 0 5.5 5.5" /><text x="12" y="17.6" textAnchor="middle" fontSize="6.6" fill="currentColor" stroke="none" fontWeight="700">10</text></svg>;
const IconPrev = () => <svg {...spFill}><path d="M7 5.5a1 1 0 0 1 2 0v13a1 1 0 0 1-2 0z" /><path d="M18.4 5.3a1 1 0 0 1 1.6.8v11.8a1 1 0 0 1-1.6.8l-8-5.9a1 1 0 0 1 0-1.6z" /></svg>;
const IconNext = () => <svg {...spFill}><path d="M17 5.5a1 1 0 0 0-2 0v13a1 1 0 0 0 2 0z" /><path d="M5.6 5.3a1 1 0 0 0-1.6.8v11.8a1 1 0 0 0 1.6.8l8-5.9a1 1 0 0 0 0-1.6z" /></svg>;
const IconVolHigh = () => <svg {...sp}><path d="M11 5 6.5 8.8H3.5v6.4h3L11 19z" /><path d="M15.2 9.2a4 4 0 0 1 0 5.6M17.9 6.5a7.8 7.8 0 0 1 0 11" /></svg>;
const IconVolLow = () => <svg {...sp}><path d="M11 5 6.5 8.8H3.5v6.4h3L11 19z" /><path d="M15.2 9.2a4 4 0 0 1 0 5.6" /></svg>;
const IconVolMute = () => <svg {...sp}><path d="M11 5 6.5 8.8H3.5v6.4h3L11 19z" /><path d="m15.5 9.5 5 5M20.5 9.5l-5 5" /></svg>;
const IconSettings = () => <svg {...sp}><circle cx="12" cy="12" r="2.9" /><path d="M19.4 14.4a1.6 1.6 0 0 0 .32 1.77l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.6 1.6 0 0 0-1.77-.32 1.6 1.6 0 0 0-1 1.46V21a2 2 0 1 1-4 0v-.11a1.6 1.6 0 0 0-1.05-1.46 1.6 1.6 0 0 0-1.77.32l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.6 1.6 0 0 0 .32-1.77 1.6 1.6 0 0 0-1.46-1H3a2 2 0 1 1 0-4h.11a1.6 1.6 0 0 0 1.46-1.05 1.6 1.6 0 0 0-.32-1.77l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.6 1.6 0 0 0 1.77.32H9a1.6 1.6 0 0 0 1-1.46V3a2 2 0 1 1 4 0v.11a1.6 1.6 0 0 0 1 1.46 1.6 1.6 0 0 0 1.77-.32l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.6 1.6 0 0 0-.32 1.77V9a1.6 1.6 0 0 0 1.46 1H21a2 2 0 1 1 0 4h-.11a1.6 1.6 0 0 0-1.46 1Z" /></svg>;
const IconCaptions = () => <svg {...sp}><rect x="2.5" y="5" width="19" height="14" rx="2.5" /><path d="M9.2 10.4a2.2 2.2 0 1 0 0 3.2M16.4 10.4a2.2 2.2 0 1 0 0 3.2" /></svg>;
const IconPip = () => <svg {...sp}><rect x="2.5" y="4.5" width="19" height="15" rx="2.5" /><rect x="12.5" y="11.5" width="7" height="6" rx="1.3" fill="currentColor" stroke="none" /></svg>;
const IconExpand = () => <svg {...sp}><path d="M8.5 3.5h-5v5M15.5 3.5h5v5M15.5 20.5h5v-5M8.5 20.5h-5v-5" /></svg>;
const IconCollapse = () => <svg {...sp}><path d="M3.5 8.5h5v-5M20.5 8.5h-5v-5M20.5 15.5h-5v5M3.5 15.5h5v5" /></svg>;
const IconSpeed = () => <svg {...sp}><path d="M12 20a8 8 0 1 1 8-8" /><path d="m12 12 4.2-3.2" /><circle cx="12" cy="12" r="1.4" fill="currentColor" stroke="none" /></svg>;
const IconQuality = () => <svg {...sp}><rect x="2.5" y="5" width="19" height="14" rx="2.5" /><path d="M7.5 14.5v-5l2.2 3 2.3-3v5M16 9.5v5h2.5" /></svg>;
const IconCheck = () => <svg {...sp}><path d="m5 12.5 4.5 4.5L19 7" /></svg>;
const IconChevronLeft = () => <svg {...sp}><path d="M14.5 5 8 12l6.5 7" /></svg>;
const IconChevronRight = () => <svg {...sp}><path d="M9.5 5 16 12l-6.5 7" /></svg>;
const IconKeyboard = () => <svg {...sp}><rect x="2" y="6" width="20" height="12" rx="2.5" /><path d="M6 10h.01M9.5 10h.01M13 10h.01M16.5 10h.01M6 13.6h.01M9.5 13.6h5M18 13.6h.01" /></svg>;
const IconClose = () => <svg {...sp}><path d="M18 6 6 18M6 6l12 12" /></svg>;

/* ── helpers ───────────────────────────────────────────────── */

function formatTime(seconds) {
  const total = Math.max(0, Math.floor(Number(seconds) || 0));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const pad = (n) => String(n).padStart(2, "0");
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`;
}

const RATES = [0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2];
const VOLUME_KEY = "streamhub.player.volume";
const RATE_KEY = "streamhub.player.rate";

function readStored(key, fallback) {
  try {
    const raw = window.localStorage.getItem(key);
    if (raw === null) return fallback;
    const value = Number.parseFloat(raw);
    return Number.isFinite(value) ? value : fallback;
  } catch {
    return fallback;
  }
}

function levelLabel(level) {
  if (level?.height) return `${level.height}p`;
  if (level?.bitrate) return `${Math.round(level.bitrate / 1000)} kbps`;
  return "—";
}

/**
 * Custom chrome around a plain <video>. The element itself is handed back
 * through `videoRef` so the page keeps owning source loading, hls.js, and
 * progress syncing — this component only drives playback UI.
 */
export default function VideoPlayer({
  videoRef,
  hls,
  title,
  subtitle,
  blocked,
  overlay,
  onPrev,
  onNext,
  prevLabel,
  nextLabel,
  adCuts,
  t,
}) {
  const frameRef = useRef(null);
  const seekRef = useRef(null);
  const idleTimer = useRef(null);
  const flashTimer = useRef(null);
  const tapRef = useRef({ time: 0, side: null });

  const [videoEl, setVideoEl] = useState(null);
  const [playing, setPlaying] = useState(false);
  const [ready, setReady] = useState(false);
  const [waiting, setWaiting] = useState(false);
  const [duration, setDuration] = useState(0);
  const [currentTime, setCurrentTime] = useState(0);
  const [buffered, setBuffered] = useState([]);
  const [volume, setVolume] = useState(() => readStored(VOLUME_KEY, 1));
  const [muted, setMuted] = useState(false);
  const [rate, setRate] = useState(() => readStored(RATE_KEY, 1));
  const [fullscreen, setFullscreen] = useState(false);
  const [pip, setPip] = useState(false);
  const [idle, setIdle] = useState(false);
  const [menu, setMenu] = useState(null);        // null | "root" | "speed" | "quality" | "captions"
  const [dragging, setDragging] = useState(false);
  const [dragTime, setDragTime] = useState(0);
  const [hoverX, setHoverX] = useState(null);
  const [flash, setFlash] = useState(null);
  const [showHelp, setShowHelp] = useState(false);
  const [showRemaining, setShowRemaining] = useState(false);
  const [levels, setLevels] = useState([]);
  const [currentLevel, setCurrentLevel] = useState(-1);
  const [autoLevel, setAutoLevel] = useState(-1);
  const [subtitles, setSubtitles] = useState([]);
  const [subtitleTrack, setSubtitleTrack] = useState(-1);

  const attachRef = useCallback((node) => {
    videoRef.current = node;
    setVideoEl(node);
  }, [videoRef]);

  const showFlash = useCallback((label, icon) => {
    setFlash({ label, icon, id: Math.random() });
    window.clearTimeout(flashTimer.current);
    flashTimer.current = window.setTimeout(() => setFlash(null), 900);
  }, []);

  /* ── media element wiring ─────────────────────────────────── */

  useEffect(() => {
    if (!videoEl) return undefined;

    const readBuffered = () => {
      const ranges = [];
      for (let i = 0; i < videoEl.buffered.length; i += 1) {
        ranges.push({ start: videoEl.buffered.start(i), end: videoEl.buffered.end(i) });
      }
      setBuffered(ranges);
    };

    const onPlay = () => setPlaying(true);
    const onPause = () => setPlaying(false);
    const onTime = () => { setCurrentTime(videoEl.currentTime); readBuffered(); };
    const onMeta = () => { setDuration(videoEl.duration || 0); setReady(true); };
    const onWaiting = () => setWaiting(true);
    const onPlaying = () => { setWaiting(false); setPlaying(true); };
    const onVolume = () => { setVolume(videoEl.volume); setMuted(videoEl.muted); };
    const onRate = () => setRate(videoEl.playbackRate);
    const onEnded = () => setPlaying(false);
    const onEnterPip = () => setPip(true);
    const onLeavePip = () => setPip(false);

    videoEl.addEventListener("play", onPlay);
    videoEl.addEventListener("pause", onPause);
    videoEl.addEventListener("timeupdate", onTime);
    videoEl.addEventListener("progress", readBuffered);
    videoEl.addEventListener("durationchange", onMeta);
    videoEl.addEventListener("loadedmetadata", onMeta);
    videoEl.addEventListener("waiting", onWaiting);
    videoEl.addEventListener("playing", onPlaying);
    videoEl.addEventListener("canplay", () => setWaiting(false));
    videoEl.addEventListener("volumechange", onVolume);
    videoEl.addEventListener("ratechange", onRate);
    videoEl.addEventListener("ended", onEnded);
    videoEl.addEventListener("enterpictureinpicture", onEnterPip);
    videoEl.addEventListener("leavepictureinpicture", onLeavePip);

    // Apply the persisted preferences to a freshly mounted element.
    videoEl.volume = volume;
    videoEl.playbackRate = rate;

    return () => {
      videoEl.removeEventListener("play", onPlay);
      videoEl.removeEventListener("pause", onPause);
      videoEl.removeEventListener("timeupdate", onTime);
      videoEl.removeEventListener("progress", readBuffered);
      videoEl.removeEventListener("durationchange", onMeta);
      videoEl.removeEventListener("loadedmetadata", onMeta);
      videoEl.removeEventListener("waiting", onWaiting);
      videoEl.removeEventListener("playing", onPlaying);
      videoEl.removeEventListener("volumechange", onVolume);
      videoEl.removeEventListener("ratechange", onRate);
      videoEl.removeEventListener("ended", onEnded);
      videoEl.removeEventListener("enterpictureinpicture", onEnterPip);
      videoEl.removeEventListener("leavepictureinpicture", onLeavePip);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [videoEl]);

  // A new source resets duration/position until metadata lands again.
  useEffect(() => {
    setReady(false);
    setDuration(0);
    setCurrentTime(0);
    setBuffered([]);
    setLevels([]);
    setCurrentLevel(-1);
    setSubtitles([]);
    setSubtitleTrack(-1);
  }, [hls]);

  /* ── hls.js: quality levels + subtitle tracks ─────────────── */

  useEffect(() => {
    if (!hls) return undefined;

    const syncLevels = () => {
      setLevels(hls.levels || []);
      setCurrentLevel(hls.currentLevel ?? -1);
    };
    const syncSubs = () => {
      setSubtitles(hls.subtitleTracks || []);
      setSubtitleTrack(hls.subtitleTrack ?? -1);
    };
    const onLevelSwitched = (_e, data) => setAutoLevel(data?.level ?? -1);

    syncLevels();
    syncSubs();

    hls.on("hlsManifestParsed", syncLevels);
    hls.on("hlsLevelSwitched", onLevelSwitched);
    hls.on("hlsSubtitleTracksUpdated", syncSubs);
    hls.on("hlsSubtitleTrackSwitch", syncSubs);

    return () => {
      hls.off("hlsManifestParsed", syncLevels);
      hls.off("hlsLevelSwitched", onLevelSwitched);
      hls.off("hlsSubtitleTracksUpdated", syncSubs);
      hls.off("hlsSubtitleTrackSwitch", syncSubs);
    };
  }, [hls]);

  /* ── fullscreen ───────────────────────────────────────────── */

  useEffect(() => {
    const onChange = () => setFullscreen(document.fullscreenElement === frameRef.current);
    document.addEventListener("fullscreenchange", onChange);
    onChange();
    return () => document.removeEventListener("fullscreenchange", onChange);
  }, []);

  /* ── controls auto-hide ───────────────────────────────────── */

  const wake = useCallback(() => {
    setIdle(false);
    window.clearTimeout(idleTimer.current);
    idleTimer.current = window.setTimeout(() => setIdle(true), 2800);
  }, []);

  useEffect(() => {
    if (!playing || menu || showHelp || dragging) {
      window.clearTimeout(idleTimer.current);
      setIdle(false);
      return undefined;
    }
    wake();
    return () => window.clearTimeout(idleTimer.current);
  }, [playing, menu, showHelp, dragging, wake]);

  /* ── actions ──────────────────────────────────────────────── */

  const togglePlay = useCallback(() => {
    if (!videoEl) return;
    if (videoEl.paused) void videoEl.play().catch(() => {});
    else videoEl.pause();
  }, [videoEl]);

  const seekBy = useCallback((delta) => {
    if (!videoEl || !Number.isFinite(videoEl.duration)) return;
    videoEl.currentTime = Math.min(videoEl.duration, Math.max(0, videoEl.currentTime + delta));
    showFlash(`${delta > 0 ? "+" : ""}${Math.round(delta)}s`, delta > 0 ? <IconFwd10 /> : <IconBack10 />);
  }, [videoEl, showFlash]);

  const seekTo = useCallback((seconds) => {
    if (!videoEl || !Number.isFinite(videoEl.duration)) return;
    videoEl.currentTime = Math.min(videoEl.duration, Math.max(0, seconds));
  }, [videoEl]);

  const applyVolume = useCallback((next) => {
    if (!videoEl) return;
    const clamped = Math.min(1, Math.max(0, next));
    videoEl.volume = clamped;
    videoEl.muted = clamped === 0;
    try { window.localStorage.setItem(VOLUME_KEY, String(clamped)); } catch { /* private mode */ }
  }, [videoEl]);

  const toggleMute = useCallback(() => {
    if (!videoEl) return;
    videoEl.muted = !videoEl.muted;
    showFlash(videoEl.muted ? t.vpMuted : t.vpUnmuted, videoEl.muted ? <IconVolMute /> : <IconVolHigh />);
  }, [videoEl, showFlash, t]);

  const applyRate = useCallback((next) => {
    if (!videoEl) return;
    videoEl.playbackRate = next;
    try { window.localStorage.setItem(RATE_KEY, String(next)); } catch { /* private mode */ }
    showFlash(`${next}×`, <IconSpeed />);
  }, [videoEl, showFlash]);

  const toggleFullscreen = useCallback(() => {
    const frame = frameRef.current;
    if (!frame) return;
    if (document.fullscreenElement === frame) void document.exitFullscreen().catch(() => {});
    else void frame.requestFullscreen?.().catch(() => {});
  }, []);

  const togglePip = useCallback(async () => {
    if (!videoEl || !document.pictureInPictureEnabled) return;
    try {
      if (document.pictureInPictureElement === videoEl) await document.exitPictureInPicture();
      else await videoEl.requestPictureInPicture();
    } catch { /* the element may not be ready yet */ }
  }, [videoEl]);

  const selectLevel = useCallback((index) => {
    if (!hls) return;
    hls.currentLevel = index;
    setCurrentLevel(index);
    setMenu(null);
    showFlash(index === -1 ? t.vpAuto : levelLabel(hls.levels?.[index]), <IconQuality />);
  }, [hls, showFlash, t]);

  const selectSubtitle = useCallback((index) => {
    if (hls) {
      hls.subtitleTrack = index;
      setSubtitleTrack(index);
    } else if (videoEl) {
      for (let i = 0; i < videoEl.textTracks.length; i += 1) {
        videoEl.textTracks[i].mode = i === index ? "showing" : "disabled";
      }
      setSubtitleTrack(index);
    }
    setMenu(null);
  }, [hls, videoEl]);

  /* ── keyboard shortcuts ───────────────────────────────────── */

  useEffect(() => {
    if (!videoEl) return undefined;

    function onKey(event) {
      // Never hijack typing (the page has a search field) or activation keys
      // aimed at a focused control.
      const target = event.target;
      const tag = target?.tagName;
      if (
        target?.isContentEditable ||
        tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" ||
        ((tag === "BUTTON" || tag === "A") && (event.key === " " || event.key === "Enter"))
      ) return;
      if (event.metaKey || event.ctrlKey || event.altKey) return;

      const key = event.key;
      let handled = true;

      switch (key) {
        case " ":
        case "k": togglePlay(); break;
        case "ArrowLeft": seekBy(-5); break;
        case "ArrowRight": seekBy(5); break;
        case "j": seekBy(-10); break;
        case "l": seekBy(10); break;
        case "ArrowUp": applyVolume((videoEl.volume ?? 0) + 0.1); showFlash(`${Math.round(Math.min(1, videoEl.volume + 0.1) * 100)}%`, <IconVolHigh />); break;
        case "ArrowDown": applyVolume((videoEl.volume ?? 0) - 0.1); showFlash(`${Math.round(Math.max(0, videoEl.volume - 0.1) * 100)}%`, <IconVolLow />); break;
        case "m": toggleMute(); break;
        case "f": toggleFullscreen(); break;
        case "p": void togglePip(); break;
        case "c": if (subtitles.length) selectSubtitle(subtitleTrack === -1 ? 0 : -1); break;
        case "<": applyRate(RATES[Math.max(0, RATES.indexOf(rate) - 1)]); break;
        case ">": applyRate(RATES[Math.min(RATES.length - 1, RATES.indexOf(rate) + 1)]); break;
        case "?": setShowHelp((v) => !v); break;
        case "Escape": if (menu) setMenu(null); else if (showHelp) setShowHelp(false); else handled = false; break;
        default:
          if (/^[0-9]$/.test(key) && Number.isFinite(videoEl.duration)) {
            seekTo((Number(key) / 10) * videoEl.duration);
          } else {
            handled = false;
          }
      }

      if (handled) {
        event.preventDefault();
        wake();
      }
    }

    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [videoEl, togglePlay, seekBy, seekTo, applyVolume, toggleMute, toggleFullscreen, togglePip,
      selectSubtitle, subtitles.length, subtitleTrack, applyRate, rate, menu, showHelp, wake, showFlash]);

  /* ── seek bar interaction ─────────────────────────────────── */

  const fractionFromEvent = useCallback((event) => {
    const rect = seekRef.current?.getBoundingClientRect();
    if (!rect || rect.width === 0) return 0;
    return Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width));
  }, []);

  const onSeekPointerDown = useCallback((event) => {
    if (!duration) return;
    event.currentTarget.setPointerCapture?.(event.pointerId);
    setDragging(true);
    setDragTime(fractionFromEvent(event) * duration);
  }, [duration, fractionFromEvent]);

  const onSeekPointerMove = useCallback((event) => {
    if (!duration) return;
    const fraction = fractionFromEvent(event);
    setHoverX(fraction);
    if (dragging) setDragTime(fraction * duration);
  }, [duration, dragging, fractionFromEvent]);

  const onSeekPointerUp = useCallback((event) => {
    if (!dragging) return;
    event.currentTarget.releasePointerCapture?.(event.pointerId);
    seekTo(fractionFromEvent(event) * duration);
    setDragging(false);
  }, [dragging, duration, fractionFromEvent, seekTo]);

  /* ── double-tap-to-seek on touch ──────────────────────────── */

  const onSurfacePointerUp = useCallback((event) => {
    if (event.pointerType !== "touch") return;
    const rect = frameRef.current?.getBoundingClientRect();
    if (!rect) return;
    const relative = (event.clientX - rect.left) / rect.width;
    const side = relative < 0.32 ? "left" : relative > 0.68 ? "right" : "center";
    const now = Date.now();
    const previous = tapRef.current;

    if (side !== "center" && previous.side === side && now - previous.time < 320) {
      seekBy(side === "left" ? -10 : 10);
      tapRef.current = { time: 0, side: null };
      return;
    }
    tapRef.current = { time: now, side };
  }, [seekBy]);

  /* ── derived ──────────────────────────────────────────────── */

  // Any cut within ~1.5% of the cursor counts as hovered, so the thin tick
  // does not require pixel-perfect aim.
  const hoveredCut = useMemo(() => {
    if (hoverX === null || !duration || !adCuts?.length) return null;
    const tolerance = duration * 0.015;
    return adCuts.find((cut) => Math.abs(cut.at - hoverX * duration) <= tolerance) || null;
  }, [hoverX, duration, adCuts]);

  const shownTime = dragging ? dragTime : currentTime;
  const playedFraction = duration > 0 ? Math.min(1, shownTime / duration) : 0;
  const hasHls = !!hls && levels.length > 1;
  const activeLevelLabel = currentLevel === -1
    ? `${t.vpAuto}${autoLevel >= 0 && levels[autoLevel] ? ` · ${levelLabel(levels[autoLevel])}` : ""}`
    : levelLabel(levels[currentLevel]);
  const activeSubtitleLabel = subtitleTrack === -1
    ? t.vpOff
    : subtitles[subtitleTrack]?.name || subtitles[subtitleTrack]?.lang || t.vpOn;

  const shortcuts = useMemo(() => [
    ["Space / K", t.vpKeyPlay],
    ["← / →", t.vpKeySeek5],
    ["J / L", t.vpKeySeek10],
    ["↑ / ↓", t.vpKeyVolume],
    ["0 – 9", t.vpKeyJump],
    ["M", t.vpKeyMute],
    ["F", t.vpKeyFullscreen],
    ["P", t.vpKeyPip],
    ["C", t.vpKeyCaptions],
    ["< / >", t.vpKeySpeed],
  ], [t]);

  const volumeIcon = muted || volume === 0 ? <IconVolMute /> : volume < 0.5 ? <IconVolLow /> : <IconVolHigh />;

  return (
    <div
      ref={frameRef}
      className={`vp${idle && playing ? " is-idle" : ""}`}
      onPointerMove={wake}
      onPointerLeave={() => { setHoverX(null); if (playing) setIdle(true); }}
    >
      <video ref={attachRef} className="vp-video" playsInline />

      {/* click / double-click / double-tap surface */}
      <div
        className="vp-surface"
        onClick={() => { if (!blocked) togglePlay(); }}
        onDoubleClick={toggleFullscreen}
        onPointerUp={onSurfacePointerUp}
      />

      <div className={`vp-tap-zone vp-tap-zone-left${flash?.icon && flash.label.startsWith("-") ? " is-flash" : ""}`} />
      <div className={`vp-tap-zone vp-tap-zone-right${flash?.icon && flash.label.startsWith("+") ? " is-flash" : ""}`} />

      <div className="vp-topbar">
        <div style={{ minWidth: 0 }}>
          {title ? <div className="vp-title">{title}</div> : null}
          {subtitle ? <div className="vp-subtitle">{subtitle}</div> : null}
        </div>
        <div className="vp-spacer" />
        <button type="button" className="vp-btn vp-hide-sm" onClick={() => setShowHelp(true)} title={t.vpShortcuts} aria-label={t.vpShortcuts}>
          <IconKeyboard />
        </button>
      </div>

      <div className="vp-center">
        {waiting && !blocked ? <div className="vp-spinner" /> : (
          <button
            type="button"
            className={`vp-bigplay${playing || blocked ? " is-hidden" : ""}`}
            onClick={togglePlay}
            aria-label={playing ? t.vpPause : t.vpPlay}
            tabIndex={playing || blocked ? -1 : 0}
          >
            {playing ? <IconPause /> : <IconPlay />}
          </button>
        )}
      </div>

      {flash ? (
        <div className="vp-flash" key={flash.id}>
          {flash.icon}
          <span>{flash.label}</span>
        </div>
      ) : null}

      <div className="vp-controls" onPointerMove={wake}>
        <div
          ref={seekRef}
          className={`vp-seek${dragging ? " is-dragging" : ""}`}
          role="slider"
          aria-label={t.vpSeek}
          aria-valuemin={0}
          aria-valuemax={Math.round(duration)}
          aria-valuenow={Math.round(shownTime)}
          aria-valuetext={formatTime(shownTime)}
          tabIndex={0}
          onPointerDown={onSeekPointerDown}
          onPointerMove={onSeekPointerMove}
          onPointerUp={onSeekPointerUp}
          onPointerLeave={() => setHoverX(null)}
        >
          <div className="vp-seek-track">
            {buffered.map((range, index) => (
              <div
                key={index}
                className="vp-seek-buffered"
                style={{
                  left: `${duration ? (range.start / duration) * 100 : 0}%`,
                  width: `${duration ? ((range.end - range.start) / duration) * 100 : 0}%`,
                }}
              />
            ))}
            <div className="vp-seek-played" style={{ width: `${playedFraction * 100}%` }} />
          </div>
          {/* Rendered outside the track so the marker is not clipped to 4px. */}
          {duration > 0 && adCuts?.length
            ? adCuts.map((cut, index) => (
                <div
                  key={`${cut.at}-${index}`}
                  className="vp-seek-adcut"
                  style={{ left: `${Math.min(99.7, (cut.at / duration) * 100)}%` }}
                  title={t.vpAdRemoved.replace("{s}", Math.round(cut.removed))}
                />
              ))
            : null}
          <div className="vp-seek-thumb" style={{ left: `${playedFraction * 100}%` }} />
          {hoverX !== null && duration > 0 ? (
            <div className="vp-seek-tip" style={{ left: `${hoverX * 100}%` }}>
              {formatTime(hoverX * duration)}
              {hoveredCut ? (
                <span className="vp-seek-tip-ad">{t.vpAdRemoved.replace("{s}", Math.round(hoveredCut.removed))}</span>
              ) : null}
            </div>
          ) : null}

        </div>

        <div className="vp-row">
          <button type="button" className="vp-btn" onClick={togglePlay} aria-label={playing ? t.vpPause : t.vpPlay} title={`${playing ? t.vpPause : t.vpPlay} (K)`}>
            {playing ? <IconPause /> : <IconPlay />}
          </button>

          {onPrev ? (
            <button type="button" className="vp-btn vp-hide-sm" onClick={onPrev} disabled={!prevLabel} title={prevLabel ? `${t.vpPrevious}: ${prevLabel}` : t.vpPrevious} aria-label={t.vpPrevious}>
              <IconPrev />
            </button>
          ) : null}

          <button type="button" className="vp-btn" onClick={() => seekBy(-10)} title={`${t.vpBack10} (J)`} aria-label={t.vpBack10}>
            <IconBack10 />
          </button>
          <button type="button" className="vp-btn" onClick={() => seekBy(10)} title={`${t.vpForward10} (L)`} aria-label={t.vpForward10}>
            <IconFwd10 />
          </button>

          {onNext ? (
            <button type="button" className="vp-btn" onClick={onNext} disabled={!nextLabel} title={nextLabel ? `${t.vpNext}: ${nextLabel}` : t.vpNext} aria-label={t.vpNext}>
              <IconNext />
            </button>
          ) : null}

          <div className="vp-volume">
            <button type="button" className="vp-btn" onClick={toggleMute} title={`${muted ? t.vpUnmute : t.vpMute} (M)`} aria-label={muted ? t.vpUnmute : t.vpMute}>
              {volumeIcon}
            </button>
            <div className="vp-volume-slider" style={{ "--vp-vol": `${(muted ? 0 : volume) * 100}%` }}>
              <input
                type="range"
                min="0"
                max="1"
                step="0.01"
                value={muted ? 0 : volume}
                aria-label={t.vpVolume}
                onChange={(event) => applyVolume(Number.parseFloat(event.target.value))}
              />
            </div>
          </div>

          <button type="button" className="vp-time" onClick={() => setShowRemaining((v) => !v)} title={t.vpToggleRemaining}>
            <b>{formatTime(shownTime)}</b>
            {" / "}
            {showRemaining && duration ? `-${formatTime(duration - shownTime)}` : formatTime(duration)}
          </button>

          <div className="vp-spacer" />

          {rate !== 1 ? (
            <button type="button" className="vp-btn vp-btn-wide" onClick={() => setMenu("speed")} title={t.vpSpeed}>
              {rate}×
            </button>
          ) : null}

          {subtitles.length > 0 ? (
            <button
              type="button"
              className={`vp-btn${subtitleTrack !== -1 ? " is-on" : ""}`}
              onClick={() => setMenu("captions")}
              title={`${t.vpCaptions} (C)`}
              aria-label={t.vpCaptions}
            >
              <IconCaptions />
            </button>
          ) : null}

          <div className="vp-menu-anchor">
            <button
              type="button"
              className={`vp-btn${menu ? " is-on" : ""}`}
              onClick={() => setMenu((current) => (current ? null : "root"))}
              title={t.vpSettings}
              aria-label={t.vpSettings}
              aria-haspopup="menu"
              aria-expanded={!!menu}
            >
              <IconSettings />
            </button>

            {menu ? (
              <div className="vp-menu" role="menu">
                {menu === "root" ? (
                  <>
                    <button type="button" className="vp-menu-item" onClick={() => setMenu("speed")} role="menuitem">
                      <IconSpeed />
                      {t.vpSpeed}
                      <span className="vp-menu-item-value">{rate === 1 ? t.vpNormal : `${rate}×`}<IconChevronRight /></span>
                    </button>
                    {hasHls ? (
                      <button type="button" className="vp-menu-item" onClick={() => setMenu("quality")} role="menuitem">
                        <IconQuality />
                        {t.vpQuality}
                        <span className="vp-menu-item-value">{activeLevelLabel}<IconChevronRight /></span>
                      </button>
                    ) : null}
                    {subtitles.length > 0 ? (
                      <button type="button" className="vp-menu-item" onClick={() => setMenu("captions")} role="menuitem">
                        <IconCaptions />
                        {t.vpCaptions}
                        <span className="vp-menu-item-value">{activeSubtitleLabel}<IconChevronRight /></span>
                      </button>
                    ) : null}
                    <button type="button" className="vp-menu-item" onClick={() => { setMenu(null); setShowHelp(true); }} role="menuitem">
                      <IconKeyboard />
                      {t.vpShortcuts}
                    </button>
                  </>
                ) : null}

                {menu === "speed" ? (
                  <>
                    <div className="vp-menu-head">
                      <button type="button" className="vp-menu-back" onClick={() => setMenu("root")} aria-label={t.vpBack}><IconChevronLeft /></button>
                      {t.vpSpeed}
                    </div>
                    {RATES.map((value) => (
                      <button
                        key={value}
                        type="button"
                        role="menuitemradio"
                        aria-checked={rate === value}
                        className={`vp-menu-item${rate === value ? " is-selected" : ""}`}
                        onClick={() => { applyRate(value); setMenu(null); }}
                      >
                        {value === 1 ? t.vpNormal : `${value}×`}
                        <span className="vp-menu-check"><IconCheck /></span>
                      </button>
                    ))}
                  </>
                ) : null}

                {menu === "quality" ? (
                  <>
                    <div className="vp-menu-head">
                      <button type="button" className="vp-menu-back" onClick={() => setMenu("root")} aria-label={t.vpBack}><IconChevronLeft /></button>
                      {t.vpQuality}
                    </div>
                    <button
                      type="button"
                      role="menuitemradio"
                      aria-checked={currentLevel === -1}
                      className={`vp-menu-item${currentLevel === -1 ? " is-selected" : ""}`}
                      onClick={() => selectLevel(-1)}
                    >
                      {t.vpAuto}
                      {autoLevel >= 0 && levels[autoLevel] ? <span className="vp-menu-sub">{levelLabel(levels[autoLevel])}</span> : null}
                      <span className="vp-menu-check"><IconCheck /></span>
                    </button>
                    {levels.map((level, index) => (
                      <button
                        key={`${level.height}-${level.bitrate}-${index}`}
                        type="button"
                        role="menuitemradio"
                        aria-checked={currentLevel === index}
                        className={`vp-menu-item${currentLevel === index ? " is-selected" : ""}`}
                        onClick={() => selectLevel(index)}
                      >
                        {levelLabel(level)}
                        {level.bitrate ? <span className="vp-menu-sub">{Math.round(level.bitrate / 1000)}k</span> : null}
                        <span className="vp-menu-check"><IconCheck /></span>
                      </button>
                    ))}
                  </>
                ) : null}

                {menu === "captions" ? (
                  <>
                    <div className="vp-menu-head">
                      <button type="button" className="vp-menu-back" onClick={() => setMenu("root")} aria-label={t.vpBack}><IconChevronLeft /></button>
                      {t.vpCaptions}
                    </div>
                    <button
                      type="button"
                      role="menuitemradio"
                      aria-checked={subtitleTrack === -1}
                      className={`vp-menu-item${subtitleTrack === -1 ? " is-selected" : ""}`}
                      onClick={() => selectSubtitle(-1)}
                    >
                      {t.vpOff}
                      <span className="vp-menu-check"><IconCheck /></span>
                    </button>
                    {subtitles.map((track, index) => (
                      <button
                        key={`${track.lang}-${index}`}
                        type="button"
                        role="menuitemradio"
                        aria-checked={subtitleTrack === index}
                        className={`vp-menu-item${subtitleTrack === index ? " is-selected" : ""}`}
                        onClick={() => selectSubtitle(index)}
                      >
                        {track.name || track.lang || `${t.vpTrack} ${index + 1}`}
                        <span className="vp-menu-check"><IconCheck /></span>
                      </button>
                    ))}
                  </>
                ) : null}
              </div>
            ) : null}
          </div>



          {document.pictureInPictureEnabled ? (
            <button type="button" className={`vp-btn vp-hide-sm${pip ? " is-on" : ""}`} onClick={togglePip} title={`${t.vpPip} (P)`} aria-label={t.vpPip}>
              <IconPip />
            </button>
          ) : null}

          <button type="button" className="vp-btn" onClick={toggleFullscreen} title={`${fullscreen ? t.vpExitFullscreen : t.vpFullscreen} (F)`} aria-label={fullscreen ? t.vpExitFullscreen : t.vpFullscreen}>
            {fullscreen ? <IconCollapse /> : <IconExpand />}
          </button>
        </div>
      </div>

      {overlay ? <div className="vp-overlay">{overlay}</div> : null}
      {blocked ? <div className="vp-blocked">{blocked}</div> : null}

      {showHelp ? (
        <div className="vp-help" onClick={() => setShowHelp(false)}>
          <div className="vp-help-card" onClick={(event) => event.stopPropagation()}>
            <div className="vp-help-head">
              {t.vpShortcuts}
              <button type="button" className="vp-btn" onClick={() => setShowHelp(false)} aria-label={t.vpClose}><IconClose /></button>
            </div>
            <div className="vp-help-grid">
              {shortcuts.map(([keys, label]) => (
                <div key={keys} className="vp-help-row">
                  <span>{label}</span>
                  <span className="vp-kbd">{keys}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
      ) : null}

      {ready ? null : null}
    </div>
  );
}
