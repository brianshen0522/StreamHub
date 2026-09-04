/**
 * The page's side of the service worker (src/sw.js).
 *
 * Two facts are kept here for the UI to render: whether a newer build is
 * installed and waiting, and whether the server can be reached. Both are
 * plain subscriptions rather than React state so that api.js, which has no
 * React, can consult the same source.
 *
 * `virtual:pwa-register` is provided by vite-plugin-pwa. In `npm run dev` it
 * registers nothing, so the dev server keeps behaving like a dev server.
 */
import { registerSW } from "virtual:pwa-register";

const UPDATE_CHECK_MS = 60 * 60 * 1000;

/** How long between reachability probes while the server is not answering. */
const PROBE_BASE_MS = 2_000;
const PROBE_MAX_MS = 30_000;

let updateReady = false;
let applyUpdateFn = null;
const updateListeners = new Set();

/**
 * Whether the server was reachable the last time anything asked it.
 *
 * `navigator.onLine` is only the browser's guess — true means a network
 * interface is up, not that the internet behind it works, and a phone on a
 * captive portal or a dead Wi-Fi is "online" by that measure. So this flag is
 * cleared by evidence (a request that got no answer at all, or the browser's
 * own offline event) and set again only by evidence: a health check that
 * answered. Nothing in between is trusted.
 */
let online = typeof navigator === "undefined" ? true : navigator.onLine;
const onlineListeners = new Set();
const reconnectListeners = new Set();

let probeTimer = null;
let probeAttempts = 0;
let probeInFlight = null;

function emit(listeners, value) {
  listeners.forEach((listener) => {
    try {
      listener(value);
    } catch {}
  });
}

function setOnline(next) {
  if (online === next) return;
  online = next;
  emit(onlineListeners, next);
  if (next) emit(reconnectListeners, undefined);
}

function clearProbeTimer() {
  if (probeTimer) {
    window.clearTimeout(probeTimer);
    probeTimer = null;
  }
}

function scheduleProbe(delay) {
  clearProbeTimer();
  probeTimer = window.setTimeout(() => {
    probeTimer = null;
    probe();
  }, delay);
}

/**
 * Ask the server whether it is there. `/api/health` needs no session, is not
 * routed by the worker, and is tiny. A 5xx counts as "not yet": nginx is up
 * but the backend behind it is not, which to everyone else in the app is the
 * same outage.
 */
function probe() {
  if (probeInFlight) return probeInFlight;
  probeInFlight = (async () => {
    let reachable = false;
    try {
      const response = await fetch("/api/health", { cache: "no-store" });
      reachable = response.ok;
    } catch {
      // no answer at all
    }
    probeInFlight = null;
    if (reachable) {
      probeAttempts = 0;
      clearProbeTimer();
      setOnline(true);
      return true;
    }
    // Reached while still believed online only through a failed request
    // reported by api.js, or the browser's own "online" event proving
    // premature. Either way the evidence is now in.
    setOnline(false);
    // A browser that says the interface is down would fail every probe at
    // once; its own "online" event restarts these the moment that changes.
    if (typeof navigator === "undefined" || navigator.onLine !== false) {
      const delay = Math.min(PROBE_MAX_MS, PROBE_BASE_MS * 2 ** probeAttempts);
      probeAttempts += 1;
      scheduleProbe(delay);
    }
    return false;
  })();
  return probeInFlight;
}

/**
 * Called by api.js when a request got no response at all. One failed request
 * is not proof — the server may have been mid-restart — so it starts a probe
 * rather than declaring the outage, and the probe's answer decides.
 */
export function reportServerUnreachable() {
  if (probeInFlight || probeTimer) return;
  probeAttempts = 0;
  void probe();
}

/**
 * Ask now whether the server answers, and say so. For a caller whose own
 * request died somewhere other than this server — a CDN, over a player's
 * fetches — and needs to know whether that was the CDN or the whole network.
 * The answer also drives the offline state and its retries, as any probe does.
 */
export function probeServer() {
  probeAttempts = 0;
  clearProbeTimer();
  return probe();
}

function watchConnectivity() {
  if (typeof window === "undefined") return;
  window.addEventListener("online", () => {
    // The browser knows the moment a tunnel ends; ask right then rather than
    // waiting out whatever backoff the probes had reached.
    probeAttempts = 0;
    clearProbeTimer();
    void probe();
  });
  window.addEventListener("offline", () => {
    clearProbeTimer();
    setOnline(false);
  });
  // Timers stop while a phone app is in the background. Coming back to the
  // foreground after an outage is the moment people expect things to work.
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState !== "visible" || online) return;
    probeAttempts = 0;
    clearProbeTimer();
    void probe();
  });
}

watchConnectivity();

export function registerPwa() {
  if (!("serviceWorker" in navigator)) return;

  applyUpdateFn = registerSW({
    // The new worker waits. The banner's button is what lets it through;
    // reloading on our own would stop whatever is playing.
    immediate: true,
    onNeedRefresh() {
      updateReady = true;
      emit(updateListeners, true);
    },
    onRegisteredSW(_url, registration) {
      // A home-screen app can stay open for days and never "load" again, which
      // is the only time a browser checks for a new worker on its own.
      if (!registration) return;
      const check = () => registration.update().catch(() => {});
      window.setInterval(check, UPDATE_CHECK_MS);
      // Timers stop while a phone app is in the background, sometimes for
      // days; coming back to the foreground is the moment that matters.
      document.addEventListener("visibilitychange", () => {
        if (document.visibilityState === "visible") check();
      });
    },
  });
}

/** True once a newer build is installed and waiting for a reload. */
export function isUpdateReady() {
  return updateReady;
}

export function subscribeUpdateReady(listener) {
  updateListeners.add(listener);
  return () => updateListeners.delete(listener);
}

/** Let the waiting worker take over and reload onto the new build. */
export function applyUpdate() {
  if (applyUpdateFn) {
    applyUpdateFn(true);
  } else {
    window.location.reload();
  }
}

/**
 * Whether the server answered the last time it was asked. False after a
 * request died or the browser reported the interface down; true again only
 * once a health probe gets through.
 */
export function isOnline() {
  return online;
}

export function subscribeOnline(listener) {
  onlineListeners.add(listener);
  return () => onlineListeners.delete(listener);
}

/**
 * Fires each time the server becomes reachable again after an outage — the
 * signal for anything that failed while offline to try again. Unlike the
 * browser's `online` event this only fires once a health check has answered,
 * so a subscriber's retry does not race the network coming up.
 */
export function subscribeReconnect(listener) {
  reconnectListeners.add(listener);
  return () => reconnectListeners.delete(listener);
}

/**
 * Drop the cached library on sign-out. The rows are keyed per user in the
 * worker, so this is hygiene rather than a wall — but a shared machine should
 * not keep a stranger's favourites on disk after they have left.
 */
export function clearOfflineLibrary() {
  navigator.serviceWorker?.controller?.postMessage({ type: "CLEAR_USER_CACHES" });
}
