# StreamHub for Samsung Tizen

A StreamHub client for Samsung TVs and Smart Monitors: Rust + Leptos compiled
to WebAssembly, packaged as a Tizen TV web app (`.wgt`). Target device: Smart
Monitor M7 **S32FM703UC** (2025, Tizen 9, Chromium M120), driven by its
minimal remote. It speaks the same `/api/v1` contract as the Android and iOS
clients ([`shared/api/README.md`](../shared/api/README.md)) and sends
`X-StreamHub-Client: tv`, so administrator accounts are refused at sign-in.

## Commands

All builds run in Docker. The host has no C linker, and Leptos' proc-macros
compile for the host.

| What | Command |
|---|---|
| Core unit tests (resume rules, NDJSON) | `./build.sh test` |
| Type-check the wasm app | `./build.sh check` |
| Debug build → `dist/` | `./build.sh dev` |
| Release build → `dist/` | `./build.sh release` |
| Signed package → `StreamHub.wgt` | `./package.sh` |
| Install + launch on a TV | `./install.sh <tv-ip>` |
| Launch with Web Inspector on :9222 | `./install.sh <tv-ip> debug` |
| QA specs (Playwright) | `qa/run.sh` |

`STREAMHUB_API_BASE=https://…` (build time) compiles in a different server. At
run time `localStorage["streamhub.apiBase"]` overrides it; QA uses that.

### Packaging and signing

`package.sh` runs `./build.sh release`, stages `dist/` + `package/config.xml` +
`package/icon.png` in `target/wgt-stage/`, then runs `tizen package -t wgt -s
<profile>` inside the `streamhub-tizen-cli` image (`build/Dockerfile.tizen`,
Tizen Studio 6.1 web CLI on Ubuntu 22.04 + OpenJDK 17).

Certificates, passwords and `profiles.xml` live in **`tizen/.sign/`**
(gitignored). The container sees `.sign/tizen-studio-data` as
`~/tizen-studio-data`.

* With no settings, a **throwaway author certificate** and the profile
  `streamhub-dev` are created on first run. Tizen's public distributor
  certificate signs alongside it. This proves the pipeline works, but **a retail
  Samsung set will not install it**: Samsung TVs require a Samsung distributor
  certificate whose DUID list includes the set.
* To use a different certificate, change one variable:
  ```bash
  STREAMHUB_SIGN_PROFILE=samsung \
  STREAMHUB_AUTHOR_P12=.sign/samsung/author.p12 \
  STREAMHUB_AUTHOR_PASSWORD_FILE=.sign/samsung/author.password \
  STREAMHUB_DISTRIBUTOR_P12=.sign/samsung/distributor.p12 \
  STREAMHUB_DISTRIBUTOR_PASSWORD_FILE=.sign/samsung/distributor.password \
  ./package.sh
  ```
  This (re)creates the `samsung` profile from those files and signs with it.
  After that, `STREAMHUB_SIGN_PROFILE=samsung ./package.sh` is enough.
* `SKIP_BUILD=1` reuses `dist/`. `STAGE_ONLY=1` stops after staging (QA uses it).

Package id `StrmHubTV1` (Tizen requires exactly 10 alphanumerics), application id
`StrmHubTV1.StreamHub`. Do not change them: the set would treat the result as a
different app, and the saved session would be lost.

## Architecture

```
core/   streamhub-core — pure Rust, plain `cargo test`
          resume.rs   ResumeRules (port of android/core; tests ported 1:1)
          ndjson.rs   incremental NDJSON for /api/sources
          models.rs   wire models
app/    streamhub-tizen — Leptos CSR → wasm32
          main.rs, app.rs   boot, panic hook → console, root router (Login vs stack)
          nav.rs            navigation stack; Back pops, Home+Back → exit prompt
          focus.rs          spatial navigation ([data-focusable], [data-focus-group], .is-focused)
          keys.rs           Tizen key codes → Key
          api.rs, session.rs, config.rs   fetch layer, single-flight refresh, localStorage session
          realtime.rs       /api/realtime socket, 4002 → refresh, cast receiver
          screens/          login (credentials + pairing code/QR), home rails, search, detail, exit
          player/           player screen, avplay.rs (bindings), ladder.rs (recovery), media.rs
        js/ (classic scripts, loaded before the wasm)
          boot.js           file:// fetch → XHR fallback for the .wasm
          tizen-keys.js     registers media keys via tizen.tvinputdevice
          player-shim.js    window.StreamHubPlayer: one interface, three backends
          sfx.js            window.StreamHubSfx: interface sounds (see below)
          vendor/hls.min.js
package/  config.xml, icon.png
qa/       Playwright specs against dist/ + the live server
```

