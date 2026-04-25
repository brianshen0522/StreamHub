# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**StreamHub** — a unified movie/TV search and HLS playback app aggregating two providers: `movieffm` (movieffm.net) and `777tv` (777tv.ai). The backend scrapes provider sites and proxies streams; the frontend is a React SPA with in-browser HLS playback.

## Commands

### Docker (full stack)
```bash
docker compose up --build
# Frontend: http://localhost:8080
# Backend:  http://localhost:8787/api/health
```

### Local dev
```bash
# Backend (Node, auto-restarts on file change)
cd server && npm install && npm run dev

# Frontend (Vite HMR)
cd frontend && npm install && npm run dev
```

There are no test suites and no linter configuration.

## Architecture

### Backend (`server/`)

Express app (`src/index.js`) with five API routes:

| Route | Description |
|---|---|
| `GET /api/search` | Search both/one provider in parallel |
| `GET /api/item` | Get item detail (movie streams or TV season/episode list) |
| `GET /api/episodes` | Get episode labels for a season URL |
| `GET /api/sources` | Get checked, available streams for one episode |
| `POST /api/check-sources` | Check a raw stream list (used for movies) |
| `GET /api/stream` | HLS/media proxy — rewrites m3u8 segment URLs to also go through this proxy |
| `GET /api/poster` | Image proxy for posters (bypasses hotlink protection) |

**Provider pattern** (`src/providers/`): each provider exports four functions — `search`, `getItem`, `getEpisodes`, `getEpisodeStreams`. Register a new provider in `src/providers/index.js`. The backend scrapes HTML with Cheerio and extracts stream URLs from embedded JSON in `<script>` tags.

**Caching** (`src/cache.js`): four LRU caches — `search` (5 min), `detail` (10 min), `streamCheck` (3 min), `mediaType` (10 min). Cache keys are namespaced by provider, e.g. `movieffm:search:<keyword>`.

**Stream check** (`src/stream.js`): `checkStream` HEAD-requests each stream URL, falls back to `GET bytes=0-0` for servers that reject HEAD. Only `2xx` streams reach the frontend. m3u8 playlist rewriting rewrites both segment URLs and `#EXT-X-KEY` URIs to route through `/api/stream`.

**HTTP utility** (`src/utils/http.js`): `fetchText`/`fetchJson` with a shared Chrome user-agent and `zh-TW` accept-language. All provider fetches go through these.

### Frontend (`frontend/`)

Single-file React SPA (`src/App.jsx`) with no routing library. State flows top-down through a single `App` component. Key state machine:

1. Search → grouped results by provider
2. Click result → `GET /api/item` → movie (load streams immediately) or TV (load season/episode list)
3. For TV: select episode → `GET /api/sources` → filtered stream list
4. Select source → HLS playback via hls.js (MSE) or native (Safari); on fatal error, auto-falls back from direct URL to `/api/stream` proxy URL

`src/i18n.js`: static translation map for `zh-TW` / `en`; language defaults from `navigator.language`.

**Provider-specific TV data shapes** (important asymmetry):
- `movieffm` TV: may have a `/tvshows/` page with multiple seasons → each season is a `/drama/` URL → episodes + streams extracted from that URL
- `777tv` TV: single detail page holds all episodes; stream URL extracted by fetching the individual play page and parsing `var player_*` JSON

### PoC scripts (`PoC/`)

Standalone Python CLI scripts (`movieffm_cli.py`, `777tv_cli.py`) used during initial exploration — not part of the running app.
