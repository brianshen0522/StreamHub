# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**StreamHub** — a self-hosted, multi-user movie/TV aggregator. It searches three
scraped providers (`movieffm`, `777tv`, `dramasq`), validates the HLS sources it
finds, strips spliced ad segments, and plays them back in the browser. Accounts
are managed in an admin console; watch progress, favorites and history are
per-user and sync live across a user's open tabs.

Native phone and TV clients are planned and live in this repository — see
`android/README.md` and `ios/README.md`. They are not built yet.

## Commands

### Docker (full stack, includes Postgres)
```bash
docker compose up --build
# Frontend: http://localhost:8080
# Backend:  http://localhost:8787/api/health
# First admin: admin / $ADMIN_PASSWORD (default "admin"), seeded on boot
```
Copy `.env.example` to `.env` first; compose reads it.

### Local dev
```bash
# Backend — needs a reachable Postgres and DATABASE_URL
cd server && npm install && npm run dev     # node --watch

# Frontend — proxies /api (and the /api/realtime WebSocket) to :8787
cd frontend && npm install && npm run dev   # http://localhost:5173
```

### Prisma
```bash
cd server
npx prisma generate   # also runs on npm install (postinstall)
npx prisma db push    # no migrations directory; schema is pushed
npx prisma studio
npm run seed          # providers + bootstrap admin
```

**There are no test suites, no linter, and no type checking.** Verify changes by
running the affected surface.

## Repository layout

| Directory | Contents |
|---|---|
| `server/` | Express API, scrapers, stream proxy |
| `frontend/` | React SPA (the web client) served by nginx in production |
| `shared/` | Code and contract notes used by more than one client |
| `android/`, `ios/` | Native clients — READMEs only so far |
| `PoC/` | Standalone Python scrapers used for provider spelunking; not part of the app |

`shared/` is why both Dockerfiles build with the **repository root** as their
context and mirror the repo layout inside the image, and why
`frontend/vite.config.js` sets `fs: { allow: [".."] }`. Keep that arrangement in
mind before moving files around. `android/` and `ios/` are excluded from the
Docker build context in `.dockerignore`.

## Backend (`server/`)

Express 4 + Prisma (PostgreSQL) + `ws`. ESM throughout, plain JavaScript, no
TypeScript. The Prisma client is generated to the non-standard path
`server/generated/prisma` and imported as `../generated/prisma/index.js`.

`src/index.js` is ~1200 lines and registers **all 44 routes directly on one
`app`** — no Router modules.

Every route answers at both `/api/…` and `/api/v1/…`. A middleware rewrites the
version prefix away before routing, so there is one registration per route and
`req.originalUrl` still shows what the client asked for. The web app uses the
unversioned paths; clients pin to `/api/v1`. Route groups:

| Group | Guards |
|---|---|
| `/api/health`, `/api/auth/{login,refresh,logout}` | none |
| `/api/auth/me*`, `/api/auth/heartbeat` | `requireAuth` |
| `/api/me/*` — favorites, history, progress, continue-watching, source-preference | `requireAuth` + `forbidAdminPlayback` |
| `/api/{search,item,episodes,sources,check-sources}` | `requireAuth` + `forbidAdminPlayback` + `assertProviderAccess` |
| `/api/{stream,manifest,poster}` | `requireAuth` + `forbidAdminPlayback` |
| `/api/admin/*` | `requireAuth` + `requireRole(ADMIN)` |