Player backends, chosen on each `open()` in `player-shim.js`:

| Backend | When | Notes |
|---|---|---|
| `avplay` | `webapis.avplay` exists (a real set) | Hardware decode. Plays `/api/manifest?target=…`, so ads are cut and segments come straight from the CDN |
| `html5` | desktop browser | `<video>` + hls.js |
| `mock` | `localStorage["streamhub.mockPlayer"]="1"` | Fake clock, no media, so headless QA can drive the player. Knobs: `streamhub.mockPlayerFail`, `mockDuration`, `mockRate` |

The page is built with `public_url = "./"`, no file hashes, no ES modules and
no SRI. A widget loads from `file://…/res/wgt/`, and Chromium refuses module
scripts from `file:`. `config.xml` sets an explicit CSP with
`'wasm-unsafe-eval'`, because Tizen's default policy would block compiling the
wasm.

## Interface sounds

`js/sfx.js` synthesises four short sounds into AudioBuffers (no files): a
tick when focus moves, a pop on OK, a falling pop on Back, a low thud at an
edge. `focus.rs` calls them through `sfx.rs`; all the rules live in the
script:

- **One voice.** A new sound fades the last one out (8 ms) instead of
  layering on it. OK/Back outrank an edge, which outranks a move: a lower
  sound arriving while a higher one still sounds is dropped.
- **Spacing.** The same sound inside its gap (move 70 ms, 120 ms while held
  and quieter; edge 260 ms; OK/Back 140 ms) is dropped, never queued. A held
  key against an edge thuds once, not once per bump.
- **Silent in the player.** `set_body_playing` tells the script; the
  AudioContext is suspended for as long as the player is up.
- **Never throws**, and a suspended or missing AudioContext means silence.
- The 音效 開/關 button on Home's top bar toggles it,
  `localStorage["streamhub.sfx"]="0"` when off.

## Remote key map

