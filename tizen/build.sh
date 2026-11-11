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
# Trunk 0.21 injects a <script type="module"> loader (and modulepreload /
# preload links) even with data-wasm-no-import. Chromium refuses module
# scripts from file://, where a packaged widget runs, and with the no-modules
# bindgen target that loader is wrong anyway (no default export). index.html
# carries its own classic loader, so the injected one is removed here.
strip_module_loader() {
  python3 - "$here/dist/index.html" <<'PY'
import re, sys
path = sys.argv[1]
html = open(path, encoding="utf-8").read()
# Anchored on "import init" so only trunk's loader matches: a looser pattern
# once started inside a comment that mentioned a module script, ate its "-->"
# and blanked the page.
html = re.sub(r'<script type="module">\s*import init\b.*?</script>\s*', "", html, flags=re.S)
html = re.sub(r'<link rel="(?:modulepreload|preload)"[^>]*>', "", html)
if 'type="module"' in html:
    sys.exit("index.html still contains a module script")
open(path, "w", encoding="utf-8").write(html)
PY
}

# The Wasm the app ships must load on Chromium 85 (2022 sets, Tizen 6.5): no
# reference-types (Chrome 96), and no multivalue to stay clear of ABI changes.
# What M85 does have — sign-ext, bulk-memory, saturating float-to-int,
# mutable globals — stays on, because std otherwise pulls in slow polyfills.
# std must be rebuilt with the same features (it ships compiled for stable's
# defaults), hence the pinned nightly and build-std. Host builds (proc-macros,
# `cargo test`) are untouched: the flags are scoped to the wasm32 target.
wasm_env='export RUSTUP_TOOLCHAIN=$STREAMHUB_NIGHTLY CARGO_UNSTABLE_BUILD_STD=std,panic_abort CARGO_TARGET_WASM32_UNKNOWN_UNKNOWN_RUSTFLAGS="-C target-cpu=mvp -C target-feature=+sign-ext,+bulk-memory,+nontrapping-fptoint,+mutable-globals";'

case "${1:-release}" in
  test)    run "cargo test -p streamhub-core" ;;
  check)   run "$wasm_env cargo check -p streamhub-tizen --target wasm32-unknown-unknown" ;;
  dev)     run "$wasm_env cd app && trunk build --dist ../dist" && strip_module_loader ;;
  release) run "$wasm_env cd app && trunk build --release --dist ../dist" && strip_module_loader ;;
  *) echo "unknown: $1"; exit 2 ;;
esac
