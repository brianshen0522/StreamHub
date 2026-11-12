# `:tv` architecture — the foundation screens build on

The Android TV app is a 1:1 copy of the Tizen client (`tizen/`). `PARITY.md`
says *what* each screen must look and behave like; this file says *how* to
build it on the foundation in this module. Read both. Where `PARITY.md` and the
Tizen source disagree, the source wins.

```
com.streamhub.tv
├── MainActivity        keys → engine; lifecycle → sounds; setContent { TvApp }
├── TvApp.kt            TvShell (canvas + focus root + locals + exit prompt + toast) and TvApp (routing)
├── ui/TvCanvas.kt      the fixed 960×540 dp canvas
├── ui/theme/           Tokens.kt (colour, shape, space, motion, shadow, z), Type.kt (TvType, TvText), Paint.kt (CSS gradients, tvShadow)
├── ui/components/      TvButton, TvTextField, TvModal, TvExitPrompt, TvToastHost, TvScreenEnter,
│                       TvPosterCard, TvSkeletonCard, TvRail, TvWarnBadge, TvCardChip, TvProgressBar
├── focus/              the focus engine (port of focus.rs): Keys, FocusMath (pure), Nodes, Focusable, TvFocusEngine, TvFocusRoot
├── nav/TvNav.kt        the stack (port of nav.rs) and the routes
├── sfx/                SfxRules.kt (pure port of sfx.js), TvSfx.kt (AudioTrack mixer)
├── ui/sample/          SampleHome — a Home-shaped screen of primitives; the screenshot subject
├── screens/Placeholders.kt   stand-in Login/Home/Search/Detail/Player — replace these
├── auth/LoginViewModel.kt, home/HomeViewModel.kt, cast/, AppContainer.kt   kept data code
```

The previous screens were deleted; read them with
`git show 4f07fae:android/tv/src/main/kotlin/com/streamhub/tv/<path>` if useful
(the old player has working ExoPlayer/HLS wiring).

---

## 1. Canvas and units

Everything lays out on a fixed **960 × 540 dp** canvas (`TvCanvas`), scaled to
the display by overriding `LocalDensity` (`density = min(w/960, h/540)`,
`fontScale = 1`). 720p, 1080p and 4K look identical; system font size has no
effect. Non-16:9 is letterboxed in `--bg`. The canvas clips.

**Units rule (PARITY §1):** CSS px / 2 = dp, and CSS font px / 2 = sp. `em` and
unitless line heights carry over as `em`. Durations and easings are unchanged.
So `padding: 26px 96px 28px` is `13.dp / 48.dp / 14.dp`, `font-size: 28px` is
`14.sp`.

Never read the real display size for layout. Never use `LocalConfiguration`
dimensions. The video surface is the one thing that fills the physical screen
— put the `PlayerView` *outside* `TvCanvas` (behind it) if you need that; the
chrome stays on the canvas.

## 2. Tokens (`ui/theme`)

Named after the CSS variables:

| CSS | Kotlin |
|---|---|
| `--bg --surface --surface-2 --text --text-2 --text-3 --accent --focus --on-focus --green --amber --danger` | `TvColors.Bg, Surface, Surface2, Text, Text2, Text3, Accent, Focus, OnFocus, Green, Amber, Danger` |
| literal colours (`#15110a`, danger button, chip bg, 免廣告 …) | `TvColors.OnAmber, DangerButtonBg, DangerButtonText, ChipBg, AdFreeText, …` |
| `rgba(8,9,12,a)` | `TvColors.bg(a)` |
| `--r-card --r-panel`, pill | `TvShapes.RCard/Card, RPanel/Panel, Pill` |
| `--safe-x --safe-y --rail-gap --row-gap` | `TvSpace.SafeX, SafeY, RailGap, RowGap, RowPaddingBottom` |
| `--ease --ease-in-out --t-fast --t --t-slow` | `TvMotion.Ease, EaseInOut, TFast, T, TSlow`; `CssEaseInOut` (blobs) |
| `transition: … var(--t) var(--ease)` | `TvMotion.focus()`, `fast()`, `slow()`, `tween(ms, easing, delay)` |
| `@keyframes` | `TvMotion.keyframes(p, easing, 0f to a, .45f to b, 1f to c)` (easing per interval, as CSS); `bump(p)`, `pop(p)`, `sheenX(p)` |
| box shadows | `TvShadows.Button/Card/Modal` + `Modifier.tvShadow(spec, radius) { alpha }` |
| `linear-gradient(160deg, …)` | `cssLinearGradient(160f, 0f to c1, 1f to c2)` (CSS angle semantics); `TvGradients.CardFallback, Sheen, Shimmer, BackdropMask, HomeScrim*, DetailScrim*` |
| type scale | `TvType.Display, Title, RowHeading, Body, Meta, Caption, Button, Input, CardTitle, …`; weight 650 → `TvType.W650` (600) |

