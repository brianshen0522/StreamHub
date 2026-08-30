import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useCast } from "./cast.js";
import { getRealtimeDeviceName } from "./realtime.js";

/**
 * The other hand on the same remote-controlled device, if any.
 *
 * The receiver reports who last drove it; showing that back — except when it
 * is this device itself — is what keeps two remotes from reading each other's
 * presses as glitches: the state that keeps jumping has a name attached.
 */
function otherController(state) {
  const name = state?.controlledBy;
  if (!name) return null;
  const own = getRealtimeDeviceName();
  return own && name === own ? null : name;
}

/**
 * Casting, for the browser.
 *
 * Laid out the way the phone apps are, because they drive the same televisions
 * and a person moving between them should not have to learn it twice: a button
 * that only exists when there is somewhere to cast to, a bar that keeps the
 * session reachable from any page, and a remote that is a picture of what the
 * television is doing.
 */

/**
 * A backdrop that closes only on a press that both starts and ends on it.
 *
 * Closing on the click alone is not enough: the overlay mounts during the very
 * gesture that opened it, so the release can land on a backdrop that did not
 * exist when the finger went down, and the sheet shuts the instant it appears.
 */
function Scrim({ onClose, children }) {
  const pressedHere = useRef(false);
  // Rendered into <body>, not in place. The cast button lives inside the
  // topbar, and the topbar's backdrop-filter makes it the containing block for
  // any fixed-position descendant — so "inset: 0" meant the topbar's box, and
  // the sheet drew pinned to the top of the screen, clipped, with its scrim
  // dimming a 56px strip. A portal is immune to what any ancestor declares.
  return createPortal(
    <div
      className="cast-scrim"
      role="dialog"
      aria-modal="true"
      onPointerDown={(event) => { pressedHere.current = event.target === event.currentTarget; }}
      onClick={(event) => {
        if (event.target === event.currentTarget && pressedHere.current) onClose();
        pressedHere.current = false;
      }}
    >
      {children}
    </div>,
    document.body,
  );
}

function CastGlyph({ on }) {
  return (
    <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" fill="none">
      <rect
        x="3" y="4" width="18" height="14" rx="2"
        stroke="currentColor" strokeWidth="2"
        opacity={on ? 0.45 : 1}
      />
      {on ? <rect x="5" y="6" width="14" height="10" rx="1" fill="currentColor" /> : null}
      <path d="M3 16a4 4 0 0 1 4 4" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
      <path d="M3 12a8 8 0 0 1 8 8" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
      <circle cx="3.4" cy="20" r="1.4" fill="currentColor" />
    </svg>
  );
}

/**
 * Opens the device picker. Renders nothing when there is nowhere to cast to.
 *
 * A control that is always there but usually does nothing teaches people to
 * ignore it; this one appearing is itself the signal that a television is on.
 */
export function CastButton({ t }) {
  const cast = useCast();

  if (!cast.controllable.length && !cast.target) return null;

  return (
    <>
      <button
        type="button"
        className={`cast-btn${cast.target ? " cast-btn-on" : ""}`}
        onClick={cast.openPicker}
        title={t?.castTo || "Play on another device"}
        aria-label={t?.castTo || "Play on another device"}
      >
        <CastGlyph on={Boolean(cast.target)} />
      </button>
      {cast.pickerOpen ? <CastPicker cast={cast} t={t} onClose={cast.closePicker} /> : null}
    </>
  );
}

