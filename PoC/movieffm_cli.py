#!/usr/bin/env python3
from __future__ import annotations

import html
import json
import re
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from typing import Any
from urllib.parse import quote, urljoin

import requests
from bs4 import BeautifulSoup


BASE_URL = "https://www.movieffm.net"
SEARCH_PATH = "/xssearch"
USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Safari/537.36"
)
TIMEOUT = 20
DEFAULT_MAX_PAGES = 3
CHECK_WORKERS = 12


@dataclass
class SearchResult:
    title: str
    url: str
    media_type: str
    poster_url: str


@dataclass
class StreamEntry:
    source_index: int
    source_label: str
    episode_label: str
    url: str
    collection_label: str = ""
    status_code: int | None = None
    ok: bool = False
    note: str = ""


@dataclass
class SeasonPage:
    label: str
    url: str


class MovieFFMClient:
    def __init__(self) -> None:
        self.session = requests.Session()
        self.session.headers.update(
            {
                "User-Agent": USER_AGENT,
                "Accept-Language": "zh-TW,zh;q=0.9,en;q=0.8",
                "Referer": f"{BASE_URL}/",
            }
        )

    def fetch_text(self, url: str) -> str:
        response = self.session.get(url, timeout=TIMEOUT)
        response.raise_for_status()
        response.encoding = response.encoding or response.apparent_encoding or "utf-8"
        return response.text

    def search(self, keyword: str, max_pages: int = DEFAULT_MAX_PAGES) -> list[SearchResult]:
        results: list[SearchResult] = []
        seen: set[str] = set()

        for page in range(1, max_pages + 1):
            params = {"q": keyword}
            if page > 1:
                params["f"] = "_all"
                params["p"] = str(page)
            url = f"{BASE_URL}{SEARCH_PATH}?q={quote(keyword)}"
            if page > 1:
                url += f"&f=_all&p={page}"

            text = self.fetch_text(url)
            page_results = self._parse_search_results(text)
            if not page_results:
                break

            new_count = 0
            for item in page_results:
                if item.url in seen:
                    continue
                seen.add(item.url)
                results.append(item)
                new_count += 1

            if new_count == 0:
                break

        return results

    def _parse_search_results(self, html_text: str) -> list[SearchResult]:
        soup = BeautifulSoup(html_text, "html.parser")
        output: list[SearchResult] = []
        for item in soup.select("div.result-item"):
            title_tag = item.select_one("div.title a")
            thumb_type = item.select_one("div.thumbnail span")
            if not title_tag or not title_tag.get("href"):
                continue
            title = " ".join(title_tag.get_text(" ", strip=True).split())
            media_type = thumb_type.get_text(" ", strip=True) if thumb_type else "Unknown"
            poster_tag = item.select_one("div.thumbnail img")
            poster_url = poster_tag.get("src", "").strip() if poster_tag else ""
            output.append(
                SearchResult(
                    title=title,
                    url=title_tag["href"],
                    media_type=self._normalize_media_type(media_type),
                    poster_url=poster_url,
                )
            )
        return output

    def extract_streams(self, page_url: str) -> list[StreamEntry]:
        page_html = self.fetch_text(page_url)

        if "/tvshows/" in page_url:
            seasons = self.get_tvshow_seasons(page_url, page_html)
            if not seasons:
                raise RuntimeError("Could not find season pages in tvshow HTML.")
            raise RuntimeError("TV show pages require season selection before stream extraction.")

        drama_match = re.search(r"videourls:(\[\[.*?\]\])\s*,tables:", page_html, re.S)
        if drama_match:
            table_labels = self._extract_table_labels(page_html)
            groups = json.loads(self._clean_js_json(drama_match.group(1)))
            return self._build_drama_streams(groups, table_labels)

        movie_match = re.search(r"videourls:(\[.*?\])\s*,isActive:", page_html, re.S)
        if movie_match:
            sources = json.loads(self._clean_js_json(movie_match.group(1)))
            return self._build_movie_streams(sources)

        raise RuntimeError("Could not find videourls in page HTML.")

    def get_tvshow_seasons(self, page_url: str, html_text: str | None = None) -> list[SeasonPage]:
        if html_text is None:
            html_text = self.fetch_text(page_url)
        soup = BeautifulSoup(html_text, "html.parser")
        output: list[SeasonPage] = []
        seen: set[str] = set()

        for anchor in soup.select("a[href]"):
            href = anchor.get("href", "").strip()
            if "/drama/" not in href:
                continue
            full_url = urljoin(page_url, href)
            if full_url in seen:
                continue

            text = " ".join(anchor.get_text(" ", strip=True).split())
            parent_text = " ".join(anchor.parent.get_text(" ", strip=True).split()) if anchor.parent else text
            label = parent_text or text or full_url

            if "Season" not in label and "全" not in label:
                continue

            seen.add(full_url)
            output.append(SeasonPage(label=label, url=full_url))

        return output

    def get_season_episode_labels(self, season_url: str) -> list[str]:
        streams = self.extract_streams(season_url)
        labels: list[str] = []
        seen: set[str] = set()
        for stream in streams:
            key = self._canonical_episode_key(stream.episode_label)
            if key in seen:
                continue
            seen.add(key)
            labels.append(stream.episode_label.strip())
        return labels

    def get_season_episode_streams(self, season_url: str, episode_label: str) -> list[StreamEntry]:
        streams = self.extract_streams(season_url)
        chosen_key = self._canonical_episode_key(episode_label)
        return [stream for stream in streams if self._canonical_episode_key(stream.episode_label) == chosen_key]

    def _canonical_episode_key(self, label: str) -> str:
        clean = label.strip()
        numbers = re.findall(r"\d+", clean)
        if numbers:
            return str(int(numbers[-1]))
        return clean.lower()

    def _build_drama_streams(
        self, groups: list[list[dict[str, Any]]], table_labels: list[str]
    ) -> list[StreamEntry]:
        streams: list[StreamEntry] = []
        for source_index, group in enumerate(groups):
            label = table_labels[source_index] if source_index < len(table_labels) else f"Source {source_index + 1}"
            for episode in group:
                url = episode.get("url")
                if not isinstance(url, str) or ".m3u8" not in url:
                    continue
                streams.append(
                    StreamEntry(
                        source_index=source_index,
                        source_label=label,
                        episode_label=str(episode.get("name", f"EP{len(streams) + 1}")),
                        url=url,
                    )
                )
        return streams

    def _build_movie_streams(self, sources: list[dict[str, Any]]) -> list[StreamEntry]:
        streams: list[StreamEntry] = []
        for item in sources:
            url = item.get("url")
            media_type = item.get("type")
            if not isinstance(url, str) or media_type not in {"hls", "mp4"}:
                continue
            source_index = int(item.get("source", len(streams)))
            streams.append(
                StreamEntry(
                    source_index=source_index,
                    source_label=f"Source {source_index + 1}",
                    episode_label="Movie",
                    url=url,
                )
            )
        return streams

    def _extract_table_labels(self, page_html: str) -> list[str]:
        match = re.search(r"tables:(\[\{.*?\}\])\s*,tbcur:", page_html, re.S)
        if not match:
            return []
        tables = json.loads(self._clean_js_json(match.group(1)))
        labels: list[str] = []
        for item in tables:
            raw = str(item.get("ht", ""))
            text = BeautifulSoup(html.unescape(raw), "html.parser").get_text(" ", strip=True)
            labels.append(" ".join(text.split()))
        return labels

    def _clean_js_json(self, value: str) -> str:
        return html.unescape(value.replace("\\/", "/"))

    def _normalize_media_type(self, raw: str) -> str:
        text = raw.strip()
        if text == "電影":
            return "電影"
        if text in {"電視劇", "連續劇"}:
            return "電視劇"
        return text or "Unknown"

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
                    stream.ok = self._is_valid_m3u8(content.decode("utf-8", errors="ignore"))
                    stream.note = "OK" if stream.ok else "Invalid m3u8 content"
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
            stream.status_code = None
            stream.ok = False
            stream.note = f"Request failed: {exc.__class__.__name__}"
        return stream

    @staticmethod
    def _is_valid_m3u8(text: str) -> bool:
        t = text.lstrip()
        if not t.startswith("#EXTM3U"):
            return False
        return any(tag in t for tag in ("#EXTINF", "#EXT-X-STREAM-INF", "#EXT-X-TARGETDURATION", "#EXT-X-MEDIA-SEQUENCE"))