Text: always `TvText(text, TvType.X, color = …)`. It merges the font family
(`LocalTvFontFamily`), CSS-like line boxes (centred, no font padding), one line
+ ellipsis by default. `fontFeatureSettings = TvType.TabularNums` for `tv-num`.

**Performance rules (DESIGN.md):** animate only `graphicsLayer` scale,
translation and alpha. Never animate size, padding, shadow or blur. Shadows are
pre-rendered bitmaps whose alpha animates (`tvShadow` does that; one bitmap is
shared by every element of the same size). A rail or column retargets
mid-flight (`Animatable.animateTo`), never queues.

## 3. The focus engine (`focus/`)

One engine owns every key. `MainActivity.dispatchKeyEvent` hands each
`KeyEvent` (down and up) to `TvFocusEngine.onKeyEvent`. Compose's focus
traversal is **not** used. The engine moves a single "focused" state between
registered elements with Tizen's geometry and scoring (`FocusScoring`), measured
on the elements *as drawn* (transforms included), in canvas dp.

### 3.1 Making something focusable

```kotlin
val t = rememberTvFocusTarget(
    focusId = "card:${item.id}",     // data-focus-id — route memory restores by it; keep it stable across reloads
    action = "play",                // data-action — what selectors match
    group = null,                   // data-focus-group on the element (a TvRailTrack's group covers its cards)
    autofocus = false,              // data-autofocus
    longPress = false,              // data-longpress (OK held 700 ms → TvIntent.LongPress)
    playable = false,               // data-playable (Play key → TvIntent.Play)
    favable = false,                // data-favable (red key → TvIntent.Favorite)
    selected = false,               // is-selected (current season/episode: entry lands here the first time)
    disabled = false,               // aria-disabled — never focusable
    onClick = { intent -> … },      // OK / Play / LongPress / Favorite
)
Box(
    Modifier
        .zIndex(if (t.isFocused) 2f else 0f)        // focused above its neighbours
        .tvFocusable(t, TvFocusTransform.Card)      // the element's own focus transform
        .size(…)                                    // everything after this is scaled with it
        .background(if (t.isFocused) TvColors.Focus else TvColors.Surface2)
) { … }
```

- `t.isFocused` is snapshot state: read it to style (`is-focused`). Colour
  changes that CSS does not transition must not animate either.
- `TvFocusTransform(scale, lift, durationMs, origin, bumpKeepsScale)` is the
  element's `.is-focused` transform; presets `Button` (1.06, 140 ms), `Card`
  (1.08, −3 dp lift, origin 50%/45%, 240 ms), `Episode` (1.08, −2 dp), `None`.
  The engine adds the edge bump (6 dp at 45 % of 160 ms) *before* it, as the CSS
  composes `translate · --fx`. Use `bumpKeepsScale = false` for the player pills.
- **Modifier order matters.** `tvFocusable` measures the element after its own
  transform, so put it where the element's box starts; anything placed before
  it (outer padding, `zIndex`) is outside the element.
- Ready-made: `TvButton`, `TvTextField`, `TvPosterCard` already do all of this.
  `TvButton(target = t)` / `TvPosterCard(target = t)` accept your own target.

**Everything focusable must be laid out at its natural size.** An element with
zero size is unusable (Tizen's `is_usable`), so: no `LazyRow`/`LazyColumn` for
rails or rows (Tizen renders every card; Search pages 30 at a time and grows
ahead of focus), and columns that overflow are measured unbounded
(`tvRowColumn` does this) inside a clipping parent.

### 3.2 Containers

| Tizen | Compose |
|---|---|
| `.tv-rail > .tv-track[data-rail-track][data-focus-group=g]` | `TvRailTrack(group = "g", state, start = 48.dp, contentPadding, gap) { cards }` — full width, moved by translateX so the focused card sits at `start`, clamped at the end; first reveal snaps, later ones animate 240 ms `--ease` |
| `[data-row-column][data-row-anchor=N]` | `Modifier.tvRowColumn(rememberTvColumnState(), anchor = (N/2).dp)` on the column; moves by translateY so the focused row's top sits on the anchor (rows above never move down). `state.hasFocus` = `has-focus` |
| `[data-row]` | `Modifier.tvRow(rememberTvRowState(key))`; read `row.isCurrent` / `row.isPast` for opacity |
| `[data-focus-scope]` (modal) | `Modifier.tvFocusScope()` on the panel. `TvModal` does it |
| `[data-focus-group]` on a non-track container | `Modifier.tvFocusGroup("name")` |
| `[data-loading]` | `Modifier.tvLoading()` while a screen's first load is in flight (the passive watchdog then parks focus nowhere) |
| `.tv-skeleton` | `Modifier.tvSkeleton()` (`TvSkeletonCard` has it). Down at an edge buffers while any is placed |
| a class used in a selector (`.tv-rows`, `.tv-results`, `[data-menu]`) | `Modifier.tvRegion("rows")` |

`TvRail(title, group, row, note, caption, captionIsError, alpha, rowPaddingBottom, trackPadding) { … }`
is the whole `Rail` component (heading + track, collapsed when `caption` is set).
Pass `alpha` for Detail's opacity rule (`homeRowAlpha` is Home/Search's).

