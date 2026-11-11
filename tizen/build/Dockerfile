# Toolchain for the Tizen client. The build host has no C linker, and Leptos'
# proc-macros compile for the host, so everything builds in here.
#
# The oldest target is a 2022 Smart Monitor (LS32BM703UC: Tizen 6.5,
# Chromium 85). Stable Rust's wasm32 output uses reference-types (Chrome 96+)
# and its precompiled std was built with it, so the app is built with a pinned
# nightly that rebuilds std for a narrower feature set (see build.sh).
FROM rust:1-bookworm
ARG NIGHTLY=nightly-2026-10-20
RUN rustup target add wasm32-unknown-unknown \
 && rustup toolchain install "$NIGHTLY" --profile minimal --component rust-src --target wasm32-unknown-unknown \
 && curl -sSL https://github.com/trunk-rs/trunk/releases/download/v0.21.14/trunk-x86_64-unknown-linux-gnu.tar.gz | tar xz -C /usr/local/bin
ENV STREAMHUB_NIGHTLY=$NIGHTLY
WORKDIR /src/tizen
