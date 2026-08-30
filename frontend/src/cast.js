import { useCallback, useEffect, useMemo, useState } from "react";
import { getRealtimeSessionId, sendRealtime, subscribeRealtime } from "./realtime.js";

/**
 * Driving a television from this browser.
 *
 * The same model the phone apps use, and deliberately so — they drive the same
 * televisions and disagreeing about what "connected" means would show up as one
 * device stopping what another started. Picking a device is app-wide state:
 * choose one and *every* Play goes there until you say otherwise, rather than
 * asking again on each title.
 *
 * Authorization is the account and nothing else. The server only routes a
 * command between two sockets belonging to the same user, so there is no
 * pairing step, no device code, and nothing is discovered on the local network
 * — which is why this works from a phone on mobile data.
 *
 * The one rule everything above sits on: connecting and playing are different
 * acts. Connecting — including the silent reattach after a reload or a closed
 * app coming back — is picking up the remote: observe the television, control
 * it, never feed it. Only a fresh gesture in the current page's lifetime sends
 * playback: tapping a source, an episode, a neighbour, a title, choosing a set
 * over a playing video, or the explicit play-on-television button. The
 * television is the sole owner of playback state; every controller mirrors
 * what it reports rather than what it hopes.
 */

const listeners = new Set();
let receivers = [];
let targetId = null;
let unsubscribe = null;

/**
 * The chosen television as it was last seen on the receiver list.
 *
 * Kept because a set that drops off the list leaves nothing to name it with,
 * and "disconnected" with no device beside it is not worth showing. The id is
 * deliberately *not* forgotten at the same time: a television keeps its session
 * id across a restart — the id is the sign-in, not the socket — so holding on to
 * it is what lets the tab pick the set up again by itself when it comes back.
 */
let lastKnownTarget = null;

/**
 * How many times "Stop" has been pressed this page.
 *
 * Both "Stop" and "Play here" end with no television, so nothing downstream can
 * tell them apart by looking at the target — and they mean opposite things.
 * "Play here" is a request to carry on in this tab; "Stop" is a request for
 * nothing to be playing anywhere, and a watch page that started its own player
 * the moment the television let go would be the one thing it did not ask for.
 * A counter rather than a flag, so a second Stop is distinguishable from the
 * first without anyone having to clear it.
 */
let stopCount = 0;

/**
 * Whether the device picker is on screen. Module state rather than the
 * button's own, because the watch page has to react to it: choosing where to
 * play happens *over* a playing video, and the video keeping going underneath
 * makes the choice feel like it did not take.
 */
let pickerOpen = false;

/**
 * The chosen television, remembered across a page load.
 *
 * Held in memory alone this was lost by anything that reloads the document — a
 * refresh, a shared link, reopening the tab — and the failure was silent: the
 * next Play came out of the laptop instead of the television, which on a sofa
 * is a surprise rather than a fallback.
 */
const TARGET_KEY = "streamhub.castTarget";

function rememberTarget(sessionId) {
  try {
    if (sessionId) localStorage.setItem(TARGET_KEY, sessionId);
    else localStorage.removeItem(TARGET_KEY);
  } catch {
    // Private browsing and a full disk both land here; forgetting the choice is
    // the old behaviour, which is survivable.
  }
}

function rememberedTarget() {
  try {
    return localStorage.getItem(TARGET_KEY);
  } catch {
    return null;
  }
}

function publish() {
  for (const listener of listeners) listener();
}

function ensureSubscribed() {
  if (unsubscribe) return;
  unsubscribe = subscribeRealtime((event) => {
    if (event?.type !== "receivers") return;
    receivers = Array.isArray(event.receivers) ? event.receivers : [];

    // Restored only against a receiver that is actually on the list — and the
    // memory survives the receiver being momentarily absent. A browser
    // receiver disappears on every page navigation and whenever it is idle,
    // so "not in this frame" cannot mean "gone for good"; wiping here used to
    // make a controller that reloaded at the wrong instant forget its device
    // permanently. The memory is cleared where the person acts instead:
    // disconnect, stop, and choosing to play here all forget the target.
    if (!targetId) {
      const remembered = rememberedTarget();
      if (remembered && receivers.some((receiver) => receiver.sessionId === remembered)) {
        targetId = remembered;
      }
    }

    const live = targetId ? receivers.find((receiver) => receiver.sessionId === targetId) : null;
    if (live) lastKnownTarget = live;
    publish();
  });
}

