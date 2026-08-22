import { useCallback, useEffect, useMemo, useState } from "react";
import { sendRealtime, subscribeRealtime } from "./realtime.js";

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
 */

const listeners = new Set();
let receivers = [];
let targetId = null;
let unsubscribe = null;

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

    // Restored only against a television that is actually on the list. A
    // session id belongs to one socket, so a set that has been switched off and
    // on again has a new one — reattaching to the old id would leave this tab
    // showing a remote for something that cannot hear it.
    if (!targetId) {
      const remembered = rememberedTarget();
      if (remembered && receivers.some((receiver) => receiver.sessionId === remembered)) {
        targetId = remembered;
      } else if (remembered && receivers.length) {
        rememberTarget(null);
      }
    }
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

  // Derived rather than stored: a television that drops off the socket must not
  // leave this tab showing controls for something that is no longer listening.
  const target = receivers.find((receiver) => receiver.sessionId === targetId) ?? null;
  const lost = Boolean(targetId) && !target;

  const connect = useCallback((sessionId) => {
    targetId = sessionId;
    rememberTarget(sessionId);
    publish();
  }, []);

  /** Stops driving it but leaves it playing — walking away is not a stop. */
  const disconnect = useCallback(() => {
    targetId = null;
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
    rememberTarget(null);
    publish();
  }, [command]);

  return {
    televisions,
    target,
    lost,
    connect,
    disconnect,
    play,
    stop,
    pause: useCallback(() => command({ action: "pause" }), [command]),
    resume: useCallback(() => command({ action: "resume" }), [command]),
    next: useCallback(() => command({ action: "next" }), [command]),
    seek: useCallback((positionMs) => command({ action: "seek", positionMs: Math.round(positionMs) }), [command]),
  };
}
