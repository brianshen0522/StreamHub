import { useEffect, useRef, useState } from "react";
import { useCast } from "./cast.js";

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
  return (
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
    </div>
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
  const [picking, setPicking] = useState(false);

  if (!cast.televisions.length && !cast.target) return null;

  return (
    <>
      <button
        type="button"
        className={`cast-btn${cast.target ? " cast-btn-on" : ""}`}
        onClick={() => setPicking(true)}
        title={t?.castTo || "Play on a television"}
        aria-label={t?.castTo || "Play on a television"}
      >
        <CastGlyph on={Boolean(cast.target)} />
      </button>
      {picking ? <CastPicker cast={cast} t={t} onClose={() => setPicking(false)} /> : null}
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

        {cast.televisions.map((receiver) => (
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
    const on = Boolean(cast.target);
    document.body.classList.toggle("has-cast-bar", on);
    return () => document.body.classList.remove("has-cast-bar");
  }, [cast.target]);

  if (!cast.target) return null;
  const state = cast.target.state;

  return (
    <>
      <div className={`cast-bar${cast.lost ? " cast-bar-lost" : ""}`}>
        <button type="button" className="cast-bar-main" onClick={() => setOpen(true)}>
          <CastGlyph on={!cast.lost} />
          <span className="cast-bar-text">
            <b>{state?.title || cast.target.deviceName}</b>
            <small>
              {cast.lost
                ? `${cast.target.deviceName} ${t?.disconnected || "disconnected"}`
                : (state?.title ? `${t?.onDevice || "On"} ${cast.target.deviceName}` : (t?.readyToPlay || "Ready to play"))}
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
 * The full remote.
 *
 * Two things here are not obvious. The receiver reports its position about
 * once a second, so the bar is advanced locally between reports or it ticks in
 * visible steps — but only while playing, since a paused position that crept
 * forward would be a lie about the television. And a scrub is held on screen
 * until the television reports back near it, or the bar snaps to a stale
 * position for a second and reads as a failed seek.
 */
function CastRemote({ cast, t, onClose }) {
  const state = cast.target?.state;
  const duration = (state?.durationMs ?? 0) / 1000;

  const [scrubbing, setScrubbing] = useState(null);
  const [pending, setPending] = useState(null);
  const reportedAt = useRef(Date.now());
  const [, tick] = useState(0);

  useEffect(() => {
    reportedAt.current = Date.now();
    if (pending !== null && Math.abs((state?.positionMs ?? 0) / 1000 - pending) < 2) {
      setPending(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state?.positionMs]);

  useEffect(() => {
    if (state?.paused) return undefined;
    const timer = window.setInterval(() => tick((n) => n + 1), 250);
    return () => window.clearInterval(timer);
  }, [state?.paused]);

  useEffect(() => {
    function onKey(event) { if (event.key === "Escape") onClose(); }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const base = (state?.positionMs ?? 0) / 1000;
  const live = state?.paused ? base : base + (Date.now() - reportedAt.current) / 1000;
  const shown = scrubbing ?? pending ?? live;

  return (
    <Scrim onClose={onClose}>
      <div className="cast-remote">
        <div className="cast-remote-head">
          <div>
            <small>{cast.lost ? (t?.disconnected || "Disconnected") : (t?.playingOn || "Playing on")}</small>
            <b>{cast.target.deviceName}</b>
          </div>
          <button type="button" className="cast-remote-close" onClick={onClose} aria-label="Close">×</button>
        </div>

        <div className="cast-remote-title">
          <b>{state?.title || (t?.nothingPlaying || "Nothing playing")}</b>
          {state?.episodeLabel ? <small>{`${t?.episode || "Episode"} ${state.episodeLabel}`}</small> : null}
        </div>

        <input
          className="cast-remote-seek"
          type="range"
          min={0}
          max={Math.max(duration, 1)}
          step={1}
          value={Math.min(shown, Math.max(duration, 1))}
          disabled={!state || cast.lost}
          onChange={(event) => setScrubbing(Number(event.target.value))}
          onMouseUp={commitScrub}
          onTouchEnd={commitScrub}
          onKeyUp={commitScrub}
        />
        <div className="cast-remote-times">
          <span>{clock(shown)}</span>
          <span>{clock(duration)}</span>
        </div>

        <div className="cast-remote-transport">
          <button type="button" onClick={() => cast.seek(Math.max(0, live - 10) * 1000)} disabled={!state || cast.lost}>−10</button>
          <button
            type="button"
            className="cast-remote-play"
            onClick={() => (state?.paused ? cast.resume() : cast.pause())}
            disabled={!state || cast.lost}
          >
            {state?.paused ? "▶" : "❚❚"}
          </button>
          <button type="button" onClick={() => cast.seek((live + 10) * 1000)} disabled={!state || cast.lost}>+10</button>
        </div>

        <div className="cast-remote-actions">
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

  function commitScrub() {
    if (scrubbing === null) return;
    const position = scrubbing;
    setScrubbing(null);
    setPending(position);
    cast.seek(position * 1000);
  }
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