| File | Role |
|---|---|
| `src/index.js` | All routes, session issuing, boot seeding |
| `src/middleware.js` | `requireAuth`, `requireRole`, `forbidAdminPlayback`, `asyncHandler` |
| `src/auth.js` | JWT signing/verify, refresh-token hashing, `getBearerToken` |
| `src/stream.js` | Source health checks, m3u8 duration probing, the cleaned-manifest endpoint, HLS and poster proxies |
| `src/providers/*.js` | One scraper per provider + the registry |
| `src/provider-access.js` | Global and per-user provider gating |
| `src/monitoring.js` | Provider health poller (every 30 s, runs a real search) |
| `src/realtime.js` | WebSocket fan-out at `/api/realtime` |
| `src/cache.js` | Five LRU caches |
| `src/validators.js` | Zod schemas — request bodies only |
| `src/utils/http.js` | `fetchText`/`fetchJson` with a Chrome UA and `zh-TW` accept-language |
| `src/utils/adfilter.js` | `analyzePlaylist` (measures) and `stripAds` (rewrites), both over the shared core |

**Provider pattern:** each provider exports `search`, `getItem`, `getEpisodes`,
`getEpisodeStreams` and is registered in `src/providers/index.js`. Scraping is
Cheerio over HTML plus JSON dug out of `<script>` tags; dramasq has a real JSON
endpoint. Provider responses are **not uniform** — see the gotchas below.

**Caches** (`src/cache.js`): `search` 5 min, `detail` 10 min, `streamCheck`
3 min, `streamMetadata` 10 min, `mediaType` 10 min. Keys are namespaced by
provider.

**Source checking** (`src/stream.js`): m3u8 URLs are fetched with
`Range: bytes=0-4095` and validated by *content* (`#EXTM3U` plus segment tags),
so an HTML error page cannot pass; other URLs use HEAD with a `bytes=0-0` GET
fallback on 403/405/500/501. Separately, `getStreamMetadata` probes runtime from
a 256 KB prefix, recursing one level into master-playlist variants and keeping
the longest.

## Frontend (`frontend/`)

React 18 + `react-router-dom` 7 + hls.js. Plain global CSS, no modules, no
Tailwind. Four stylesheets with hard namespaces: `styles.css` (global tokens,
auth, Browse/Watch), `portal.css` (`usr-*`), `admin.css` (`adm-*`),
`player.css` (`vp-*`).

Three levels of routing:

1. `RootApp.jsx` — `BrowserRouter`, session state, login pages, `ProtectedRoute`,
   and a 30 s heartbeat. **Admin vs user is decided by `session.user.role`**, not
   by the URL; both portals are lazy-loaded.
2. `UserPortal.jsx` — the shell (collapsible rail, topbar, toasts, badge counts)
   plus the Favorites, Continue, History and Profile pages. Below 860 px the rail
   becomes a bottom tab bar by **CSS alone**, not a second component.
3. `AdminPortal.jsx` — dashboard, providers, users, audit, account.

| File | Role |
|---|---|
| `App.jsx` | **The Browse/Watch page**, not the app root. Search, detail, seasons/episodes, source selection, hls.js lifecycle, progress reporting, download orchestration |
| `VideoPlayer.jsx` | Player chrome only — transport, seek preview, menus, PiP, fullscreen, keyboard shortcuts. Never constructs `Hls` |
| `WatchPanels.jsx` | `SeasonSelect`, `EpisodeRail`, `SourceSelect` |
| `api.js` | `localStorage` session + fetch layer; single-flight refresh, retry-once on 401, `apiNdjsonStream` |
| `realtime.js` | One WebSocket per tab, exponential backoff, close-code handling |
| `adfilter.js` | `stripAds` and the hls.js `pLoader` that removes ad segments before parse |
| `download.js` | Client-side HLS→file download, AES-128 via WebCrypto, streams to disk when the File System Access API exists |
| `portal-chrome.js` | Contexts letting a routed page inject controls into the shell topbar, and the shared language state |
| `i18n.js` | Flat `zh-TW` / `en` maps, `resolveLanguage()`, `fmt()` |

**Playback fallback:** `App.jsx` tries `Hls.isSupported()` *before* `canPlayType`
(Chrome on macOS claims "maybe" for HLS and then fails to demux), loads
`directUrl` first, and on a fatal error destroys the instance and reloads through
`proxyUrl`. Engines without MSE get `video.src` with a one-shot swap to the proxy
on error.