def prompt_choice(max_value: int) -> int:
    while True:
        raw = input(f"Choose 1-{max_value}: ").strip()
        if raw.isdigit():
            value = int(raw)
            if 1 <= value <= max_value:
                return value
        print("Invalid choice.")


def print_results(results: list[SearchResult]) -> None:
    for index, item in enumerate(results, start=1):
        print(f"{index:>2}. [{item.media_type}] {item.title}")
        print(f"    {item.url}")
        print(f"    Poster: {item.poster_url or 'N/A'}")


def print_streams(streams: list[StreamEntry]) -> None:
    current_collection = None
    current_source = None
    for index, stream in enumerate(streams, start=1):
        if stream.collection_label and stream.collection_label != current_collection:
            current_collection = stream.collection_label
            current_source = None
            print(f"\n{current_collection}")
        if stream.source_label != current_source:
            current_source = stream.source_label
            print(f"\n{current_source}")
        status = f"{stream.status_code}" if stream.status_code is not None else "ERR"
        badge = "OK" if stream.ok else "BAD"
        print(f"  {index:>2}. [{badge} {status}] {stream.episode_label}")
        print(f"      {stream.url}")


def print_stream_result(index: int, stream: StreamEntry) -> None:
    status = f"{stream.status_code}" if stream.status_code is not None else "ERR"
    badge = "OK" if stream.ok else "BAD"
    prefix = f"{stream.collection_label} | " if stream.collection_label else ""
    print(f"{index:>2}. [{badge} {status}] {prefix}{stream.source_label} | {stream.episode_label}")
    print(f"    {stream.url}")