### 3.3 The engine API (focus.rs → Kotlin)

Get it with `LocalTvFocus.current`.

| focus.rs | `TvFocusEngine` |
|---|---|
| `focus_element(el)` | `focus(target)` |
| `focus_selector(sel)` | `focusFirst(selector): Boolean` |
| `focus_later(sel)` | `focusLater(selector)` (after the change composes and lays out — two frames) |
| `restore()` | `restore(): Boolean` |
| `focused()` | `focused: TvFocusTarget?` |
| `focused_within(sel)` | `focusedWithin(selector)` |
| `back_to(sel)` | `backTo(selector): Boolean` |
| `ensure_valid()` | `ensureValid()` (also runs every 200 ms) |
| `intent()` | `intent` (or just use the `onClick` parameter) |
| `key_count()` | `keyCount` |
| `exit_prompt()` | `exitPromptOpen`, `openExitPrompt()`, `closeExitPrompt()` |
| `exit_app()` | `exitApp()` |
| `set_screen_handler` | `TvScreenKeys { press -> … }` (composable) |
| Timeout(0) after a render | `afterLayout { … }` |

Selectors (`TvSelect`): `id("x")`, `action("play")`, `autofocus`, `group("g")`,
`inRegion("rows")`, `inRow(rowState)`, `textField`, `any`, combined with `and`.
Examples: `.tv-rows [data-focusable]` → `TvSelect.inRegion("rows")`;
`input[name=q]` → `TvSelect.action("q")`;
`.tv-results .tv-rows > section:first-of-type` → `TvSelect.inRegion("results") and TvSelect.inRow(firstRowState)`.
`TvFocusTarget` also answers `regions()`, `inRegion(name)`, `row()`, `track()`, `inScope()`.

### 3.4 Screen key handlers

```kotlin
TvScreenKeys(onKeyUp = { key, code -> … }) { press ->   // press.key, press.repeat, press.keyCode
    when (press.key) {
        TvKey.Back -> engine.focusedWithin(TvSelect.inRegion("results")) && engine.backTo(TvSelect.action("q"))
        else -> false
    }
}
```

- The handler gets **every keydown first** (Back included, through the Back
  chain), except while the exit prompt is open. Return true to consume. A
  consumed arrow plays `move` (held → the quieter repeat), a consumed non-repeat
  OK plays `select` — exactly where focus.rs does.
