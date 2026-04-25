# StreamHub

A self-hosted streaming aggregator that searches **movieffm** and **777tv** simultaneously, validates HLS sources, and plays them in-browser — no ads, no redirects.

![StreamHub UI](https://img.shields.io/badge/stack-React%20%2B%20Node.js-informational)
![Docker](https://img.shields.io/badge/docker-ready-blue)

---

## Features

- **Unified search** across movieffm.net and 777tv.ai in one query
- **Strict stream validation** — checks HTTP status *and* reads m3u8 content (`#EXTM3U` + segment tags); HTML error pages no longer pass as valid
- **In-browser HLS playback** via hls.js, with automatic fallback to the server proxy if direct play fails
- **m3u8 proxy** that rewrites segment and `#EXT-X-KEY` URLs through the backend to bypass CORS and hotlinking
- **Poster proxy** to bypass hotlink protection on cover images
- **LRU caching** for search results, detail pages, stream checks, and media-type detection
- Traditional Chinese / English UI, language switch does not interrupt playback

---

## Quick Start

### Docker (recommended)

```bash
docker compose up --build
```

| Service  | URL |
|---|---|
| Frontend | http://localhost:8080 |
| Backend  | http://localhost:8787/api/health |

### Local Dev

```bash
# Backend (auto-reloads on file change)
cd server && npm install && npm run dev

# Frontend (Vite HMR)
cd frontend && npm install && npm run dev
# → http://localhost:5173  (proxies /api to localhost:8787)
```

---

## Architecture

```
browser
  └── React SPA (Vite)
        └── /api/*  ──►  Express server
                            ├── /api/search        scrape & cache search results
                            ├── /api/item          scrape detail page (streams / seasons / episodes)
                            ├── /api/episodes      episode list for a season URL
                            ├── /api/sources       check & filter streams for one episode
                            ├── /api/check-sources batch check raw stream list (movies)
                            ├── /api/stream        HLS proxy + m3u8 URL rewriting
                            └── /api/poster        image proxy
```

### Backend (`server/`)

| File | Role |
|---|---|
| `src/index.js` | Express routes |
| `src/stream.js` | Stream checker, HLS proxy, poster proxy |
| `src/cache.js` | Four LRU caches (search 5 min, detail 10 min, streamCheck 3 min, mediaType 10 min) |
| `src/providers/movieffm.js` | Scraper for movieffm.net |
| `src/providers/seventv.js` | Scraper for 777tv.ai |
| `src/providers/index.js` | Provider registry |
| `src/utils/http.js` | Shared `fetchText` / `fetchJson` with browser UA |

**Adding a provider:** implement `{ search, getItem, getEpisodes, getEpisodeStreams }` and register it in `src/providers/index.js`.

### Frontend (`frontend/src/`)

Single `App.jsx` component. UI flow:

1. Search → grouped poster grid (skeleton shimmer while loading)
2. Click poster → detail panel opens (poster + title on left, player on right)
3. Select season → episode list loads
4. Select episode → sources checked and listed as compact pills (green dot = direct, orange = proxy)
5. Source selected → HLS plays; on fatal error auto-falls back to proxy URL

---

## PoC Scripts

Standalone CLI tools in `PoC/` for scraping without the web UI:

```bash
# movieffm
pip install requests beautifulsoup4
python3 PoC/movieffm_cli.py

# 777tv
python3 PoC/777tv_cli.py
```

Both scripts apply the same strict m3u8 validation as the backend (reads first 4 KB, checks `#EXTM3U` + segment tags).

---

## Requirements

- Docker ≥ 26 — for the compose setup
- Node.js ≥ 22 — for local dev
- Python ≥ 3.11 + `requests` + `beautifulsoup4` — for the PoC scripts only