def main() -> int:
    client = MovieFFMClient()

    keyword = input("Keyword: ").strip()
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

    print(f"\nFound {len(results)} result(s):")
    print_results(results)

    choice = prompt_choice(len(results))
    selected = results[choice - 1]

    print(f"\nSelected: {selected.title}")
    try:
        if "/tvshows/" in selected.url:
            print("Fetching seasons...")
            seasons = client.get_tvshow_seasons(selected.url)
            if not seasons:
                print("No seasons found.")
                return 1

            for index, season in enumerate(seasons, start=1):
                print(f"{index:>2}. {season.label}")
                print(f"    {season.url}")

            season_choice = prompt_choice(len(seasons))
            chosen_season = seasons[season_choice - 1]

            print(f"\nSelected season: {chosen_season.label}")
            print("Fetching episodes...")
            episodes = client.get_season_episode_labels(chosen_season.url)
            if not episodes:
                print("No episodes found.")
                return 1

            for index, episode in enumerate(episodes, start=1):
                print(f"{index:>2}. {episode}")

            episode_choice = prompt_choice(len(episodes))
            chosen_episode = episodes[episode_choice - 1]

            print(f"\nSelected episode: {chosen_episode}")
            print("Extracting streams...")
            streams = client.get_season_episode_streams(chosen_season.url, chosen_episode)
            for stream in streams:
                stream.collection_label = chosen_season.label
        else:
            print("Extracting streams...")
            streams = client.extract_streams(selected.url)
    except Exception as exc:
        print(f"Extraction failed: {exc}")
        return 1

    if not streams:
        print("No playable streams found.")
        return 0

    print(f"Checking {len(streams)} stream(s)...")
    checked: list[StreamEntry | None] = [None] * len(streams)
    with ThreadPoolExecutor(max_workers=CHECK_WORKERS) as executor:
        future_map = {
            executor.submit(client.check_stream, stream): index
            for index, stream in enumerate(streams)
        }
        for future in as_completed(future_map):
            index = future_map[future]
            stream = future.result()
            checked[index] = stream
            print_stream_result(index + 1, stream)

    done_streams = [stream for stream in checked if stream is not None]
    ok_count = sum(1 for stream in done_streams if stream.ok)
    print(f"\nSummary: {ok_count}/{len(done_streams)} streams OK (http 2xx + valid m3u8 content).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