## Ad stripping

Providers emit no SCTE-35, so ad breaks are inferred structurally: spliced ad
segments sit in a **different directory** than the feature and are bracketed by
`#EXT-X-DISCONTINUITY`. Discontinuity count alone is not a signal.

`shared/adfilter-core.js` holds the classification (`classifyRuns`) and is pure
ESM that loads unchanged in Node and in the bundle. Three guards keep it
conservative: the dominant directory must hold ≥60% of the runtime, a foreign run
over 240 s is treated as content, and it never strips more than 35% of a
playlist.

Parsing (`parsePlaylist`) and rewriting (`stripAds`) live there too, so both
sides clean a playlist the same way. Anything platform-specific stays in the
caller: `frontend/src/adfilter.js` keeps the hls.js `pLoader` and unwraps
`/api/stream` URLs before classifying, via the `resolveUri` option.

`GET /api/manifest?target=<url>` serves a cleaned playlist with **absolute CDN
URLs**, so a native player gets an ad-free manifest while segments stream
device-to-CDN. Master playlists are not filtered — each variant and rendition is
pointed back at the endpoint so whichever one the player picks arrives cleaned.
Unlike `/api/stream`, the body carries no access token, because the segment URLs
are the CDN's own.

## Gotchas

- **`App.jsx` is not the application root.** `RootApp.jsx` is. `App.jsx` is the
  Browse/Watch page and, at ~1770 lines, the largest component in the repo.
- **Login does not check role by default.** An admin authenticates fine and gets
  tokens, but every content and library route then 403s via
  `forbidAdminPlayback`. A request carrying `X-StreamHub-Client` is refused at
  login and refresh instead; the web app deliberately does not send that header,
  because its login form is shared with the admin console.
- **Refresh rotates.** `POST /api/auth/refresh` invalidates the old refresh token,
  so concurrent refreshes kill the session. Any client needs a single-flight
  refresh; `api.js` does this with a module-level `refreshPromise`.
- **Ad stripping must run before proxy URL rewriting.** `rewritePlaylist`
  collapses every segment into the same `/api/stream` directory, which destroys
  the directory signal the classifier depends on. The browser filter works around
  this by unwrapping proxy URLs first.
- **`/api/search` returns 200 even when a provider fails.** Check the per-provider
  `error` field, not the HTTP status.
- **`/api/item` is polymorphic** and returned bare. Discriminate on which key is
  present: `seasons` (movieffm TV hub, needs a second call), `episodes` (a season
  or single-page series), or `streams` (a movie). Episodes are plain strings.
- **`/api/sources` is NDJSON**, written as each probe finishes. Parse
  incrementally; failing sources are simply never emitted.
- **`/api/stream` and `/api/poster` accept `?accessToken=`** because `hls.js` and
  `<img>` cannot set headers, and the proxy bakes that token into every rewritten
  segment URL. Posters are behind auth, so they cannot be loaded unauthenticated.
- **WebSocket close code 4002 means the access token expired.** Refresh first,
  then reconnect, or the client reconnect-loops.
- **`encodeViewState` is duplicated** in `App.jsx` and `UserPortal.jsx` with
  different parameter names but an identical wire format. Change both.
- **`AdminPortal.jsx` is not translated** — its strings are hardcoded English.
- `/api/me/history` and `/api/me/progress` are capped at 200 rows with no
  pagination.

## Conventions

- Commit subjects are lower-case with an area scope: `feat(player):`,
  `fix(stream):`, `refactor:`. Bodies explain *why*, and say what was verified.
- Zod validates request bodies; responses are hand-built literals with no schema.
- Prisma models are camelCase in code, snake_case in the database via `@map`.
- `shared/api/README.md` holds the API behaviour every client must get right.
  Update it when the contract changes.
