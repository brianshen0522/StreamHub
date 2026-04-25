#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from urllib.parse import urljoin

import requests
from bs4 import BeautifulSoup


BASE_URL = "https://dramasq.io"
USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Safari/537.36"
)
TIMEOUT = 20
CHECK_WORKERS = 12


@dataclass
class SearchResult:
    title: str
    url: str
    show_id: str
    poster_url: str


@dataclass
class Episode:
    label: str
    play_url: str   # /vodplay/{id}/ep{N}.html
    ep_slug: str    # ep{N}  — used for the /drq/ API call


@dataclass
class StreamEntry:
    index: int
    src_site: str
    url: str
    status_code: int | None = None
    ok: bool = False
    note: str = ""


class DramasqClient:
    def __init__(self) -> None:
        self.session = requests.Session()
        self.session.headers.update(
            {
                "User-Agent": USER_AGENT,
                "Accept-Language": "zh-TW,zh;q=0.9,en;q=0.8",
                "Referer": f"{BASE_URL}/",
            }
        )

    def _get(self, url: str, **kwargs) -> requests.Response:
        response = self.session.get(url, timeout=TIMEOUT, **kwargs)
        response.raise_for_status()
        response.encoding = response.encoding or response.apparent_encoding or "utf-8"
        return response

    # ── Search ──────────────────────────────────────────────────────────────

    def search(self, keyword: str) -> list[SearchResult]:
        response = self._get(f"{BASE_URL}/search", params={"q": keyword})
        soup = BeautifulSoup(response.text, "html.parser")

        results: list[SearchResult] = []
        seen: set[str] = set()
        for anchor in soup.select("div.list a.drama[href*='/detail/']"):
            href = anchor.get("href", "").strip()
            if not href:
                continue
            url = urljoin(BASE_URL, href)
            if url in seen:
                continue
            seen.add(url)

            title = " ".join(anchor.get_text(" ", strip=True).split())
            show_id = _extract_show_id(href)
            poster_url = f"{BASE_URL}/uuimg/{show_id}.jpg" if show_id else ""
            results.append(SearchResult(title=title, url=url, show_id=show_id, poster_url=poster_url))

        return results

    # ── Detail page ─────────────────────────────────────────────────────────

    def get_episodes(self, detail_url: str) -> list[Episode]:
        response = self._get(detail_url, headers={"Referer": BASE_URL + "/"})
        soup = BeautifulSoup(response.text, "html.parser")

        episodes: list[Episode] = []
        seen: set[str] = set()
        for anchor in soup.select("div.eps a[href*='/vodplay/']"):
            href = anchor.get("href", "").strip()
            if not href or href in seen:
                continue
            seen.add(href)

            label = " ".join(anchor.get_text(" ", strip=True).split())
            play_url = urljoin(BASE_URL, href)
            # /vodplay/201940342/ep44.html  →  ep44
            ep_slug = href.rstrip("/").rsplit("/", 1)[-1].removesuffix(".html")
            episodes.append(Episode(label=label, play_url=play_url, ep_slug=ep_slug))

        # Episodes listed newest-first on the page; reverse to chronological order
        return list(reversed(episodes))

    # ── Stream API ──────────────────────────────────────────────────────────

    def get_streams(self, show_id: str, ep_slug: str) -> list[StreamEntry]:
        url = f"{BASE_URL}/drq/{show_id}/{ep_slug}"
        referer = f"{BASE_URL}/vodplay/{show_id}/{ep_slug}.html"
        response = self._get(url, headers={"Referer": referer})
        data = response.json()

        streams: list[StreamEntry] = []
        for index, item in enumerate(data.get("video_plays", []), start=1):
            play_url = item.get("play_data", "").strip()
            src_site = item.get("src_site", f"source{index}").strip()
            if play_url:
                streams.append(StreamEntry(index=index, src_site=src_site, url=play_url))

        return streams

    # ── Stream health check ─────────────────────────────────────────────────

    def check_stream(self, stream: StreamEntry) -> StreamEntry:
        try:
            if ".m3u8" in stream.url.lower():
                response = self.session.get(
                    stream.url,
                    allow_redirects=True,
                    timeout=TIMEOUT,
                    headers={"Range": "bytes=0-4095"},
                    stream=True,
                )
                stream.status_code = response.status_code
                if response.status_code in {200, 206}:
                    content = next(response.iter_content(4096), b"")
                    response.close()
                    stream.ok = _is_valid_m3u8(content.decode("utf-8", errors="ignore"))
                    stream.note = "OK" if stream.ok else "Invalid m3u8"
                else:
                    response.close()
                    stream.ok = False
                    stream.note = "Unavailable"
            else:
                response = self.session.head(stream.url, allow_redirects=True, timeout=TIMEOUT)
                if response.status_code in {403, 405, 500, 501}:
                    response = self.session.get(stream.url, stream=True, allow_redirects=True, timeout=TIMEOUT)
                stream.status_code = response.status_code
                stream.ok = 200 <= response.status_code < 300
                stream.note = "OK" if stream.ok else "Unavailable"
                response.close()
        except requests.RequestException as exc:
            stream.ok = False
            stream.note = f"{exc.__class__.__name__}"
        return stream


