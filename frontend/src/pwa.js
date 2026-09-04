/**
 * The page's side of the service worker (src/sw.js).
 *
 * Two facts are kept here for the UI to render: whether a newer build is
 * installed and waiting, and whether the browser believes it is online. Both
 * are plain subscriptions rather than React state so that api.js, which has
 * no React, can consult the same source.
 *
 * `virtual:pwa-register` is provided by vite-plugin-pwa. In `npm run dev` it
 * registers nothing, so the dev server keeps behaving like a dev server.
 */
import { registerSW } from "virtual:pwa-register";

const UPDATE_CHECK_MS = 60 * 60 * 1000;

let updateReady = false;
let applyUpdateFn = null;
const updateListeners = new Set();

let online = typeof navigator === "undefined" ? true : navigator.onLine;
const onlineListeners = new Set();

function emit(listeners, value) {
  listeners.forEach((listener) => {
    try {
      listener(value);
    } catch {}
  });
}

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

  window.addEventListener("online", () => {
    online = true;
    emit(onlineListeners, true);
  });
  window.addEventListener("offline", () => {
    online = false;
    emit(onlineListeners, false);
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
 * The browser's own opinion, which is only ever a hint: `true` means there is
 * a network interface up, not that the server answers. A fetch that fails is
 * the real signal, and api.js reports it in the same words.
 */
export function isOnline() {
  return online;
}

export function subscribeOnline(listener) {
  onlineListeners.add(listener);
  return () => onlineListeners.delete(listener);
}

/**
 * Drop the cached library on sign-out. The rows are keyed per user in the
 * worker, so this is hygiene rather than a wall — but a shared machine should
 * not keep a stranger's favourites on disk after they have left.
 */
export function clearOfflineLibrary() {
  navigator.serviceWorker?.controller?.postMessage({ type: "CLEAR_USER_CACHES" });
}
