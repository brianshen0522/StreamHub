# `:tv` — handoff

For a second session building the Android TV app in parallel with the phone app.
Everything here was verified against the working tree, not remembered.

---

## 1. Where the code actually is

```
main                          52f96e5  Merge pull request #1 from brianshen0522/worktree-client-repo-scaffold
worktree-client-repo-scaffold          (6 commits ahead of main)
```

`main` **already contains** `android/`, the `:core` module, and the `:tv`
skeleton — they went in through PR #1. What is still outstanding on
`worktree-client-repo-scaffold`:

```
feat(tv):      give the TV module its foundations              <- §3, and this file
feat(devices): show and revoke the account's signed-in sessions <- adds sid/clientKind, see §7
fix(dramasq):  say when a title has no episodes rather than blaming the parser
fix(mobile):   make full screen actually full screen
feat(mobile):  a real player, source filtering, and several fixes
fix(mobile):   make the app look like StreamHub
```

**Branch from `worktree-client-repo-scaffold`**, whose tip adds the `:tv`
foundations described in §3. Everything below it is `:mobile`, `server/`, and
provider work — you want the `devices` commit in particular, because that is
what puts `DeviceSession`, `sid`, and `clientKind` into `:core` and the server,
and §7 depends on them.

```bash
git worktree add .claude/worktrees/tv-app -b tv-app worktree-client-repo-scaffold
```

If you would rather start from `main`, cherry-pick that one commit
(`feat(tv): give the TV module its foundations`) instead — starting from `main`
without it means re-doing §3 by hand.

## 2. Who owns what — read this before editing anything

Another session is working the phone app on this same branch **right now**, and
lands commits into `:core`, `:mobile`, and `server/` without warning. Its most
recent one added `clientKind` to the session row, a `sid` claim in the access
token, `GET`/`DELETE /api/me/sessions`, and `DeviceSession` in `:core`. Expect
more. Rebase rather than merge, and expect `:core` to move under you.

| Path | Owner | You may |
| --- | --- | --- |
| `android/tv/**` | **you** | anything |
| `android/core/**` | phone session | read, depend on — **do not edit** |
| `android/mobile/**` | phone session | read for reference — do not edit |
| `server/**` | phone session | read — do not edit |
| `android/gradle/libs.versions.toml` | shared | add entries, don't change versions |

If you need something from `:core` that isn't there yet, **write it locally in
`:tv` first** and note it here. Two branches editing the same `:core` file is the
one thing that will cost real time to untangle. `ui/Color.kt` in this module is
already a deliberate copy of the phone's palette for exactly that reason —
collapse the two into `:core` once both branches have landed, not before.

## 3. What is already in place

Done and building (`:tv:assembleDebug` passes):

- **`build.gradle.kts`** — `buildConfig = true`, with `BuildConfig.SERVER_URL`
  and `BuildConfig.GIT_SHA`. Override the server for local work:
  `-Pstreamhub.serverUrl=http://10.0.2.2:58787`
- **`network_security_config.xml`** — `main` refuses cleartext, `debug` allows it,
  wired into the manifest. Without this the app cannot reach a local server at
  all and the failure looks like a network outage.
- **`ui/Color.kt` / `ui/Theme.kt`** — the brand palette from
  `frontend/src/styles.css`, and `StreamHubTvTheme`. No dynamic colour: the phone
  app shipped with it once and the user's verdict was 「整個app的theme都不對」.
- **`res/values/themes.xml`, `colors.xml`** — window background is the `--bg`
  token, so there is no flash before the first frame.
- **`MainActivity.kt`** — a placeholder screen using the theme. Replace it.

Still a skeleton: no sign-in, no data, no navigation, no player.

## 4. What `:core` gives you

`com.streamhub.core.net.StreamHubApi(serverUrl, sessionStore, ClientKind.TV)` —
pass `ClientKind.TV`, not `PHONE`; the server records it and it is what makes a
television identifiable in the account's device list.

Auth: `login`, `logout`, `me`, `renewSession`, `heartbeat`, `sessions`,
`revokeSession`. Content: `search`, `item`, `episodes`, `sources`,
`checkSources`, `manifestUrl`, `adCuts`, `posterUrl`. Library: `favorites`,
`addFavorite`, `removeFavorite`, `history`, `continueWatching`, `progress`,
`putProgress`, `deleteProgress`, `sourcePreference`, `rememberSourcePreference`.

Also `EncryptedSessionStore`, `RealtimeClient`, `ResumeRules`, and
`api.sessionEnded` (a `SharedFlow<Unit>` that fires when the refresh token is
gone and the UI must return to sign-in).

Copy `android/mobile/src/main/kotlin/com/streamhub/mobile/AppContainer.kt`
verbatim into `:tv` and change `ClientKind.PHONE` to `ClientKind.TV`. It is
thirty lines of hand-wiring with no framework, and the `shareIn` on
`realtimeEvents` is load-bearing — see §5.

## 5. Traps the phone app already paid for

