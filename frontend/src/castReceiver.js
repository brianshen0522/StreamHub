import { useEffect, useRef } from "react";
import { sendRealtime, subscribeRealtime } from "./realtime.js";

/**
 * The browser as a receiver: this tab can be driven the way a television is.
 *
 * The server never cared what kind of device announces itself — any socket
 * that publishes playback state becomes a receiver, and commands route to it
 * by session id. The television was just the first client to take the role.
 *
 * The role has two layers, mirroring how the television is built:
 *
 * - Presence lives in the portal shell (`useReceiverPresence`) and announces
 *   for as long as the web app is open — a null state while idle, exactly the
 *   way a television on its home screen announces. This is what makes an open
 *   browser appear in every picker before anything is playing, and what lets
 *   an idle tab accept a title: a `play` arriving while the watch page is not
 *   mounted is stashed and the shell navigates there to honour it.
 *
 * - Playback lives in the watch page (`useBrowserReceiver`), which registers
 *   a state getter and a command handler with this module while it is
 *   mounted. Presence reads through them; it never needs to know how the
 *   player works.
 *
 * One wrinkle is unique to the web and handled here: every tab of the same
 * browser shares one login session, so two tabs would announce under the same
 * id — indistinguishable in every picker, and a command would land on both.
 * A short lease in localStorage elects exactly one tab per session to hold
 * the role; the others stay silent and deaf. The lease is refreshed while
 * held, released on pagehide, and expires on its own as a fallback. A tab
 * that is actually playing may take the lease from one that sits idle —
 * whoever has the picture speaks for the session — but never from another
 * playing tab, so two playing tabs cannot flap over it.
 */

const LEASE_KEY = "streamhub.receiverLease";
const LEASE_TTL_MS = 12_000;
const tabId = Math.random().toString(36).slice(2);

/**
 * The person at this browser saying "stop letting other devices drive me".
 *
 * Being a receiver is the default, and everything about it is account-scoped —
 * any signed-in device can hand this one a title. The one thing that must
 * beat the account is the person physically at the screen. The flag lives in
 * localStorage so every tab of this browser honours it and a reload does not
 * quietly re-open the door; it is undone only from the Profile page's toggle.
 */
const OPT_OUT_KEY = "streamhub.receiverOptOut";

export function isReceiverDetached() {
  try {
    return localStorage.getItem(OPT_OUT_KEY) === "1";
  } catch {
    return false;
  }
}

/** Refuse remote control from now on, and leave the account's cast lists. */
export function detachReceiver() {
  try { localStorage.setItem(OPT_OUT_KEY, "1"); } catch { /* still refuse below */ }
  releaseLease();
  // Retried once: the whole point of the press is to leave the list, and a
  // socket mid-reconnect at that instant would otherwise swallow it silently.
  if (!sendRealtime({ type: "playback", withdraw: true })) {
    window.setTimeout(() => sendRealtime({ type: "playback", withdraw: true }), 1_000);
  }
}

/** Accept remote control again — the next heartbeat re-lists this browser. */
export function reattachReceiver() {
  try { localStorage.removeItem(OPT_OUT_KEY); } catch { /* covered by the beat */ }
}

/** Set by the watch page while it has local playback to report. */
let stateGetter = null;
/** Set by the watch page while it is mounted and can act on commands. */
let commandHandler = null;
/** A play request waiting for the watch page to mount and consume it. */
let pendingPlay = null;
/** A queued immediate announcement — see `announceNow`. */
let announceTimer = null;

/**
 * Announce this tab's state right now instead of on the next heartbeat.
 *
 * The heartbeat alone is why remotes used to feel broken: press pause and the
 * remote's button sat wrong for up to a second, long enough to press it again
 * and undo the first press. The player calls this on every transition — play,
 * pause, seek, buffering — and the presence layer calls it after applying a
 * command, so the controller hears the effect within a round-trip.
 *
 * Deferred one tick rather than sent inline: transitions arrive in bursts
 * (pause fires pause and seeked together) and the element's state is only
 * settled once the burst is over. One frame carrying the settled state beats
 * three carrying the intermediate ones.
 */
export function announceNow() {
  if (announceTimer) return;
  announceTimer = window.setTimeout(() => {
    announceTimer = null;
    if (isReceiverDetached()) return;
    const state = stateGetter ? stateGetter() : null;
    if (!holdsLease(Boolean(state))) return;
    sendRealtime({ type: "playback", state });
  }, 60);
}

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

function holdsLease(playing) {
  const lease = readLease();
  if (lease && lease.tab !== tabId) {
    // Playing beats idle; nothing beats playing.
    if (!(playing && !lease.playing)) return false;
  }
  try {
    localStorage.setItem(LEASE_KEY, JSON.stringify({ tab: tabId, at: Date.now(), playing }));
  } catch {
    // Storage refused — claim nothing rather than fight over it.
    return false;
  }
  return true;
}

