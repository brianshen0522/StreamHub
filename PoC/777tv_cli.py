#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from urllib.parse import urljoin

import requests
from bs4 import BeautifulSoup


BASE_URL = "https://777tv.ai"
PLAY_BASE_URL = "https://play.777tv.ai"
SEARCH_PATH = "/vod/search.html"
USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Safari/537.36"
)
TIMEOUT = 20
CHECK_WORKERS = 12
TYPE_WORKERS = 8


@dataclass
class SearchResult:
    title: str
    url: str
    media_type: str
    poster_url: str


@dataclass
class PlayOption:
    source_label: str
    episode_label: str
    url: str
    group_key: str


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


class TV777Client:
    def __init__(self) -> None:
        self.session = requests.Session()
        self.session.headers.update(
            {
                "User-Agent": USER_AGENT,
                "Accept-Language": "zh-TW,zh;q=0.9,en;q=0.8",
                "Referer": f"{BASE_URL}/",
            }
        )

    def fetch_text(self, url: str, *, referer: str | None = None) -> str:
        headers = {}
        if referer:
            headers["Referer"] = referer
        response = self.session.get(url, timeout=TIMEOUT, headers=headers)
        response.raise_for_status()
        response.encoding = response.encoding or response.apparent_encoding or "utf-8"
        return response.text

    def search(self, keyword: str) -> list[SearchResult]:
        response = self.session.post(
            f"{BASE_URL}{SEARCH_PATH}",
            data={"wd": keyword, "submit": ""},
            timeout=TIMEOUT,
        )
        response.raise_for_status()
        response.encoding = response.encoding or response.apparent_encoding or "utf-8"
        soup = BeautifulSoup(response.text, "html.parser")

        results: list[SearchResult] = []
        seen: set[str] = set()
        for item in soup.select("li.stui-vodlist__item"):
            title_link = item.select_one("h4.stui-vodlist__title a[href*='/vod/detail/id/']")
            thumb = item.select_one("a.stui-vodlist__thumb[href*='/vod/detail/id/']")
            note = item.select_one(".pic-text")
            if not title_link and not thumb:
                continue

            anchor = title_link or thumb
            href = anchor.get("href", "").strip()
            if not href:
                continue

            url = urljoin(BASE_URL, href)
            if url in seen:
                continue

            title = anchor.get("title", "").strip() or " ".join(anchor.get_text(" ", strip=True).split())
            if not title:
                continue

            media_type = note.get_text(" ", strip=True) if note else "Unknown"
            poster_url = ""
            if thumb:
                poster_url = thumb.get("data-original", "").strip() or thumb.get("data-src", "").strip()
            results.append(SearchResult(title=title, url=url, media_type=media_type, poster_url=poster_url))
            seen.add(url)

        self._populate_media_types(results)
        return results

    def get_play_options(self, detail_url: str) -> list[PlayOption]:
        html_text = self.fetch_text(detail_url)
        soup = BeautifulSoup(html_text, "html.parser")

        options: list[PlayOption] = []
        source_index = 0
        for panel in soup.select("div.stui-pannel"):
            title_node = panel.select_one("div.stui-pannel__head h4.title")
            playlist = panel.select_one("ul.stui-content__playlist")
            if not title_node or not playlist:
                continue

            source_label = " ".join(title_node.get_text(" ", strip=True).split())
            if not source_label:
                source_label = f"Source {source_index + 1}"

            panel_added = False
            for anchor in playlist.select("a[href*='/vod/play/']"):
                href = anchor.get("href", "").strip()
                if not href:
                    continue

                url = urljoin(PLAY_BASE_URL, href)
                episode_label = " ".join(anchor.get_text(" ", strip=True).split())
                if not episode_label:
                    episode_label = url.rsplit("/", 1)[-1]

                options.append(
                    PlayOption(
                        source_label=source_label,
                        episode_label=episode_label,
                        url=url,
                        group_key=self._group_key(episode_label, url),
                    )
                )
                panel_added = True

            if panel_added:
                source_index += 1

        if not options:
            raise RuntimeError("Could not find play options in detail HTML.")

        return options

    def _populate_media_types(self, results: list[SearchResult]) -> None:
        with ThreadPoolExecutor(max_workers=TYPE_WORKERS) as executor:
            future_map = {
                executor.submit(self._detect_media_type, result.url): result
                for result in results
            }
            for future in as_completed(future_map):
                result = future_map[future]
                try:
                    result.media_type = future.result()
                except requests.RequestException:
                    result.media_type = self._normalize_media_type(result.media_type)

    def _detect_media_type(self, detail_url: str) -> str:
        html_text = self.fetch_text(detail_url)
        soup = BeautifulSoup(html_text, "html.parser")
        active = soup.select_one(".stui-header__menu li.active a")
        if active:
            return self._normalize_media_type(active.get_text(" ", strip=True))
        return "Unknown"

    def get_episode_labels(self, options: list[PlayOption]) -> list[str]:
        labels: list[str] = []
        seen: set[str] = set()
        for option in options:
            if option.group_key in seen:
                continue
            seen.add(option.group_key)
            labels.append(option.episode_label)
        return labels

    def extract_streams(self, options: list[PlayOption], selected_label: str) -> list[StreamEntry]:
        selected_key = self._group_key(selected_label, "")
        matched = [option for option in options if option.group_key == selected_key]
        streams: list[StreamEntry] = []

        for source_index, option in enumerate(matched):
            stream_url = self._extract_m3u8_from_play_page(option.url)
            if not stream_url:
                continue
            streams.append(
                StreamEntry(
                    source_index=source_index,
                    source_label=option.source_label,
                    episode_label=option.episode_label,
                    url=stream_url,
                )
            )

        return streams

    def extract_movie_streams(self, options: list[PlayOption]) -> list[StreamEntry]:
        streams: list[StreamEntry] = []

        for source_index, option in enumerate(options):
            stream_url = self._extract_m3u8_from_play_page(option.url)
            if not stream_url:
                continue
            streams.append(
                StreamEntry(
                    source_index=source_index,
                    source_label=option.source_label,
                    episode_label="Movie",
                    url=stream_url,
                )
            )

        return streams

    def _extract_m3u8_from_play_page(self, play_url: str) -> str | None:
        html_text = self.fetch_text(play_url, referer=BASE_URL)
        match = re.search(r"var\s+player_\w+\s*=\s*(\{.*?\})</script>", html_text, re.S)
        if not match:
            match = re.search(r"var\s+player_data\s*=\s*(\{.*?\})</script>", html_text, re.S)
        if not match:
            return None

        payload = json.loads(match.group(1).replace("\\/", "/"))
        url = payload.get("url")
        if isinstance(url, str) and url:
            return url
        return None

    def _group_key(self, label: str, play_url: str) -> str:
        numbers = re.findall(r"\d+", label)
        if numbers:
            return f"ep:{int(numbers[-1])}"

        normalized = label.strip().lower()
        if normalized in {
            "正片",
            "hd",
            "hd中字",
            "hd國語",
            "hd国语",
            "国语",
            "國語",
            "中字",
            "tc",
            "tc中字",
            "tcv2",
            "預告片",
            "预告片",
        }:
            return f"variant:{normalized}"

        nid_match = re.search(r"/nid/(\d+)\.html", play_url)
        if nid_match and not label:
            return f"nid:{nid_match.group(1)}"

        return f"label:{normalized}"

    def _normalize_media_type(self, raw: str) -> str:
        text = raw.strip()
        if text in {"電影", "动作片", "劇情片", "喜劇片"}:
            return "電影"
        if text in {"電視劇", "连续剧", "連續劇", "美劇", "韓劇", "陸劇", "港劇", "日劇", "台劇", "泰劇"}:
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


