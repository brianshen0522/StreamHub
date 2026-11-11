#!/usr/bin/env bash
# Usage: ./build.sh test     — cargo test for the core crate (and app unit tests)
#        ./build.sh check    — cargo check the wasm app
#        ./build.sh release  — trunk build --release into tizen/dist
#        ./build.sh dev      — debug trunk build into tizen/dist (faster)
# Extra env: STREAMHUB_API_BASE (compiled-in server, default the public domain).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(dirname "$here")"
docker build -q -t streamhub-tizen-build "$here/build" >/dev/null
run() {
  docker run --rm -u "$(id -u):$(id -g)" \
    -e CARGO_HOME=/cargo -e STREAMHUB_API_BASE="${STREAMHUB_API_BASE:-}" \
    -e HOME=/tmp \
    -v streamhub-tizen-cargo:/cargo-cache \
    -v "$repo":/src -w /src/tizen \
    streamhub-tizen-build bash -c "mkdir -p /cargo-cache && ln -sfn /cargo-cache /tmp/c; export CARGO_HOME=/cargo-cache; export RUSTUP_HOME=/usr/local/rustup; export PATH=/usr/local/cargo/bin:\$PATH; $*"
}
case "${1:-release}" in
  test)    run "cargo test -p streamhub-core" ;;
  check)   run "cargo check -p streamhub-tizen --target wasm32-unknown-unknown" ;;
  dev)     run "cd app && trunk build --dist ../dist" ;;
  release) run "cd app && trunk build --release --dist ../dist" ;;
  *) echo "unknown: $1"; exit 2 ;;
esac