function releaseLease() {
  // A queued announcement would re-claim the lease this is releasing —
  // `holdsLease` takes any lease that is absent — so it dies with it.
  window.clearTimeout(announceTimer);
  announceTimer = null;
  try {
    const lease = readLease();
    if (lease?.tab === tabId) localStorage.removeItem(LEASE_KEY);
  } catch { /* nothing to release */ }
}

/**
 * The presence layer, mounted once by the portal shell.
 *
 * @param {object}   options
 * @param {Function} options.onPlay  (playback) => void — called when a play
 *   command arrives while the watch page is not mounted; expected to stash
 *   the request (`stashPlayRequest`) and navigate to the watch page.
 */
export function useReceiverPresence({ onPlay }) {
  const playRef = useRef(onPlay);
  playRef.current = onPlay;

  useEffect(() => {
    const beat = window.setInterval(() => {
      if (isReceiverDetached()) return;
      const state = stateGetter ? stateGetter() : null;
      if (!holdsLease(Boolean(state))) return;
      sendRealtime({ type: "playback", state });
    }, 1_000);

    const unsubscribe = subscribeRealtime((event) => {
      if (event?.type !== "command" || !event.command) return;
      // Detached means deaf as well as silent: the withdrawal already removed
      // this browser from every picker, but a command aimed at the session id
      // before that landed must die here, not land as a surprise.
      if (isReceiverDetached()) return;
      // Addressed to this session; only the leaseholder answers, or every tab
      // of this browser would obey at once.
      const state = stateGetter ? stateGetter() : null;
      if (!holdsLease(Boolean(state))) return;
      if (commandHandler) {
        commandHandler(event.command, event.fromName || null);
        // The echo: whoever sent the command sees its effect immediately,
        // not on the next heartbeat. `announceNow`'s deferral gives the
        // player a tick to settle first.
        announceNow();
        return;
      }
      // No watch page mounted: an idle tab still honours a hand-over, the way
      // a television on its home screen does. Everything else needs a player
      // to act on and is meaningless here.
      if (event.command.action === "play" && event.command.playback) {
        playRef.current(event.command.playback, event.fromName || null);
      }
    });

    // A page on its way out — closing, navigating, or being frozen into the
    // back-forward cache — must resign the receiver role explicitly. A frozen
    // page's socket stays open and answers protocol pings by itself while its
    // script never runs again, which used to leave a zombie on the cast list:
    // reachable, listed, showing a position frozen at the moment of freezing,
    // and deaf to every command. React cleanup cannot be relied on for any of
    // these, so the withdrawal rides pagehide and freeze; pageshow and resume
    // re-announce on the way back in, so a restored page reappears at once.
    const resign = () => {
      releaseLease();
      sendRealtime({ type: "playback", withdraw: true });
    };
    const reappear = () => announceNow();
    window.addEventListener("pagehide", resign);
    document.addEventListener("freeze", resign);
    window.addEventListener("pageshow", reappear);
    document.addEventListener("resume", reappear);
    return () => {
      window.clearInterval(beat);
      window.removeEventListener("pagehide", resign);
      document.removeEventListener("freeze", resign);
      window.removeEventListener("pageshow", reappear);
      document.removeEventListener("resume", reappear);
      unsubscribe();
      resign();
    };
  }, []);
}

/**
 * The playback layer, mounted by the watch page.
 *
 * @param {object}   options
 * @param {boolean}  options.active     whether this page has local playback to offer
 * @param {Function} options.getState   () => CastPlaybackState-shaped object
 * @param {Function} options.onCommand  (command) => void — pause/resume/seek/stop/next/previous/play
 */
export function useBrowserReceiver({ active, getState, onCommand }) {
  const getRef = useRef(getState);
  getRef.current = getState;
  const cmdRef = useRef(onCommand);
  cmdRef.current = onCommand;

  useEffect(() => {
    commandHandler = (command, fromName) => cmdRef.current(command, fromName);
    return () => { commandHandler = null; };
  }, []);

  useEffect(() => {
    if (!active) return undefined;
    stateGetter = () => getRef.current();
    return () => { stateGetter = null; };
  }, [active]);
}

/** Hold a play request across the navigation into the watch page. */
export function stashPlayRequest(playback, fromName = null) {
  pendingPlay = { playback, fromName };
}

/** The watch page collects what the shell accepted on its behalf. */
export function consumePlayRequest() {
  const request = pendingPlay;
  pendingPlay = null;
  return request;
}
