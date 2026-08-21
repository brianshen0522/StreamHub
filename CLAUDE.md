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

**The server and web app have no test suites, no linter and no type checking** —
verify changes there by running the affected surface. The Android `:core` module
is the exception and does have JVM unit tests:

```bash
cd android && ./gradlew :core:testDebugUnitTest
```

## Repository layout

| Directory | Contents |
|---|---|
| `server/` | Express API, scrapers, stream proxy |
| `frontend/` | React SPA (the web client) served by nginx in production |
| `shared/` | Code and contract notes used by more than one client |
| `android/` | Gradle project: `:core` data layer (API client, session, models — unit-tested), `:mobile` phone app, `:tv` Android TV app. Both play video; the phone can drive the television — see `android/CASTING.md` |
| `ios/` | Native client — README only so far |
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

`src/index.js` is ~1400 lines and registers **all 54 routes directly on one
`app`** — no Router modules.

Every route answers at both `/api/…` and `/api/v1/…`. A middleware rewrites the
version prefix away before routing, so there is one registration per route and
`req.originalUrl` still shows what the client asked for. The web app uses the
unversioned paths; clients pin to `/api/v1`. Route groups:

| Group | Guards |
|---|---|
| `/api/health`, `/api/auth/{login,refresh,logout}`, `/api/auth/device/{start,poll}` | none |
| `/api/auth/me*`, `/api/auth/heartbeat`, `/api/auth/device/{pending,approve,deny}` | `requireAuth` |
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
| `src/realtime.js` | WebSocket fan-out at `/api/realtime`, and the phone-to-television command channel |
| `src/backup.js` | Whole-instance export and import, behind `/api/admin/backup` |
| `src/device-auth.js` | Codes and expiry for signing a television in from a phone |
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

## Exposed to the internet

The instance runs on a public domain, which changes what the code has to assume.

- **`JWT_SECRET` and `ADMIN_PASSWORD` are required.** The server refuses to start
  on a placeholder secret or one shorter than 32 characters, and refuses to seed
  a first administrator with a weak password. Both used to fall back to values
  committed to this repository.
- **Every outbound fetch is address-checked.** `src/utils/safe-fetch.js` resolves
  each hop and refuses anything that is not a public unicast address, following
  redirects by hand so a target cannot pass the check and then bounce inward.
  It guards the three media proxies *and* `fetchText`/`fetchJson`, because
  `/api/item`, `/api/episodes` and `/api/check-sources` all take URLs straight
  from the request. Without it any signed-in account could read internal
  services through the server.
- **`app.set("trust proxy", 1)`** — one hop, the nginx in front. Session IPs and
  rate limiting are meaningless without it.
- **Login and refresh are rate limited** to 20 *failures* per 15 minutes per
  address (`src/rate-limit.js`). Successes are not counted, because a household
  shares one public address and one person's typo must not lock out the rest.
- Postgres is not published to the host; it is reachable only over the compose
  network.

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
- **Resume semantics now exist three times**: `getResumeSeason`,
  `getResumeEpisode` and friends in `frontend/src/App.jsx`, `ResumeRules` in
  `android/core` (the only one with tests), and `ResumeRules` in
  `ios/StreamHub/Core`. Which season and episode resume, when a season rolls
  over and what counts as finished have to agree across clients, so changing
  one means changing all three. They cannot share code — one is JS in a
  browser, one Kotlin, one Swift.
- **Where a title opens is decided by progress, not by the link.** A row in the
  library carries the season and episode it was recorded with, and only a row of
  *history* means "take me to that viewing" — it sets `x: 1` in the encoded view
  state and its position is honoured as given. A favourite carries wherever the
  heart happened to be tapped, so its season and episode are a fallback used
  only for a title with nothing watched; otherwise `getResumeSeason` picks the
  season holding the most recent progress. Adding a new entry point means
  deciding which of the two it is.
- **A television signs in by pairing, and the two codes are not alike.** The
  `deviceCode` collects the session and must never be displayed or encoded into
  the QR — a photograph of the screen would be a sign-in. The `userCode` is the
  short one meant to be read out and typed. `POST /api/auth/device/start` also
  stamps the request's address, user agent and client kind onto the pending row,
  and the session is minted from *those* rather than from the phone that
  approves it, so the account's device list names the television.
- **A scanned QR is untrusted input.** All three clients take only the `code`
  parameter out of it and never open the URL: a QR is something anyone can
  print, and following one would hand a signed-in session to whoever printed it.
  The rule lives in `UserCode.fromScan` (android/core, ios/StreamHub/Core) and
  `codeFromScan` (frontend/src/QrScanner.jsx). Decoding differs by platform —
  zxing through `QrDecoder` in android/core, AVFoundation on iOS, and
  `BarcodeDetector` with a jsQR fallback on the web because Safari has no
  native one.
- **Never rewrite a code field's text to insert the separator while it is being
  typed.** Both phone apps did, and both silently corrupted the pairing code:
  Compose reordered it (`3vxja5wj` → `3VXJ-5WJA`) and SwiftUI dropped the
  character typed at the group boundary (`5EH5XHS3` → `5EH5HS3`). Inserting a
  character mid-string moves the caret somewhere the keyboard is not expecting.
  The handler may only *remove* — uppercase, drop invalid characters, cap the
  length. Android draws the break with a `VisualTransformation`, which leaves
  the value alone; iOS has no equivalent and simply does not show one while
  typing. A code one transposition out fails as "expired", which sends people
  to look at the television rather than at the field.
- **The QR on the TV sign-in screen must not be sized off its container's
  width.** A 1080p television is 540dp tall; a width-derived square overflowed
  the safe area, and a Compose `Column` that runs out of height crushes its last
  child rather than clipping it — the address under the code rendered five
  pixels tall while everything else looked fine.
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
