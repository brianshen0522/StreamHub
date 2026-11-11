# Toolchain for the Tizen client. The build host has no C linker, and Leptos'
# proc-macros compile for the host, so everything builds in here.
FROM rust:1-bookworm
RUN rustup target add wasm32-unknown-unknown \
 && curl -sSL https://github.com/trunk-rs/trunk/releases/download/v0.21.14/trunk-x86_64-unknown-linux-gnu.tar.gz | tar xz -C /usr/local/bin
WORKDIR /src/tizen
