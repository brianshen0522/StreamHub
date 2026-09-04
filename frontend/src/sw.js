/// <reference lib="webworker" />
/**
 * The service worker. Built by vite-plugin-pwa (injectManifest) into /sw.js,
 * so this file is the whole worker — nothing is generated around it.
 *
 * What it is for: the app opens without a network, shows the library it last
 * saw, and tells the person it is offline. What it is deliberately *not* for:
 * playback. Nothing under /api/stream, /api/manifest, /api/sources or search
 * is ever cached — those bodies carry access tokens or are only valid for
 * minutes, and a stale one is worse than a failed one.
 *
 * Three tiers:
 *
 *   1. The shell — index.html, the hashed bundle, icons, the manifest — is
 *      precached at install with a revision per file, so an update replaces
 *      it whole. Navigations offline are answered with the precached shell;
 *      the router takes it from there.
 *   2. Fonts from Google are cached on first use. The stylesheet is refreshed
 *      in the background, the font files are immutable and kept for a year.
 *   3. The library — favourites, continue watching, history — is
 *      network-first with the cached copy as the fallback, keyed *per user*
 *      (see cacheKeyForUser) so two accounts on one browser never see each
 *      other's rows. Posters are cache-first with the access token stripped
 *      from the key, or every token rotation would miss.
 *
 * Updates are not applied under a running page: the new worker waits until
 * the page sends SKIP_WAITING (a person pressed "reload" on the banner) or
 * every tab has closed. Reloading on its own would cut a film off.
 */
import { clientsClaim } from "workbox-core";
import { cleanupOutdatedCaches, createHandlerBoundToURL, precacheAndRoute } from "workbox-precaching";
import { NavigationRoute, registerRoute } from "workbox-routing";
import { CacheFirst, NetworkFirst, StaleWhileRevalidate } from "workbox-strategies";
import { ExpirationPlugin } from "workbox-expiration";
import { CacheableResponsePlugin } from "workbox-cacheable-response";

const LIBRARY_CACHE = "streamhub-library-v1";
const POSTER_CACHE = "streamhub-posters-v1";
const FONT_STYLE_CACHE = "streamhub-font-css-v1";
const FONT_FILE_CACHE = "streamhub-font-files-v1";

// ---------------------------------------------------------------------------
// Shell
// ---------------------------------------------------------------------------

precacheAndRoute(self.__WB_MANIFEST);
cleanupOutdatedCaches();
clientsClaim();

self.addEventListener("message", (event) => {
  const type = event.data?.type;
  if (type === "SKIP_WAITING") {
    self.skipWaiting();
  } else if (type === "CLEAR_USER_CACHES") {
    // Signing out. Posters are not personal, the library is.
    event.waitUntil(caches.delete(LIBRARY_CACHE));
  }
});

// A navigation anywhere in the SPA gets index.html. /api/ is excluded so a
// browser that is *navigated* to an API URL (a poster opened in a new tab, the
// health check) still reaches the server, and so a 404 there stays a 404.
registerRoute(
  new NavigationRoute(createHandlerBoundToURL("/index.html"), {
    denylist: [/^\/api\//],
  }),
);

// ---------------------------------------------------------------------------
// Fonts
// ---------------------------------------------------------------------------

registerRoute(
  ({ url }) => url.origin === "https://fonts.googleapis.com",
  new StaleWhileRevalidate({
    cacheName: FONT_STYLE_CACHE,
    plugins: [new CacheableResponsePlugin({ statuses: [0, 200] })],
  }),
);

registerRoute(
  ({ url }) => url.origin === "https://fonts.gstatic.com",
  new CacheFirst({
    cacheName: FONT_FILE_CACHE,
    plugins: [
      new CacheableResponsePlugin({ statuses: [0, 200] }),
      new ExpirationPlugin({ maxEntries: 30, maxAgeSeconds: 365 * 24 * 60 * 60 }),
    ],
  }),
);

// ---------------------------------------------------------------------------
// Library
// ---------------------------------------------------------------------------

/**
 * Who a request belongs to, read off its own bearer token.
 *
 * The token is a JWT and its `sub` is the user id. Nothing is verified here —
 * the server does that — this only needs a stable name to file the response
 * under, and the id is stable where the token itself rotates every few
 * minutes. A request without a token (there should be none on these routes)
 * is filed under "anonymous", which the server will have refused anyway.
 */
function userIdFromRequest(request) {
  const header = request.headers.get("authorization") || "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : "";
  const payload = token.split(".")[1];
  if (!payload) return "anonymous";
  try {
    const json = atob(payload.replace(/-/g, "+").replace(/_/g, "/"));
    return String(JSON.parse(json).sub || "anonymous");
  } catch {
    return "anonymous";
  }
}

const cacheKeyForUser = {
  cacheKeyWillBeUsed: async ({ request }) => {
    const url = new URL(request.url);
    url.searchParams.set("__user", userIdFromRequest(request));
    return url.href;
  },
};

// The lists a person expects to still be there when the network is not. Only
// GETs, only the read endpoints: a DELETE on a favourite goes straight to the
// server and fails honestly offline.
const LIBRARY_PATHS = /^\/api\/(v1\/)?me\/(favorites|continue-watching|history|progress|providers)$/;

registerRoute(
  ({ url, request }) => request.method === "GET" && LIBRARY_PATHS.test(url.pathname),
  new NetworkFirst({
    cacheName: LIBRARY_CACHE,
    // A slow link should not feel like a dead one: after this the cached
    // copy answers, and the live one is still written back when it lands.
    networkTimeoutSeconds: 8,
    plugins: [
      cacheKeyForUser,
      new CacheableResponsePlugin({ statuses: [200] }),
      new ExpirationPlugin({ maxEntries: 40, maxAgeSeconds: 30 * 24 * 60 * 60 }),
    ],
  }),
);

// ---------------------------------------------------------------------------
// Posters
// ---------------------------------------------------------------------------

// The token in the query string is how <img> authenticates (it cannot set a
// header), and it changes on every refresh. The key ignores it so the image
// survives the rotation; the fetch itself still carries it, so a person who is
// not signed in still cannot fill this cache.
const cacheKeyWithoutToken = {
  cacheKeyWillBeUsed: async ({ request }) => {
    const url = new URL(request.url);
    url.searchParams.delete("accessToken");
    return url.href;
  },
};

registerRoute(
  ({ url, request }) => request.method === "GET" && /^\/api\/(v1\/)?poster$/.test(url.pathname),
  new CacheFirst({
    cacheName: POSTER_CACHE,
    plugins: [
      cacheKeyWithoutToken,
      new CacheableResponsePlugin({ statuses: [200] }),
      new ExpirationPlugin({ maxEntries: 400, maxAgeSeconds: 30 * 24 * 60 * 60, purgeOnQuotaError: true }),
    ],
  }),
);

// Everything else under /api/ has no route registered and goes straight to
// the network, untouched — which is the point.
