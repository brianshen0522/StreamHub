import { useEffect, useRef } from "react";
import { getRealtimeSessionId, sendRealtime, subscribeRealtime } from "./realtime.js";

/**
 * The browser as a receiver: this tab can be driven the way a television is.
 *
 * The server never cared what kind of device announces itself — any socket
 * that publishes playback state becomes a receiver, and commands route to it
 * by session id. The television was just the first client to take the role.
 *
 * One wrinkle is unique to the web and handled here: every tab of the same
 * browser shares one login session, so two tabs would announce under the same
 * id — indistinguishable in every picker, and a command would land on both.
 * A short lease in localStorage elects exactly one tab per session to hold
 * the role; the others stay silent and deaf. The lease is refreshed while
 * held and expires on its own, so a closed tab hands the role over without
 * anyone doing anything.
 */

const LEASE_KEY = "streamhub.receiverLease";
const LEASE_TTL_MS = 12_000;
const tabId = Math.random().toString(36).slice(2);

function readLease() {
  try {
    const raw = localStorage.getItem(LEASE_KEY);
    if (!raw) return null;
    const lease = JSON.parse(raw);
    if (Date.now() - lease.at > LEASE_TTL_MS) return null;
    return lease;
  } catch {
    return null;
  }
}

function holdsLease() {
  const lease = readLease();
  if (lease && lease.tab !== tabId) return false;
  try {
    localStorage.setItem(LEASE_KEY, JSON.stringify({ tab: tabId, at: Date.now() }));
  } catch {
    // Storage refused — claim nothing rather than fight over it.
    return false;
  }
  return true;
}

function releaseLease() {
  try {
    const lease = readLease();
    if (lease?.tab === tabId) localStorage.removeItem(LEASE_KEY);
  } catch { /* nothing to release */ }
}

/**
 * Runs the receiver role for the watch page.
 *
 * @param {object}   options
 * @param {boolean}  options.active     whether this page has local playback to offer
 * @param {Function} options.getState   () => CastPlaybackState-shaped object, or null when idle
 * @param {Function} options.onCommand  (command) => void — pause/resume/seek/stop/next/previous/play
 */
export function useBrowserReceiver({ active, getState, onCommand }) {
  const stateRef = useRef(getState);
  stateRef.current = getState;
  const commandRef = useRef(onCommand);
  commandRef.current = onCommand;
  const announced = useRef(false);

  useEffect(() => {
    if (!active) {
      // Fell idle after having announced: say so once, so the pickers show
      // "Ready" instead of a playback that ended minutes ago. Deferred past
      // the server's 250ms state throttle — the last heartbeat may have gone
      // out moments ago, and a throttled frame is silently dropped, which
      // would leave the stale title on every picker.
      if (announced.current && holdsLease()) {
        const settle = window.setTimeout(() => {
          if (holdsLease()) sendRealtime({ type: "playback", state: null });
        }, 400);
        return () => window.clearTimeout(settle);
      }
      return undefined;
    }

    const beat = window.setInterval(() => {
      if (!holdsLease()) return;
      const state = stateRef.current();
      sendRealtime({ type: "playback", state });
      announced.current = true;
    }, 1_000);

    return () => window.clearInterval(beat);
  }, [active]);

  useEffect(() => {
    // A closing tab cannot rely on React cleanup, and an unreleased lease
    // makes the session's next tab wait out the TTL before it may announce.
    // pagehide fires on close and on navigation away — both are moments this
    // tab stops being able to hold the role.
    window.addEventListener("pagehide", releaseLease);
    const unsubscribe = subscribeRealtime((event) => {
      if (event?.type !== "command" || !event.command) return;
      // Addressed to this session; only the leaseholder answers, or every tab
      // of this browser would obey at once.
      if (!holdsLease()) return;
      // A tab that never took the playing role still answers `play`: sending a
      // title to "Chrome" must work while Chrome is idle, exactly as it does
      // for a television sitting on its home screen.
      if (!announced.current && event.command.action !== "play") return;
      commandRef.current(event.command);
    });
    return () => {
      window.removeEventListener("pagehide", releaseLease);
      unsubscribe();
      releaseLease();
    };
  }, []);
}

export function ownReceiverSessionId() {
  return getRealtimeSessionId();
}