| Key | keyCode | Action |
|---|---|---|
| ↑ ↓ ← → | 38 40 37 39 | move focus. Player: ←/→ seek, ↑/↓ show controls |
| OK / Enter | 13 | activate (a `click` on the focused element) |
| Back | 10009 | player: hide controls, then leave. Elsewhere: pop. On Home: exit prompt (default focus 取消) |
| Play/Pause | 10252 | toggle (the M7's minimal remote has only this media key) |
| Play / Pause / Stop / ⏪ / ⏩ | 415 / 19 / 413 / 412 / 417 | registered when the remote has them |
| Previous / Next | 10232 / 10233 | previous / next episode |
| Exit | 10182 | leave the app immediately |

Arrows, Enter and Back arrive without registration. Media keys are registered
one by one in `tizen-keys.js`. Long-press Back belongs to the platform. On a
desktop, Escape/Backspace act as Back and Space as Play/Pause.

## QA (`qa/`)

`qa/run.sh` runs `./build.sh dev`, stages the widget (`STAGE_ONLY=1
./package.sh`), serves `dist/` on `http://localhost:5190` and runs the specs
at 1920×1080. Every page gets `streamhub.apiBase=http://localhost:8080` and
`streamhub.mockPlayer=1` before the app loads. Playwright 1.65.0 (Chromium
1254) runs in `mcr.microsoft.com/playwright:v1.65.0-noble`, because this host
lacks Chromium's shared libraries. Set `QA_NATIVE=1` on a host that has them.

* Credentials: `STREAMHUB_QA_USER` / `STREAMHUB_QA_PASSWORD`, or an env file at
  `STREAMHUB_QA_ENV` (default `qa/.env`, gitignored). They are never printed.
* `lib/fixtures.js` provides:
  * `remote` — key events with Tizen keyCodes set through `defineProperty`
  * `focused()` — the `.is-focused` element's text, data attributes, group and screen
  * `focusTo()` — walks the D-pad; the specs never click
  * `shot()` — step screenshots into `qa/test-results/<test>/`
  * a guard that fails a test on any console error containing `panicked`, or on
    an uncaught page error
* Test hooks the UI exposes: `[data-screen=login|home|search|detail|player|exit]`,
  `[data-action=login|pair|search|play|signout|exit-cancel|exit-confirm]`,
  `[data-user-code]`, `[data-provider]` rails, and the player's `data-position`
  (seconds) and `data-controls=visible|hidden`.
* Cleanup: global setup snapshots the QA account. Teardown deletes the progress,
  favourites and sessions (seeded and paired) the run added. **Two things
  remain:** watch history and source preferences. The server has no
  user-facing delete route for either.

## Tonight: first run on the S32FM703UC

1. **Developer Mode.** On the monitor open **Apps**, and on the Apps panel type
   `1` `2` `3` `4` `5` with the remote. On a minimal remote, use the on-screen
   number pad, which opens from the Apps search or the "123" key. Switch
   Developer Mode **On**, enter this machine's **LAN IP** as Host PC IP, then
   **power-cycle** the monitor: hold the power button, or unplug it. If the
   Apps panel ignores 12345, community reports say the SmartThings app's
   virtual remote number pad works too. Check: `./install.sh <tv-ip> info` lists
   the set.
2. **Samsung certificate.** The throwaway author cert will not install on the set.
   * Install Tizen Studio on a desktop with a GUI: the IDE installer from
     <https://developer.tizen.org/development/tizen-studio/download>. In
     Package Manager → Extension SDK, install **TV Extensions** and **Samsung
     Certificate Extension**.
   * Get the DUID: `./install.sh <tv-ip> duid`. Or let Certificate Manager read
     it from the connected set via Device Manager.
   * Tools → **Certificate Manager** → `+` → **Samsung** → TV. Create a new
     author certificate (any name, password) and sign in with your Samsung
     account. Create a new distributor certificate with privilege level
     **Public**, and add the DUID.
   * There is no supported CLI route: the Samsung sign-in is an OAuth web
     page inside the IDE, which is also why `tizen4docker` ships webkitgtk.
     The files appear in `~/SamsungCertificate/<profile>/`: `author.p12` and
     `distributor.p12`. The passwords are the ones you typed.
   * Copy both `.p12` files into `tizen/.sign/samsung/`. Put each password in
     `author.password` / `distributor.password` there (not `.pwd`: the CLI
     owns that name) (no trailing newline is needed;
     `chmod 600`). Then run the `STREAMHUB_SIGN_PROFILE=samsung …
     ./package.sh` line from *Packaging*.
3. **Install:** `./install.sh <tv-ip>`. This runs `sdb connect`, `tizen install -n
   StreamHub.wgt -s <ip>:26101` and `tizen run -p StrmHubTV1.StreamHub`. A
   signature error at install time means the certificate's DUID list does
   not include this set.
4. **Inspector:** `./install.sh <tv-ip> debug`. This runs `sdb shell 0 debug
   <appid>`, forwards the printed port to `localhost:9222`, and you open it in
   Chrome.
5. **Verify**, and write down results:
   - [ ] **Boot at all.** The page mounts from `file://`. If it is black, check
     the Inspector console for fetch/CSP/wasm errors.
   - [ ] **Engine version.** `navigator.userAgent` in the console should show
     Chrome/120. Also check the `webapis.productinfo` model/firmware line.
   - [ ] **Startup under 30 s** from launching the app to Home, timed cold
     (after power-cycling).
   - [ ] **AVPlay playback of `/api/manifest`**, including a title whose playlist
     has `#EXT-X-DISCONTINUITY`. Playback must continue across the cut ad
     break, A/V in sync, with no stall at the boundary. Then seek across a
     boundary.
   - [ ] **Remote keys:** arrows, OK, **Back**: in the player it first hides
     controls, then leaves; on Home it opens the exit prompt with focus on
     取消. Also Play/Pause, and the **Exit** key / long-press Back (should leave).
     Record any key that never reaches the app. The Inspector's console logs
     keyCodes if the UI agent left the debug log on.
   - [ ] **IME text entry:** the search field with the on-screen keyboard. Check
     Chinese input (黑暗榮耀), that Done/Enter submits, and that Back closes the
     keyboard without leaving the screen.
   - [ ] **Pairing:** the code is readable from the sofa, and a phone approves it.
   - [ ] **30-minute soak:** play one episode for 30 min. Watch memory in the
     Inspector (Performance monitor → JS heap, DOM nodes). It should be
     flat, not climbing. Progress should reach the server (check the web app's
     Continue shelf).
   - [ ] Resume after relaunch lands at the saved position minus 30 s.