def print_episode_labels(labels: list[str]) -> None:
    for index, label in enumerate(labels, start=1):
        print(f"{index:>2}. {label}")


def print_stream_result(index: int, stream: StreamEntry) -> None:
    status = f"{stream.status_code}" if stream.status_code is not None else "ERR"
    badge = "OK" if stream.ok else "BAD"
    prefix = f"{stream.collection_label} | " if stream.collection_label else ""
    print(f"{index:>2}. [{badge} {status}] {prefix}{stream.source_label} | {stream.episode_label}")
    print(f"    {stream.url}")


def main() -> int:
    client = TV777Client()

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
        options = client.get_play_options(selected.url)
    except Exception as exc:
        print(f"Extraction failed: {exc}")
        return 1

    try:
        if selected.media_type == "電影":
            print("Extracting streams...")
            streams = client.extract_movie_streams(options)
        else:
            print("Fetching episodes...")
            labels = client.get_episode_labels(options)
            if not labels:
                print("No episodes found.")
                return 0

            print(f"\nFound {len(labels)} option(s):")
            print_episode_labels(labels)

            episode_choice = prompt_choice(len(labels))
            selected_label = labels[episode_choice - 1]
            print(f"\nSelected episode: {selected_label}")
            print("Extracting streams...")
            streams = client.extract_streams(options, selected_label)
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