function CastPicker({ cast, t, onClose }) {
  useEffect(() => {
    function onKey(event) { if (event.key === "Escape") onClose(); }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <Scrim onClose={onClose}>
      <div className="cast-sheet">
        <div className="cast-sheet-title">{t?.playOn || "Play on"}</div>

        <button
          type="button"
          className={`cast-device${cast.target ? "" : " cast-device-on"}`}
          onClick={() => { cast.disconnect(); onClose(); }}
        >
          <CastGlyph on={!cast.target} />
          <span>
            <b>{t?.thisDevice || "This device"}</b>
            <small>{t?.playHere || "Play here"}</small>
          </span>
        </button>

        {cast.controllable.map((receiver) => (
          <button
            key={receiver.sessionId}
            type="button"
            className={`cast-device${receiver.sessionId === cast.target?.sessionId ? " cast-device-on" : ""}`}
            onClick={() => { cast.connect(receiver.sessionId); onClose(); }}
          >
            <CastGlyph on={receiver.sessionId === cast.target?.sessionId} />
            <span>
              <b>{receiver.deviceName}</b>
              <small>
                {receiver.state?.title
                  ? `${t?.playing || "Playing"} ${receiver.state.title}`
                  : (t?.ready || "Ready")}
              </small>
            </span>
          </button>
        ))}
      </div>
    </Scrim>
  );
}

/**
 * The strip that keeps a remote session reachable.
 *
 * Without it, navigating away from the player is indistinguishable from
 * stopping the cast: the television keeps playing and this tab shows no sign
 * of it.
 */
export function CastBar({ t }) {
  const cast = useCast();
  const [open, setOpen] = useState(false);

  // The bar is fixed over the page, so the page needs to know to leave room
  // for it — but only while it is there.
  useEffect(() => {
    const on = Boolean(cast.target) || Boolean(cast.lostDevice);
    document.body.classList.toggle("has-cast-bar", on);
    return () => document.body.classList.remove("has-cast-bar");
  }, [cast.target, cast.lostDevice]);

  // A television that has gone quiet still gets a bar. Returning null on a
  // missing target made every "disconnected" branch below unreachable — `lost`
  // is only ever true when `target` is null — so a set switched off took the
  // bar away with it and said nothing, and the next Play came out of this
  // device instead. The bar stays, greyed, until it comes back or is dismissed.
  const device = cast.target ?? cast.lostDevice;
  if (!device) return null;
  const state = cast.target?.state;

  return (
    <>
      <div className={`cast-bar${cast.lost ? " cast-bar-lost" : ""}`}>
        <button type="button" className="cast-bar-main" onClick={() => setOpen(true)}>
          <CastGlyph on={!cast.lost} />
          <span className="cast-bar-text">
            <b>{state?.title || device.deviceName}</b>
            <small>
              {cast.lost
                // The name is already the line above whenever nothing is
                // playing, and "name / name disconnected" reads like a stutter.
                ? (state?.title
                  ? `${device.deviceName} ${t?.disconnectedSuffix || "disconnected"}`
                  : (t?.disconnected || "Disconnected"))
                : (state?.title ? `${t?.onDevice || "On"} ${device.deviceName}` : (t?.readyToPlay || "Ready to play"))}
            </small>
          </span>
        </button>
        {!cast.lost && state?.title ? (
          <button
            type="button"
            className="cast-bar-toggle"
            onClick={() => (state.paused ? cast.resume() : cast.pause())}
            aria-label={state.paused ? (t?.resume || "Resume") : (t?.pause || "Pause")}
          >
            {state.paused ? "▶" : "❚❚"}
          </button>
        ) : null}
      </div>
      {open ? <CastRemote cast={cast} t={t} onClose={() => setOpen(false)} /> : null}
    </>
  );
}

/**
 * The remote's live picture of the receiver, shared by every surface that
 * draws one — the modal remote and the watch page's remote panel.
 *
 * Three tricks make the remote feel attached to the television rather than a
 * second behind it. The position is advanced locally between reports — but
 * only while playing, since a paused position that crept forward would be a
 * lie. A scrub is held on screen until the television reports back near it,
 * or the bar snaps to a stale position and reads as a failed seek. And a
 * press of play or pause is *assumed* to land: the button flips at once and
 * the receiver's echo confirms it a round-trip later — shown wrong for 2.5
 * seconds at worst, against reading wrong after every single press before.
 */
function useRemoteTransport(cast) {
  const state = cast.target?.state;
  const duration = (state?.durationMs ?? 0) / 1000;

  const [scrubbing, setScrubbing] = useState(null);
  const [pending, setPending] = useState(null);
  const [assumed, setAssumed] = useState(null);
  const reportedAt = useRef(Date.now());
  const [, tick] = useState(0);

  // Nothing loaded is not "playing from zero": an idle receiver has no
  // position to advance, and interpolating from a missing report counted a
  // connected-but-idle device upward from 0:00 forever.
  const idle = !state || !state.title;

  useEffect(() => {
    reportedAt.current = Date.now();
    if (pending !== null && Math.abs((state?.positionMs ?? 0) / 1000 - pending) < 2) {
      setPending(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state?.positionMs]);

  // A scrub the receiver ignored — or answered with an end-of-episode — must
  // not hold a wrong position on screen forever. Same 4-second give-up the
  // phone remote has.
  useEffect(() => {
    if (pending === null) return undefined;
    const timer = window.setTimeout(() => setPending(null), 4_000);
    return () => window.clearTimeout(timer);
  }, [pending]);

  // The assumption yields to the receiver: confirmed, it is no longer needed;
  // unanswered, it expires rather than hold a lie.
  useEffect(() => {
    if (assumed === null) return undefined;
    if (Boolean(state?.paused) === assumed) {
      setAssumed(null);
      return undefined;
    }
    const timer = window.setTimeout(() => setAssumed(null), 2_500);
    return () => window.clearTimeout(timer);
  }, [assumed, state?.paused]);

  const paused = assumed ?? Boolean(state?.paused);

  useEffect(() => {
    if (idle || paused) return undefined;
    const timer = window.setInterval(() => tick((n) => n + 1), 250);
    return () => window.clearInterval(timer);
  }, [idle, paused]);

  const base = (state?.positionMs ?? 0) / 1000;
  const live = idle ? 0 : paused ? base : base + (Date.now() - reportedAt.current) / 1000;
  const shown = scrubbing ?? pending ?? live;

  const seekTo = (seconds) => {
    const position = Math.max(0, duration > 0 ? Math.min(seconds, duration) : seconds);
    setPending(position);
    cast.seek(position * 1000);
  };

  return {
    state,
    idle,
    duration,
    live,
    shown,
    paused,
    scrubbing,
    beginScrub: setScrubbing,
    commitScrub: () => {
      if (scrubbing === null) return;
      const position = scrubbing;
      setScrubbing(null);
      seekTo(position);
    },
    togglePause: () => {
      if (idle) return;
      const next = !paused;
      setAssumed(next);
      if (next) cast.pause();
      else cast.resume();
    },
    seekBy: (delta) => seekTo(live + delta),
  };
}

function RemoteSeek({ cast, r }) {
  return (
    <>
      <input
        className="cast-remote-seek"
        type="range"
        min={0}
        max={Math.max(r.duration, 1)}
        step={1}
        value={Math.min(r.shown, Math.max(r.duration, 1))}
        disabled={r.idle || cast.lost}
        onChange={(event) => r.beginScrub(Number(event.target.value))}
        onMouseUp={r.commitScrub}
        onTouchEnd={r.commitScrub}
        onKeyUp={r.commitScrub}
      />
      <div className="cast-remote-times">
        <span>{clock(r.shown)}</span>
        <span>{clock(r.duration)}</span>
      </div>
    </>
  );
}

function RemoteTransport({ cast, r, t }) {
  const state = r.state;
  return (
    <div className="cast-remote-transport">
      {/* The television is the one holding the episode list, so whether a
          skip exists comes from its report — at a season's first episode
          there is no previous, at its last no next, and the button says so
          by being disabled rather than by failing silently. */}
      <button
        type="button"
        onClick={() => cast.previous()}
        disabled={!state?.hasPrevious || cast.lost}
        aria-label={t?.prevEpisode || "Previous episode"}
      >
        ⏮
      </button>
      <button type="button" onClick={() => r.seekBy(-10)} disabled={r.idle || cast.lost}>−10</button>
      <button
        type="button"
        className="cast-remote-play"
        onClick={r.togglePause}
        disabled={r.idle || cast.lost}
      >
        {r.paused ? "▶" : "❚❚"}
      </button>
      <button type="button" onClick={() => r.seekBy(10)} disabled={r.idle || cast.lost}>+10</button>
      <button
        type="button"
        onClick={() => cast.next()}
        disabled={!state?.hasNext || cast.lost}
        aria-label={t?.nextEpisode || "Next episode"}
      >
        ⏭
      </button>
    </div>
  );
}

/** The full remote, as a sheet over any page. */
function CastRemote({ cast, t, onClose }) {
  const r = useRemoteTransport(cast);
  const state = r.state;

  useEffect(() => {
    function onKey(event) { if (event.key === "Escape") onClose(); }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <Scrim onClose={onClose}>
      <div className="cast-remote">
        <div className="cast-remote-head">
          <div>
            <small>{cast.lost ? (t?.disconnected || "Disconnected") : (t?.playingOn || "Playing on")}</small>
            {/* The remembered one when the set is away: this sheet is reachable
                from a disconnected bar, and reading the live target there is
                reading a null. */}
            <b>{(cast.target ?? cast.lostDevice)?.deviceName}</b>
          </div>
          <button type="button" className="cast-remote-close" onClick={onClose} aria-label="Close">×</button>
        </div>

        <div className="cast-remote-title">
          <b>{state?.title || (t?.nothingPlaying || "Nothing playing")}</b>
          {/* The label names itself — "第03集", "EP3" — a prefix on top reads
              doubled in either language. */}
          {state?.episodeLabel ? <small>{state.episodeLabel}</small> : null}
        </div>
        {otherController(state) ? (
          <div className="cast-remote-other">
            {(t?.castAlsoControlled || "Also being controlled from {d}").replace("{d}", otherController(state))}
          </div>
        ) : null}

        <RemoteSeek cast={cast} r={r} />
        <RemoteTransport cast={cast} r={r} t={t} />

        <div className="cast-remote-actions">
          <button type="button" onClick={() => cast.fullscreen()} disabled={r.idle || cast.lost}>
            {t?.castFullscreen || "Fullscreen"}
          </button>
          <button type="button" onClick={() => { cast.disconnect(); onClose(); }}>
            {t?.playHere || "Play here"}
          </button>
          <button type="button" className="cast-remote-stop" onClick={() => { cast.stop(); onClose(); }}>
            {t?.stop || "Stop"}
          </button>
        </div>
      </div>
    </Scrim>
  );
}

/**
 * The watch page while it is a remote.
 *
 * Connected to another device, the page's player area *is* the remote: what
 * the receiver is showing, a scrub bar, transport, and where to send things —
 * not a dark video element with the real controls hidden behind a bar at the
 * bottom of the screen. Episode and source rows stay where they always are,
 * below; while this panel is up, choosing one plays it on the receiver.
 */
export function RemotePanel({ t, poster, canSend, onSendCurrent }) {
  const cast = useCast();
  const r = useRemoteTransport(cast);
  const device = cast.target ?? cast.lostDevice;
  if (!device) return null;
  const state = r.state;

  return (
    <div className={`remote-panel${cast.lost ? " is-lost" : ""}`}>
      {poster ? (
        <div className="remote-panel-art" style={{ backgroundImage: `url("${poster}")` }} aria-hidden="true" />
      ) : null}
      <div className="remote-panel-veil" aria-hidden="true" />

      <div className="remote-panel-body">
        <div className="remote-panel-head">
          <span
            className={`remote-panel-dot${!r.idle && !r.paused && !cast.lost ? " is-live" : ""}`}
            aria-hidden="true"
          />
          <span>
            {cast.lost
              ? `${device.deviceName} · ${t?.disconnected || "Disconnected"}`
              : r.idle
                ? (t?.castConnectedTo || "Connected to {device}").replace("{device}", device.deviceName)
                : (t?.castPlayingOnDevice || "Playing on {device}").replace("{device}", device.deviceName)}
          </span>
        </div>

        <div className="remote-panel-now">
          <b>{state?.title || (t?.nothingPlaying || "Nothing playing")}</b>
          {state?.episodeLabel || state?.subtitle ? (
            <small>{[state?.episodeLabel, state?.subtitle].filter(Boolean).join(" · ")}</small>
          ) : r.idle && !cast.lost ? (
            <small>{t?.castIdleHint || "Pick an episode or a source below to start it there."}</small>
          ) : null}
        </div>

        {otherController(state) ? (
          <div className="remote-panel-other">
            {(t?.castAlsoControlled || "Also being controlled from {d}").replace("{d}", otherController(state))}
          </div>
        ) : null}

        {canSend ? (
          <button type="button" className="remote-panel-send" onClick={onSendCurrent}>
            {(t?.castPlayThisHere || "Play this on {device}").replace("{device}", device.deviceName)}
          </button>
        ) : null}

        <div className="remote-panel-controls">
          <RemoteSeek cast={cast} r={r} />
          <RemoteTransport cast={cast} r={r} t={t} />
          <div className="remote-panel-actions">
            <button type="button" onClick={() => cast.fullscreen()} disabled={r.idle || cast.lost}>
              {t?.castFullscreen || "Fullscreen"}
            </button>
            <button type="button" onClick={() => cast.disconnect()}>
              {t?.playHere || "Play here"}
            </button>
            <button type="button" className="remote-panel-stop" onClick={() => cast.stop()}>
              {t?.stop || "Stop"}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function clock(seconds) {
  if (!Number.isFinite(seconds) || seconds <= 0) return "0:00";
  const total = Math.floor(seconds);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const secs = total % 60;
  const pad = (n) => String(n).padStart(2, "0");
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(secs)}` : `${minutes}:${pad(secs)}`;
}