/**
 * Every television on this account that is connected and willing to be driven.
 *
 * Only devices that announced themselves appear. One that is signed in but has
 * its app closed is absent — the phone and the browser both have to tell those
 * two states apart to say "open StreamHub on it" instead of failing silently.
 */
export function useCast() {
  const [, bump] = useState(0);

  useEffect(() => {
    const listener = () => bump((n) => n + 1);
    listeners.add(listener);
    ensureSubscribed();
    return () => {
      listeners.delete(listener);
      if (!listeners.size && unsubscribe) {
        unsubscribe();
        unsubscribe = null;
      }
    };
  }, []);

  const televisions = useMemo(
    () => receivers.filter((receiver) => receiver.clientKind === "tv"),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [receivers, receivers.length],
  );

  /**
   * Everything this client may drive: televisions, and now any browser that
   * has taken the receiver role — the server never cared which kind of device
   * announces itself, the television was merely the first. The one exclusion
   * is this session's own announcement, because a remote for oneself is a
   * hall of mirrors.
   */
  const controllable = useMemo(
    () => receivers.filter((receiver) => receiver.sessionId !== getRealtimeSessionId()),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [receivers, receivers.length],
  );

  // Derived rather than stored: a television that drops off the socket must not
  // leave this tab showing controls for something that is no longer listening.
  const target = receivers.find((receiver) => receiver.sessionId === targetId) ?? null;
  const lost = Boolean(targetId) && !target;

  const connect = useCallback((sessionId) => {
    targetId = sessionId;
    lastKnownTarget = receivers.find((receiver) => receiver.sessionId === sessionId) ?? null;
    rememberTarget(sessionId);
    publish();
  }, []);

  /** Stops driving it but leaves it playing — walking away is not a stop. */
  const disconnect = useCallback(() => {
    targetId = null;
    lastKnownTarget = null;
    // Forgotten deliberately: choosing to play here again must not be undone by
    // the next reload reattaching to the television.
    rememberTarget(null);
    publish();
  }, []);

  const command = useCallback((body) => {
    if (!targetId) return false;
    return sendRealtime({ type: "command", to: targetId, command: body });
  }, []);

  const play = useCallback(
    (request) =>
      command({
        action: "play",
        playback: {
          streamUrl: request.directUrl,
          provider: request.providerKey,
          itemUrl: request.itemUrl,
          title: request.title,
          subtitle: request.sourceLabel,
          posterUrl: request.posterUrl,
          episodeLabel: request.episodeLabel,
          episodeUrl: request.seasonUrl,
          nextEpisodeLabel: request.nextEpisodeLabel,
          prevEpisodeLabel: request.prevEpisodeLabel,
          // The receiver picks up where this account left off, so handing a
          // title over lands where it would have here.
          positionMs: Math.max(0, Math.round((request.resumeAtSeconds || 0) * 1000)),
        },
      }),
    [command],
  );

  const stop = useCallback(() => {
    command({ action: "stop" });
    targetId = null;
    lastKnownTarget = null;
    rememberTarget(null);
    stopCount += 1;
    publish();
  }, [command]);

  return {
    televisions,
    controllable,
    target,
    lost,
    /**
     * What to call the television while it is away.
     *
     * `target` is only ever the live receiver, because everything that sends a
     * command keys off it and must not aim at a set that is not listening. The
     * bar still has to name the thing that vanished, and only this remembers it.
     */
    lostDevice: lost ? lastKnownTarget : null,
    connect,
    disconnect,
    play,
    stop,
    /** Rises by one each time Stop is pressed. See `stopCount`. */
    stopped: stopCount,
    pickerOpen,
    openPicker: useCallback(() => { pickerOpen = true; publish(); }, []),
    closePicker: useCallback(() => { pickerOpen = false; publish(); }, []),
    pause: useCallback(() => command({ action: "pause" }), [command]),
    resume: useCallback(() => command({ action: "resume" }), [command]),
    next: useCallback(() => command({ action: "next" }), [command]),
    previous: useCallback(() => command({ action: "previous" }), [command]),
    seek: useCallback((positionMs) => command({ action: "seek", positionMs: Math.round(positionMs) }), [command]),
    /** Asks the receiver to fill its screen — the web receiver's immersive
     *  layout. A native television is already edge to edge and ignores it. */
    fullscreen: useCallback(() => command({ action: "fullscreen" }), [command]),
  };
}
