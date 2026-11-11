#!/usr/bin/env bash
# Build the app (debug) and run the Playwright specs against tizen/dist and
# the live server. Screenshots and results land in tizen/qa/test-results.
#
#   ./run.sh                      # build + all specs
#   ./run.sh specs/20-home.spec.js --headed
#   QA_SKIP_BUILD=1 ./run.sh      # reuse the existing dist/
#   QA_NATIVE=1 ./run.sh          # host Playwright instead of Docker
#
# Credentials: STREAMHUB_QA_USER / STREAMHUB_QA_PASSWORD in the environment, or
# an env file at STREAMHUB_QA_ENV (default tizen/qa/.env, gitignored).
# Server: STREAMHUB_QA_API (default http://localhost:8080).
#
# Playwright runs in mcr.microsoft.com/playwright:v1.65.0-noble by default,
# because this host's Chromium lacks its shared libraries (NSS, GTK, X11) and
# there is no root to install them. Host networking lets it reach :8080.
set -euo pipefail
qa="$(cd "$(dirname "$0")" && pwd)"
tizen="$(dirname "$qa")"
repo="$(dirname "$tizen")"
PW_VERSION=1.65.0

if [[ -z "${QA_SKIP_BUILD:-}" ]]; then
  "$tizen/build.sh" dev
fi
# The widget layout (dist + config.xml + icon), unsigned, for the file:// spec.
SKIP_BUILD=1 STAGE_ONLY=1 "$tizen/package.sh" >/dev/null
[[ -d "$qa/node_modules/@playwright/test" ]] || (cd "$qa" && npm install --no-fund --no-audit)
mkdir -p "$qa/test-results"

envfile="${STREAMHUB_QA_ENV:-$qa/.env}"
if [[ -n "${QA_NATIVE:-}" ]]; then
  cd "$qa" && STREAMHUB_QA_ENV="$envfile" exec npx playwright test "$@"
fi

mounts=(-v "$repo:$repo")
envs=(-e STREAMHUB_QA_API -e STREAMHUB_QA_PORT -e STREAMHUB_QA_USER -e STREAMHUB_QA_PASSWORD -e CI)
if [[ -f "$envfile" ]]; then
  mounts+=(-v "$envfile:/run/qa.env:ro")
  envs+=(-e STREAMHUB_QA_ENV=/run/qa.env)
fi
exec docker run --rm --network host --ipc host --init \
  -u "$(id -u):$(id -g)" -e HOME=/tmp \
  "${mounts[@]}" "${envs[@]}" -w "$qa" \
  "mcr.microsoft.com/playwright:v${PW_VERSION}-noble" \
  npx playwright test "$@"