- `onKeyUp` gets every keyup (the player's scrub end and OK tap/long-press).
- **One slot per engine**, like Tizen: install one `TvScreenKeys` per screen and
  branch inside it for the screen's modals. A second one replaces the first.
- Keys: `TvKey.Up/Down/Left/Right/Enter/Back/PlayPause/Play/Pause/Stop/Rewind/FastForward/Previous/Next/ChannelUp/ChannelDown/PageUp/PageDown/Red/Other` (map in `TvKeyMap`, PARITY §5). Back repeats are swallowed.

### 3.5 What the engine does by itself

Dispatch order, pacing, bump, buffering, Back chain and `ensure_valid` are
ported line by line (PARITY §4.6-4.12): Left/Right repeats every 160 ms, 80 ms
after 0.4 s, 3 steps after 1.5 s; Up/Down 220 ms; extra repeats dropped; CH/Page
keys a row per keydown. Bumps de-dupe globally for 300 ms; a held key thuds
once per wall. Directions pressed while nothing is focusable (and Down at an
edge while skeletons are up) are buffered (≤ 3, ≤ 2 s) and replayed. OK clicks
on keydown (long-press elements decide on keyup or at 700 ms). Route memory is
keyed `"{depth}|{route.memoryKey}"`, recorded on focus and on OK, not inside
modals, pruned to the current depth.

### 3.6 Text fields

`TvTextField(value, onValueChange, onSubmit, label, placeholder, focusId, action, autofocus, password, keyboardType, imeAction)`.
When the engine focuses it, it also takes real Compose focus (the IME opens).
Left/Right move the caret and leave only from the ends; Up/Down leave;
Backspace edits; OK and the IME's Search/Done call `onSubmit`; keys are ignored
while the IME is composing. Anything else focused takes Compose focus back to
the root, which closes the IME.

## 4. Navigation and the shell

`TvNav` (`LocalTvNav.current`): `push(route)`, `replace(route)`, `back()`,
`reset()`, `current`, `depth`, `stack`. Routes: `TvRoute.Home`, `Search`,
`Detail(DetailTarget)`, `Player(PlayTarget)` — the `nav.rs` fields.

`TvApp` shows Login when signed out, else **only the top entry**, keyed on the
entry's id. Returning to a screen re-mounts it: reload its data, then focus
returns through route memory (the watchdog calls `restore()`; call
`engine.restore()` yourself once data has rendered, as Home and Detail do).
Search keeps its query/results across Detail in something that outlives the
composable (a ViewModel scoped above the entry, or an object), as Tizen keeps
them in globals.

Back: screen handler → close the exit prompt → `nav.back()` → open the exit
prompt (focus 取消). The exit prompt (`TvExitPrompt`) and toasts
(`LocalTvToast.current.show("…")`, 2.2 s) are mounted by `TvShell` above every
screen. Every screen except the player is wrapped in `TvScreenEnter` (fade +
1.015 → 1 over 420 ms) by `TvApp`.

**To land a screen:** write it under `screens/<name>/`, then swap the
placeholder call in `TvApp.kt` for it. Delete its placeholder.

## 5. Sounds (`sfx/`)

`TvSfx` is a faithful port of `sfx.js`: the four sounds synthesised at the
device rate with the same formula; MASTER .5; per-sound gain, priority and gap;
held moves 120 ms apart at 60 %; one voice, a cut fades over 8 ms; a lower
priority is dropped while a higher one sounds; silent while the player is up
(the OK that opened it finishes its pop) and while the activity is stopped.
On/off persists in SharedPreferences `streamhub` / key `sfx` (`"0"` = off,
missing = on).

The engine already plays move / edge / select / back where focus.rs does.
Screens only need `LocalTvSfx.current`:

- the 音效 toggle: `val on = sfx.setEnabled(!sfx.enabled); if (on) sfx.play(Sound.Select)`;
- nothing else — `TvApp` calls `setPlayer(true/false)` when the top route is a Player.

## 6. Tests and screenshots

```bash
cd android
./build-env/gradle.sh :tv:testDebugUnitTest                                   # everything
./build-env/gradle.sh :tv:testDebugUnitTest --tests '*SampleScreenshotTest*'  # screenshots only
```

PNGs are written on every run to **`android/tv/build/outputs/roborazzi/`**
(`captureRoboImage("build/outputs/roborazzi/<name>.png")`; the system property
`roborazzi.test.record=true` is set in `build.gradle.kts`).

- Pure JVM tests: `focus/FocusScoringTest`, `PacingTest`, `KeyMapTest`, `sfx/SfxRulesTest`.
- Robolectric: `focus/FocusEngineTest` runs at **sdk 24 and 35**
  (`@Config(sdk = [24, 35], qualifiers = "w960dp-h540dp-land-xhdpi")` = a
  1920×1080 display). `SampleScreenshotTest` runs at sdk 35 with
  `@GraphicsMode(NATIVE)` — native graphics needs API 26+.
- Fonts: Robolectric has no CJK font. Wrap screenshot content in
  `CompositionLocalProvider(LocalTvFontFamily provides notoSansCjk())`
  (`src/test/.../TestSupport.kt`, reads `fonts-noto-cjk` from the build image).
- Infinite animations (skeleton shimmer) never let the test clock idle: set
  `compose.mainClock.autoAdvance = false` and drive it with `advanceTimeBy`.
- Inject the clock: `TvFocusEngine(nav, sounds, clock = { compose.mainClock.currentTime })`.
  `RecordingSounds` records what the engine played.
- Drive keys with `engine.keyDown(KeyEvent.KEYCODE_…, repeat)` / `engine.keyUp(code)`.
- `src/test/resources/robolectric.properties` turns Robolectric's Conscrypt off
  (the app's `conscrypt-android` has no JVM JNI).

## 7. Known differences from Tizen in the foundation

- Weight 650 renders as 600 (no system font has 650).
- "Neither track nor column" reveals use Compose `bringIntoView`, which animates
  inside a scroll container; Tizen scrolls instantly.
- After `nav.back()` the engine runs `ensureValid` once the new screen is laid
  out instead of waiting up to 200 ms for the watchdog. Same result, sooner.
- The Exit key (Tizen 10182) has no Android equivalent; HOME belongs to the system.