# ── Helpers ──────────────────────────────────────────────────────────────────

def _extract_show_id(href: str) -> str:
    # /detail/201940342.html  →  201940342
    match = re.search(r"/detail/(\d+)\.html", href)
    return match.group(1) if match else ""


def _is_valid_m3u8(text: str) -> bool:
    t = text.lstrip()
    if not t.startswith("#EXTM3U"):
        return False
    return any(tag in t for tag in ("#EXTINF", "#EXT-X-STREAM-INF", "#EXT-X-TARGETDURATION", "#EXT-X-MEDIA-SEQUENCE"))


def _prompt_choice(max_value: int) -> int:
    while True:
        raw = input(f"Choose 1-{max_value}: ").strip()
        if raw.isdigit():
            value = int(raw)
            if 1 <= value <= max_value:
                return value
        print("Invalid choice.")


# ── Display helpers ───────────────────────────────────────────────────────────

def print_results(results: list[SearchResult]) -> None:
    for i, r in enumerate(results, start=1):
        print(f"{i:>2}. {r.title}")
        print(f"    {r.url}")
        print(f"    Poster: {r.poster_url}")


def print_episodes(episodes: list[Episode]) -> None:
    cols = 8
    for i, ep in enumerate(episodes, start=1):
        end = "\n" if i % cols == 0 else "  "
        print(f"{i:>3}. {ep.label}", end=end)
    if len(episodes) % cols != 0:
        print()


def print_stream(stream: StreamEntry) -> None:
    status = str(stream.status_code) if stream.status_code is not None else "ERR"
    badge = "OK " if stream.ok else "BAD"
    print(f"  [{badge} {status}] {stream.src_site}")
    print(f"         {stream.url}")


# ── Main ─────────────────────────────────────────────────────────────────────

def main() -> int:
    client = DramasqClient()

    keyword = input("Search keyword: ").strip()
    if not keyword:
        print("Keyword is required.")
        return 1

    print("\nSearching...")
    try:
        results = client.search(keyword)
    except requests.RequestException as exc:
        print(f"Search failed: {exc}")
        return 1

    if not results:
        print("No results found.")
        return 0

    print(f"\nFound {len(results)} result(s):\n")
    print_results(results)

    choice = _prompt_choice(len(results))
    selected = results[choice - 1]
    print(f"\nSelected: {selected.title}")

    print("Fetching episodes...")
    try:
        episodes = client.get_episodes(selected.url)
    except Exception as exc:
        print(f"Failed to fetch episodes: {exc}")
        return 1

    if not episodes:
        print("No episodes found.")
        return 0

    print(f"\nFound {len(episodes)} episode(s):\n")
    print_episodes(episodes)

    ep_choice = _prompt_choice(len(episodes))
    selected_ep = episodes[ep_choice - 1]
    print(f"\nSelected: {selected_ep.label}")

    print("Fetching stream URLs...")
    try:
        streams = client.get_streams(selected.show_id, selected_ep.ep_slug)
    except Exception as exc:
        print(f"Failed to fetch streams: {exc}")
        return 1

    if not streams:
        print("No streams found.")
        return 0

    print(f"Checking {len(streams)} stream(s)...\n")
    checked: list[StreamEntry | None] = [None] * len(streams)
    with ThreadPoolExecutor(max_workers=CHECK_WORKERS) as executor:
        future_map = {
            executor.submit(client.check_stream, stream): i
            for i, stream in enumerate(streams)
        }
        for future in as_completed(future_map):
            i = future_map[future]
            stream = future.result()
            checked[i] = stream

    done = [s for s in checked if s is not None]
    done.sort(key=lambda s: s.index)
    for stream in done:
        print_stream(stream)

    ok_count = sum(1 for s in done if s.ok)
    print(f"\nSummary: {ok_count}/{len(done)} streams OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
