#!/usr/bin/env bash
# Load the packaged app in a real Chromium 85 — the engine of the oldest
# supported set (LS32BM703UC, 2022 Smart Monitor M7, Tizen 6.5) — the way the
# set loads it: from file://, with the compiled-in server address.
#   ./m85.sh                 build release, stage, probe → test-results/m85.png
#   QA_SKIP_BUILD=1 ./m85.sh reuse dist/
# Exit 0 when the app mounts with no unexpected console errors.
set -euo pipefail
qa="$(cd "$(dirname "$0")" && pwd)"; tizen="$(dirname "$qa")"
cache="${M85_CACHE:-$HOME/.cache/streamhub-chromium85}"
rev=782797   # Chromium 85.0.4183.0, the official snapshot build
if [[ ! -x "$cache/chrome-linux/chrome" ]]; then
  mkdir -p "$cache"
  curl -sSL -o "$cache/chrome-linux.zip" "https://commondatastorage.googleapis.com/chromium-browser-snapshots/Linux_x64/$rev/chrome-linux.zip"
  (cd "$cache" && unzip -qo chrome-linux.zip && rm chrome-linux.zip)
fi
[[ -n "${QA_SKIP_BUILD:-}" ]] || "$tizen/build.sh" release
SKIP_BUILD=1 STAGE_ONLY=1 "$tizen/package.sh" >/dev/null
[[ -d "$qa/node_modules/playwright" ]] || (cd "$qa" && npm install --no-fund --no-audit)
mkdir -p "$qa/test-results"
docker run --rm --network host --ipc host -u "$(id -u):$(id -g)" -e HOME=/tmp \
  -v "$cache:/c:ro" -v "$qa/test-results:/out" -v "$tizen/target/wgt-stage:/w:ro" -v "$qa:/qa:ro" \
  mcr.microsoft.com/playwright:v1.65.0-noble bash -c '
    /c/chrome-linux/chrome --headless --no-sandbox --disable-gpu --allow-file-access-from-files \
      --remote-debugging-port=9333 --window-size=1920,1080 about:blank >/dev/null 2>&1 &
    for i in $(seq 1 30); do curl -s http://127.0.0.1:9333/json/version >/dev/null && break; sleep 0.3; done
    node /qa/tools/m85-probe.cjs "${1:-}"' _ "${1:-}"
