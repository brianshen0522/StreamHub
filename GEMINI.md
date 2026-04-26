# StreamHub: Project Context & Guidelines

StreamHub is a self-hosted streaming aggregator that scrapes content from multiple providers (MovieFFM, 777TV, DramaSQ), validates streams, and provides a unified, ad-free viewing experience.

## Project Overview

*   **Architecture:** Monorepo-style with separate `frontend` (React/Vite) and `server` (Node.js/Express) directories.
*   **Tech Stack:**
    *   **Frontend:** React 18, Vite, `hls.js` for playback, Vanilla CSS for styling.
    *   **Backend:** Node.js (Express), Prisma ORM (PostgreSQL), `cheerio` for scraping, `lru-cache` for performance.
    *   **Database:** PostgreSQL 17.
    *   **Infrastructure:** Docker Compose for containerization.
*   **Core Logic:**
    *   **Scraping:** Modular provider system in `server/src/providers/`.
    *   **Stream Validation:** Backend checks HLS manifests (`m3u8`) for validity before serving.
    *   **Proxying:** Backend proxies HLS segments and poster images to bypass CORS and hotlink protection.
    *   **Auth:** JWT-based authentication with access/refresh tokens and role-based access control (Admin/User).

## Building and Running

### Using Docker (Recommended)
```bash
# Start all services (Database, Backend, Frontend)
docker compose up --build
```
*   **Frontend:** [http://localhost:8080](http://localhost:8080)
*   **Backend:** [http://localhost:8787](http://localhost:8787)
*   **Default Admin:** `admin` / `admin` (Change after first login)

### Local Development
**Server:**
```bash
cd server
npm install
npm run dev  # Starts with node --watch
```
*Requires a running PostgreSQL instance and `DATABASE_URL` in `.env`.*

**Frontend:**
```bash
cd frontend
npm install
npm run dev  # Starts Vite HMR on http://localhost:5173
```

### Database Management (Prisma)
```bash
cd server
npx prisma generate  # Generate client
npx prisma db push   # Sync schema to DB
npx prisma studio    # GUI for database
```

## Project Structure

### `server/src/`
*   `index.js`: Main entry point, API routes, and auth logic.
*   `providers/`: Scraper implementations for each site.
*   `stream.js`: Logic for HLS validation and proxying.
*   `monitoring.js`: Background health checks for providers.
*   `middleware.js`: Auth, roles, and error handling.
*   `prisma/schema.prisma`: Database schema definition.

### `frontend/src/`
*   `App.jsx`: Main application container.
*   `UserPortal.jsx`: Main viewing interface.
*   `AdminPortal.jsx`: Dashboard and management interface.
*   `api.js`: Axios-based API client.

### `PoC/`
Standalone Python scripts (`requests` + `BeautifulSoup`) used for testing scraping logic independently of the Node.js environment.

## Development Conventions

*   **Surgical Updates:** When modifying scrapers, always verify against the current structure of the target site. Use `PoC/` scripts for rapid testing.
*   **Stream Proxying:** High-bandwidth traffic passes through the server proxy. Ensure `server/src/stream.js` is optimized.
*   **Type Safety:** Use Zod for request validation (defined in `server/src/validators.js`).
*   **Naming:** Backend uses `camelCase` for variables and `snake_case` for database mappings in Prisma.
*   **HLS Playback:** Frontend uses `hls.js` with a fallback mechanism. Direct play is preferred; proxy is used if direct fails.

## Key Features to Remember
*   **Search Aggregation:** Results from all providers are combined in the UI.
*   **Watch Progress:** Saved to DB via `/api/me/progress`.
*   **Health Checks:** Providers are monitored and can be disabled globally by an admin.
*   **Poster Proxy:** Images are proxied via `/api/poster?url=...` to avoid 403 errors.