Every one of these was a real debugging session. Do not rediscover them.

1. **`kotlin-android` must not be applied.** AGP 9 has built-in Kotlin support;
   applying the plugin on top *fails the build*. Only
   `alias(libs.plugins.kotlin.compose)` goes alongside the application plugin.
2. **ExoPlayer needs an explicit HLS MIME type.** `/api/v1/manifest?target=…`
   has no `.m3u8` extension, so without
   `.setMimeType(MimeTypes.APPLICATION_M3U8)` the player picks the progressive
   source and dies with `UnrecognizedInputFormatException`.
3. **Token refresh must be single-flight.** Two concurrent refreshes rotate the
   opaque token twice and kill the session. `:core` already shares one
   `TokenRefresher` between the HTTP 401 path and the realtime 4002 close — use
   it, don't add a second one.
4. **One realtime socket per app, not per screen.** Each `events()` collection
   opens its own connection. Three ViewModels each collecting directly meant
   three sockets. Hence `shareIn(WhileSubscribed)` in `AppContainer`.
5. **Never send the server address or its API path to the screen.** The user was
   explicit: 「user 不應該可以從前端看到任何server 的資訊」. Show
   `BuildConfig.GIT_SHA` if you need a build identifier — the placeholder
   `MainActivity` shows the pattern.
6. **`/api/sources` and `/api/check-sources` are NDJSON.** Parse line by line as
   it streams; buffering the whole body defeats the point of the endpoint.
7. **XML comments cannot contain `--`.** This broke `colors.xml` when it tried to
   name the CSS variables it mirrors.
8. **The username field must use `KeyboardType.Email`.**
   `KeyboardCapitalization.None` alone is not enough — the keyboard still
   capitalised `brian` to `Brian` and the server matches usernames exactly. On TV
   this matters more, not less: the leanback IME is worse.

## 6. What to build, in order

1. **`AppContainer` + sign-in.** A TV sign-in screen is its own design problem —
   typing a password on a D-pad is miserable. Simplest honest version first: a
   plain form. Consider a phone-assisted code flow later, but that needs a server
   route that does not exist yet, and the server belongs to the other session.
2. **Home.** Continue-watching rail first — it is the screen a television is
   actually turned on for. `api.continueWatching()` already returns one row per
   title, not per episode.
3. **Search.** Assume the on-screen keyboard is the worst input on any platform
   here; voice input via the leanback search fragment is the conventional answer.
4. **Detail + source list.** Sources with detected ads sort **first** and are
   labelled in `--green`, not red — red reads as an error to the user.
5. **Player.** Media3 with a D-pad control overlay. The phone's
   `player/PlayerScreen.kt` has the ad-cut handling and progress reporting worth
   copying; its gesture layer is not.

TV-specific things the phone app has no answer for: every interactive element
needs a visible focus state (the theme puts the accent on `border` for this),
`Modifier.focusRestorer()` on rails, overscan-safe padding (~5%, 48dp × 27dp at
1080p), and nothing may depend on touch — the manifest already declares
`android.hardware.touchscreen` as not required.

## 7. Known-live design question: phone → TV control

The user wants the phone to drive the television, Spotify-Connect style,
including waking a closed TV app. Not implemented, but the analysis is done:

- The `sid` claim and `clientKind` (already on this branch, see §1) are what make
  "send this to *that* device" addressable at all. A TV that signs in with
  `ClientKind.TV` is therefore already visible and targetable from the phone's
  device list — that half is done.
- The realtime server currently **ignores every client frame after auth** and
  broadcasts by `userId` only. Directed frames need a protocol change on the
  server — coordinate, don't unilaterally add it.
- **Waking a closed app**: FCM needs Firebase and Play services; Google Cast
  needs a registered receiver ID. For a sideloaded app on a mains-powered TV,
  a boot-started foreground service holding the socket is the realistic path and
  has no policy problem, because this app is never going near a store.

## 8. Build and run

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # no JDK on PATH
cd android
./gradlew :tv:assembleDebug
./gradlew :tv:installDebug -Pstreamhub.serverUrl=http://10.0.2.2:58787
```

There is a `Television_1080p` AVD. Two emulators on the same AVD interfere —
both go offline — so if the phone session has one running, use a different AVD
or wait. Stale `*.lock` files under `~/.android/avd/<name>.avd/` cause the same
symptom after a crash; delete them.

Toolchain: AGP 9.3.1, Kotlin 2.4.10, Compose BOM 2026.08.00, Media3 1.11.0,
tv-material 1.1.0, compileSdk 37, minSdk 26.

## 9. Repo rules

- Never push to `main`, never force-push, never merge. Open a PR; the user merges.
- Never commit signing material: `*.jks`, `*.keystore`, `keystore.properties`,
  `local.properties`, `*.mobileprovision`, `*.p12`, `*.cer`.
- Commit with explicit paths (`git add android/tv`), not `-a` — the working tree
  may hold another session's in-flight work.
