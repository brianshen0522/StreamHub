# StreamHub for Android

Two apps from one Gradle project: a phone client and an Android TV client that
share a data layer and almost nothing else. They are the same platform with
different input models, so they are two UI modules rather than two projects.

Read [`shared/api/README.md`](../shared/api/README.md) before writing any
networking code — it holds the API behaviour that every client has to get right.

---

## Module layout

| Module | Type | Role |
|---|---|---|
| `:core` | library | API client, token storage and refresh, models, local database, resume rules |
| `:mobile` | application | Phone UI — Material 3 with dynamic color |
| `:tv` | application | Android TV UI — Compose for TV, built around D-pad focus |

Two application modules means two APKs with two application IDs, which can be
installed side by side. Everything that is not a screen belongs in `:core`; if
`:mobile` and `:tv` both need it, it is not UI.

## Creating the project

There is no system JDK and no Gradle CLI on this machine — Android Studio's
bundled JBR is the toolchain, so the project has to come from the New Project
wizard rather than being hand-written. Create it **into this directory** so the
ignore rules and the Docker build context exclusions already in place apply from
the first commit.

Installed SDK: platform 37, build-tools 36.0.0.

Commit the Gradle wrapper (`gradlew`, `gradle/wrapper/`) — it is how the build
stays reproducible. Do not commit `local.properties`; it hard-codes this
machine's SDK path.

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

Media3 / ExoPlayer on both. The server is expected to serve a cleaned manifest
with ads already removed, because the browser's `pLoader` filter has no ExoPlayer
equivalent — until that endpoint exists, a native client plays the ads the web
player hides.
