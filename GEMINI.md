# StreamHub: Project Context & Guidelines

StreamHub is a self-hosted streaming aggregator that scrapes content from multiple providers (MovieFFM, 777TV, DramaSQ), validates streams, strips spliced ad segments, and provides a unified viewing experience for multiple user accounts.

## Project Overview

*   **Architecture:** Monorepo. `frontend` (React/Vite) and `server` (Node.js/Express) plus a `shared` directory for code and contract notes used by more than one client. `android` and `ios` hold planned native clients (READMEs only so far).
*   **Tech Stack:**
    *   **Frontend:** React 18, Vite 5, `react-router-dom` 7, `hls.js` for playback, plain global CSS (no modules, no Tailwind).
    *   **Backend:** Node.js (Express 4), Prisma ORM (PostgreSQL), `cheerio` for scraping, `ws` for WebSockets, `lru-cache`, `argon2`, `zod`.
    *   **Database:** PostgreSQL 17. Schema is pushed, not migrated — there is no migrations directory.
    *   **Infrastructure:** Docker Compose. Both images build with the **repository root** as their context so `shared/` is reachable.
*   **Core Logic:**
    *   **Scraping:** Modular provider system in `server/src/providers/`.
    *   **Stream Validation:** m3u8 URLs are validated by content (`#EXTM3U` + segment tags) over the first 4 KB, so HTML error pages cannot pass.
    *   **Ad Stripping:** Ad runs are inferred structurally — spliced segments live in a different directory than the feature. Parsing, classification and rewriting are all shared (`shared/adfilter-core.js`), so the browser filter and the server's `/api/manifest` endpoint clean a playlist identically.
    *   **Proxying:** Direct CDN playback is the default path. `/api/stream` is a **fallback** for hostile networks and hotlink protection, not the normal route — video does not usually pass through the server.
    *   **Auth:** JWT access tokens (4 h) plus rotating opaque refresh tokens (30 d), with role-based access control (Admin/User).
    *   **Realtime:** A WebSocket at `/api/realtime` pushes per-user cache invalidation so a user's open tabs stay in sync.

## Building and Running

