# StreamHub for Android

Two apps from one Gradle project: a phone client and an Android TV client that
share a data layer and almost nothing else. They are the same platform with
different input models, so they are two UI modules rather than two projects.

Read [`shared/api/README.md`](../shared/api/README.md) before writing any
networking code — it holds the API behaviour that every client has to get right.
Two things belong in the HTTP client from the first commit: pin the base URL to
**`/api/v1`**, and send **`X-StreamHub-Client: android`** (or `tv`) on every
request, which is what makes the server refuse admin accounts at the login
screen rather than later on every content screen.

---

## Module layout

| Module | Type | Role |
|---|---|---|
| `:core` | library | API client, token storage and refresh, models, resume rules |
| `:mobile` | application | Phone UI — Material 3 with dynamic color |
| `:tv` | application | Android TV UI — Compose for TV, built around D-pad focus |

### What `:core` holds now

`StreamHubApi` covers auth, search, item detail, episodes, the NDJSON source
stream, manifest and poster URLs, and the library routes. Three parts are worth
knowing before changing anything:

- **`TokenAuthenticator` serialises refreshes.** The server rotates the refresh
  token on every use, so two concurrent refreshes destroy the session. A caller
  that finds the stored token already changed while it waited reuses it instead
  of refreshing again. There is a test that fails if that guard is removed.
- **`ItemDetail` is a sealed type parsed by inspection.** `/api/item` answers a
  different shape per provider with no type field; which key is present —
  `seasons`, `episodes` or `streams` — is the discriminator.
- **`SessionStore` is an interface** so the encrypted implementation can be
  swapped and so tests can run on the JVM. Note that
  `EncryptedSharedPreferences` is deprecated as of security-crypto 1.1.0 with no
  drop-in replacement; it is still the battle-tested option, and replacing it
  with an AES/GCM store over the Android keystore is a one-file change.
- **`RealtimeClient.events()` is a self-healing flow.** Collecting it opens the
  socket, cancelling closes it, and it reconnects on its own. A 4002 close means
  the token behind the handshake lapsed, so it renews *through the shared
  `TokenRefresher`* before reconnecting — reconnecting with the same dead token
  loops, and renewing through a second lock would rotate the token away from an
  HTTP request doing the same thing.
- **`ResumeRules` is a port, not a design.** Which episode resumes, when a season
  rolls over and what counts as finished already exist in the web player; the
  two must agree. One difference is deliberate and marked in the source: a
  finished episode restarts from zero here rather than 30 seconds from the end.

```bash
./gradlew :core:testDebugUnitTest
```

Two application modules means two APKs with two application IDs, which can be
installed side by side. Everything that is not a screen belongs in `:core`; if
`:mobile` and `:tv` both need it, it is not UI.

## Building

```bash
cd android
./gradlew assembleDebug          # both APKs
./gradlew :mobile:installDebug   # phone, over adb
./gradlew :tv:installDebug       # TV, over adb
```

Open the `android/` directory in Android Studio and it will import as-is.

`local.properties` is required and deliberately not committed, because it
hard-codes one machine's SDK path. A fresh clone needs:

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > android/local.properties
```

There is no JDK on the `PATH` on this machine — Android Studio's bundled one is
the toolchain. To build from a terminal:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

Toolchain: AGP 9.3.1, Kotlin 2.4.10, Gradle 9.7.1, compileSdk 37, minSdk 26.
Note that **AGP 9 has built-in Kotlin support** — applying the
`org.jetbrains.kotlin.android` plugin on top of it fails the build rather than
being merely redundant. The Compose compiler plugin is still applied separately.

The Gradle wrapper is committed, which is what keeps the build reproducible.

## Signing

Generate one release keystore and keep using it. **Android refuses to install an
update signed with a different key than the build already on the device**, so
rotating the key means uninstalling first, which wipes the saved session — on the
TV that is genuinely painful.

The keystore and `keystore.properties` are git-ignored. Back the keystore up
somewhere durable outside this repository; there is no store to recover it from.

## Design language

The phone app is stock Material 3 in Compose with **dynamic color** enabled, so it
derives its palette from the user's wallpaper the way first-party Google apps do.
Support predictive back and use the M3 motion defaults.

The TV app follows the ten-foot conventions instead — a full-bleed hero backdrop
above horizontal rails, *Continue watching* first. Focus is the whole interaction
model: the focused card scales with a bright border, neighbours dim, and focus
position is restored when returning to a rail. Keep content inside overscan-safe
margins, use a dark theme always, and never make a gesture the only way to reach
something.

Two TV-specific consequences of the API worth planning for. The source list runs
past twenty entries and cannot be shown on a TV — pick the best checked source
automatically and put "change source" behind the player menu. And text entry with
a remote is bad enough that voice search is the primary input, not an extra.

## Playback

Media3 / ExoPlayer on both. Hand the player `/api/manifest?target=<source url>`
rather than the raw source: the browser's `pLoader` ad filter has no ExoPlayer
equivalent, so that endpoint is what keeps native playback ad-free. Segments come
back as absolute CDN URLs and never touch the server.

Set the auth header as a **default request property** on the data source, not
just on the first request — a master playlist sends the player back to
`/api/manifest` for the variant it picks.