### Using Docker (Recommended)
```bash
cp .env.example .env
docker compose up --build
```
*   **Frontend:** [http://localhost:8080](http://localhost:8080)
*   **Backend:** [http://localhost:8787](http://localhost:8787)
*   **Default Admin:** `admin` / `admin` — seeded on boot from `ADMIN_PASSWORD`. Change after first login.

### Local Development
**Server:**
```bash
cd server
npm install   # runs prisma generate via postinstall
npm run dev   # node --watch
```
*Requires a running PostgreSQL instance and `DATABASE_URL` in `.env`.*

**Frontend:**
```bash
cd frontend
npm install
npm run dev   # Vite HMR on http://localhost:5173
```
*Vite proxies `/api` to `:8787`, including the `/api/realtime` WebSocket upgrade.*

### Database Management (Prisma)
```bash
cd server
npx prisma generate  # client is emitted to server/generated/prisma (non-standard path)
npx prisma db push   # sync schema to DB
npx prisma studio    # GUI for database
npm run seed         # providers + bootstrap admin
```

*There are no test suites, no linter, and no type checking. Verify changes by running the affected surface.*

## Project Structure

### `server/`
*   `src/index.js`: All 44 routes registered on one Express app, session issuing, boot seeding. No Router modules.
*   `src/middleware.js`: `requireAuth`, `requireRole`, `forbidAdminPlayback`, `asyncHandler`.
*   `src/auth.js`: JWT signing/verification, refresh-token hashing, bearer extraction.
*   `src/providers/`: Scraper implementations plus the registry.
*   `src/provider-access.js`: Global and per-user provider gating.
*   `src/stream.js`: Source health checks, m3u8 duration probing, the cleaned-manifest endpoint, and the HLS and poster proxies.
*   `src/monitoring.js`: Background provider health checks every 30 s.
*   `src/realtime.js`: WebSocket fan-out.
*   `src/cache.js`: Five LRU caches — search, detail, streamCheck, streamMetadata, mediaType.
*   `src/validators.js`: Zod schemas for request bodies.
*   `prisma/schema.prisma`: Database schema definition (note: `server/prisma/`, not under `src/`).

### `frontend/src/`
*   `RootApp.jsx`: The application root — router, session state, login, role-based redirects.
*   `UserPortal.jsx`: User shell (rail, topbar, toasts) plus the Favorites, Continue, History and Profile pages.
*   `AdminPortal.jsx`: Dashboard, providers, users, audit and account pages. Not translated — hardcoded English.
*   `App.jsx`: The **Browse/Watch page**, not the app root despite the name. Search, detail, source selection, hls.js lifecycle, progress, download.
*   `VideoPlayer.jsx`: Player chrome only — never constructs `Hls`.
*   `api.js`: Session in `localStorage` and a plain `fetch` layer with single-flight refresh and retry-once on 401. **Not Axios; there is no HTTP library.**
*   `realtime.js`, `adfilter.js`, `download.js`, `i18n.js`, `portal-chrome.js`: WebSocket client, ad stripping, client-side downloads, translations, and the topbar/language contexts.

### `shared/`
*   `adfilter-core.js`: Ad-run classification, imported by both the server and the bundle.
*   `api/README.md`: API behaviour every client must get right. Keep it current when the contract changes.

### `PoC/`
Standalone Python scripts (`requests` + `BeautifulSoup`) used for testing scraping logic independently of the Node.js environment.

## Development Conventions

*   **Surgical Updates:** When modifying scrapers, always verify against the current structure of the target site. Use `PoC/` scripts for rapid testing.
*   **Ordering matters in the stream path:** ad stripping must run *before* proxy URL rewriting. `rewritePlaylist` collapses every segment into the same `/api/stream` directory, which destroys the directory signal the ad classifier depends on.
*   **Type Safety:** Zod validates request bodies (`server/src/validators.js`). Responses are hand-built literals with no schema, and there is no OpenAPI document.
*   **API Versioning:** every route answers at both `/api/…` and `/api/v1/…`; a middleware rewrites the prefix away before routing. The web app uses the unversioned paths, native clients pin to `/api/v1`, so the unversioned surface can change without breaking an installed build that nothing updates automatically.
*   **Naming:** Backend uses `camelCase` for variables and `snake_case` for database mappings in Prisma.
*   **HLS Playback:** `Hls.isSupported()` is checked *before* `canPlayType`, because Chrome on macOS claims "maybe" for HLS and then fails to demux. Direct play is preferred; the proxy is the fallback.
*   **Commits:** lower-case subjects with an area scope (`feat(player):`, `fix(stream):`). Bodies explain why, and state what was verified.

## Key Features to Remember
*   **Search Aggregation:** Results from all permitted providers are combined in the UI. `/api/search` returns **200 even when a provider fails** — check the per-provider `error` field.
*   **Progressive Source Discovery:** `/api/sources` streams NDJSON as each source finishes its health probe. Parse it incrementally; failing sources are never emitted.
*   **Watch Progress:** Saved via `/api/me/progress`; `progressPercent` and `isCompleted` are derived server-side. `/api/me/continue-watching` collapses rows to one per title and flags `nextUp`.
*   **Health Checks:** Providers are monitored and can be disabled globally by an admin, or per user.
*   **Poster Proxy:** Images are proxied via `/api/poster?target=...` to avoid 403 errors. The parameter is `target`, and the route is behind auth.
*   **Admins cannot watch:** `forbidAdminPlayback` 403s every content and library route. Login itself does not check role, so a request carrying `X-StreamHub-Client` is refused at login and refresh instead of being handed a token that fails everywhere. The web app omits that header because its login form is shared with the admin console.
