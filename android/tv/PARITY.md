# Android TV ⇄ Tizen parity spec

The reference for rewriting `android/tv` (Kotlin + Compose) as a 1:1 copy of the
Samsung Tizen client in `tizen/`. **The Tizen source is the authority.** Where
this document and the Tizen code disagree, the code wins. Report the
disagreement so this file can be fixed.

Every claim cites `file:line`. Paths are relative to the repository root.
`styles.css` means `tizen/app/assets/styles.css` and `player.css` means
`tizen/app/assets/player.css`. Rust paths are under `tizen/app/src/` unless
written in full.

Scope: replace `android/tv` and keep `android/core`. Section 9 lists what
`:core` already gives you and what it is missing.

---

## 1. Units and canvas

Tizen lays everything out on a fixed **1920 × 1080 CSS-px** page
(`tizen/app/index.html:5`, `styles.css:48-74`, `.app` 1920×1080). On a 4K panel
the web layer is still 1920×1080 (`player.css:17-19`).

**Conversion rule.** Android TV at 1080p is xhdpi (density 2.0). So:

- **1 CSS px = 0.5 dp.** Every length in this document is given as `Npx → M dp`, with M = N/2.
- **Font size px / 2 = sp.** For example, 28px body text is 14 sp.
- `em` values (letter-spacing, line-height) carry over as Compose `em` unchanged.
- Unitless `line-height` such as `1.45` becomes `lineHeight = 1.45.em`.
- Durations and easings are unchanged.
- Fixed-px constants inside the Rust and JS logic are CSS px. Convert them the
  same way: the 96 px rail start is 48 dp, the 284 px strip tile pitch is 142 dp.
- Ratios (scale factors, percentages, opacity) are unchanged.

**Fixed canvas.** Android must lay out against a **fixed 960 × 540 dp canvas**,
scaled to the actual display, so that a 720p box, a 1080p box and a 4K box look
identical (Tizen gets this for free from `width=1920` in the viewport).

1. At the root of `setContent`, compute `scale = min(widthPx / 960f, heightPx / 540f)`.
2. Provide `LocalDensity provides Density(density = scale, fontScale = 1f)`.
3. Put all UI inside a `Box(Modifier.requiredSize(960.dp, 540.dp))`, centred, with letterboxing in `#08090c` if the aspect ratio is not 16:9.

`fontScale` is pinned to 1. A system "large text" setting must not reflow the
layout, because Tizen has no such setting. This is a deliberate parity choice;
flag it if accessibility review objects.

All focus-engine geometry (section 4) is computed in **canvas units**.
Recommended: dp in the 960×540 space. The engine has one absolute constant,
`SLACK = 2px`, which is **1 dp**. Everything else in the scoring is linear, so
uniform scaling does not change which candidate wins.

The video plane: Tizen's AVPlay draws on a hardware plane *under* a transparent
page (`player-shim.js:91-103`, `player.css:58-64`). On Android, put the ExoPlayer
`SurfaceView`/`PlayerView` under the Compose player chrome, full-screen, black
behind, with **letterbox / fit** resize mode (`player-shim.js:124`
`PLAYER_DISPLAY_MODE_LETTER_BOX`). The video fills the *physical* screen. The
chrome sits on the 960×540 canvas.

No pointer: Tizen hides the cursor (`tizen/app/assets/base.css:6-8`) and
disables the pointing device (`tizen/package/config.xml:57`). The Android app
is D-pad only, with no touch or mouse affordances.

---

## 2. Design tokens

Source: `styles.css:11-46` (`:root`), `player.css:22-38` (`.vp-root`, which
mirrors the root tokens and adds safe-area copies). The design intent is
`tizen/DESIGN.md`.

### 2.1 Colour

| Token | Value | Use |
|---|---|---|
| `--bg` | `#08090c` | page, scrim base (`styles.css:12`) |
| `--surface` | `#15171c` | cards, card art, skeletons (`:13`) |
| `--surface-2` | `#1f222a` | buttons at rest, modals, raised (`:14`) |
| `--text` | `#f5f5f7` | primary text (`:15`) |
| `--text-2` | `rgba(245,245,247,.68)` | secondary (`:16`) |
| `--text-3` | `rgba(245,245,247,.44)` | tertiary / captions (`:17`) |
| `--accent` | `#e5383b` | brand, progress fills, primary button **at rest** (`:18`). Never a focus colour |
| `--focus` | `#ffffff` | every focus state (`:19`) |
| `--on-focus` | `#0b0c0f` | text on white focus fill (`:20`) |
| `--green` | `#46d369` | "下一集" chip, episode check (`:21`) |
| `--amber` | `#f5a623` | warning badge, 已下架 title (`:22`) |
| `--danger` | `#ff8a80` | error text (`:23`) |
| `--scrim` | (defined, unused by rules; see backdrop scrims) | `:24-25` |
| `--tv-bg` | `var(--bg)` | html/body bg (`:45`) |

Other literal colours, each listed where it is used:
- `#15110a`: text on the amber badge (`:211`).
- `rgba(229,56,59,.18)` with text `#ffb3ae`: the danger button (`:171`).
- `rgba(8,9,12,.82)`: card chip background (`:221`).
- `#23262f → #15171c`: card fallback gradient and up-next card gradient.
- `rgba(84,98,220,.18)`: login blob B (`:780`).
- `#4ade80` on `rgba(46,204,113,.16)`: the 免廣告 badge. When focused it is
  `#15803d` on `rgba(22,163,74,.16)` (`player.css:530-550`).
- `#1a8f3c`: the episode check on a focused tile (`styles.css:753`).
- `#333`: QR wait text (`:856`).

### 2.2 Type

- Font stack: `"SamsungOneUI", "TizenSans", "Noto Sans CJK TC", "Noto Sans TC", "PingFang TC", sans-serif` (`styles.css:27`, `player.css:38`). On Android use the system sans (Noto Sans CJK on Android TV) via `FontFamily.Default`. Bundling Noto Sans TC is acceptable if the box lacks CJK weights.
- Mono stack: `"SamsungOneUI Mono", "Roboto Mono", "DejaVu Sans Mono", "Noto Sans Mono CJK TC", monospace` (`styles.css:28`). Use `FontFamily.Monospace`. It is used only for the pairing code.
- Body: 28px/1.45 → **14 sp / 1.45em**, weight 400 (`styles.css:63-64`).
- `font-variant-numeric: tabular-nums` (class `.tv-num`, `styles.css:109`, and many player rules) → `fontFeatureSettings = "tnum"`.
- CSS weight 650 (`styles.css:155, 380`; `player.css:316`) has no system equivalent. Use `FontWeight(650)` if the font is variable, otherwise **600**. This is an ambiguity; see the report.
- Type scale (DESIGN.md "Type"): Display 72/1.05 w800 → 36 sp; Title 44/1.15 w700 → 22 sp; Row heading 32/1.2 w650 → 16 sp; Body 28/1.45 → 14 sp; Meta 24/1.3 w500 → 12 sp; Caption 20 → 10 sp. Nothing is below 20px (10 sp).

### 2.3 Shape, space, safe area

| Token | px | dp | Source |
|---|---|---|---|
| `--safe-x` (`--vp-safe-x`) | 96 | 48 | `styles.css:30`, `player.css:36` |
| `--safe-y` (`--vp-safe-y`) | 64 | 32 | `styles.css:31`, `player.css:37` |
| `--r-card` | 14 | 7 | `:32` |
| `--r-panel` | 20 | 10 | `:33` |
| pill radius | 999 | fully rounded (`RoundedCornerShape(50)`) | `:151` |
| `--rail-gap` | 28 | 14 | `:34` |
| `--row-gap` | 56 | 28 | `:35` (used as `calc(56-28)=28px` row padding) |

### 2.4 Motion

| Token | Value | Compose |
|---|---|---|
| `--ease` | `cubic-bezier(.2,.8,.2,1)` | `CubicBezierEasing(0.2f,0.8f,0.2f,1f)` (`styles.css:37`) |
| `--ease-in-out` (`--vp-ease-io`) | `cubic-bezier(.45,0,.2,1)` | `CubicBezierEasing(0.45f,0f,0.2f,1f)` (`:38`) |
| `--t-fast` (`--vp-t-fast`) | 140 ms | press / button scale (`:39`) |
| `--t` (`--vp-t`) | 240 ms | focus, controls (`:40`) |
| `--t-slow` (`--vp-t-slow`) | 420 ms | backdrop cross-fade, screen enter, modals (`:41`) |
| CSS `ease-in-out` (blobs only) | `cubic-bezier(.42,0,.58,1)` | `styles.css:775,781` |
| `linear` | — | shimmer, countdown fill, ring, scrub fill |

**Performance rules (DESIGN.md "Performance rules"):**
- Animate only transform and opacity, which in Compose means `graphicsLayer` scale, translation and alpha.
- Never animate size, padding, shadow or blur.
- Shadows are pre-drawn layers whose **alpha** animates.
- A rail move that starts while another is in flight retargets from the current value. It never queues. Use `Animatable.animateTo`, which retargets.

### 2.5 Z-order (paint order, back to front)

- **Home and Detail** (`styles.css:290, 304, 463, 484`):
  1. backdrop (`.tv-backdrop`)
  2. hero, z1
  3. rows
  4. top bar, z3
  5. focused card / button, z2 inside its rail
  6. inside a card art: art → fallback → image → badge/chip/progress (z2) → sheen (z3) → ring (z4)
- **Modals:** backdrop at z100 (`:909`), toast at z120 (`:965`). The exit prompt is rendered after every screen (`app.rs:54`), so it paints above a screen's own modals.
- **Search:** the bar is z2 (`:555`).
- **Player:** the video plane sits at z0 (`player-shim.js:99`) and `.vp-root` at z1 (`player.css:45`). Inside the root, in DOM order (`player/mod.rs:1660-1888`):
  1. driver badge
  2. flash glyph
  3. chrome
  4. episode strip
  5. up-next card
  6. source picker modal
  7. error modal (topmost)

---

## 3. Screens

Root routing (`app.rs:21-57`):
- Signed out → **Login**.
- Signed in → the top of the nav stack: Home | Search | Detail | Player (`nav.rs:56-61`).
- The stack starts as `[Home]` (`nav.rs:66`).
- A signed-in → signed-out *transition* resets the stack to `[Home]` (`app.rs:37-43`).
- The exit prompt is always mounted over everything (`app.rs:54`).

**Only the top route is mounted.** Returning to a screen (Back from Detail to
Home, Back from Player to Detail) **re-mounts it and re-runs its loads**. Home
refetches all three rails and Detail refetches item/progress/favourites, then
restores focus from route memory (section 4.3). Search keeps its query and
results in globals for as long as Search is in the stack (`search.rs:6-8, 43-52,
340-346`). Android must reproduce the observable result: fresh data and the same
focused element on return.

**Screen enter animation** (all `.tv-screen`, `styles.css:78-87`): opacity 0→1
and scale 1.015→1 over 420 ms `--ease`, both. Leaving is instant. The Player
root is not a `.tv-screen` and has no enter animation (`player/mod.rs:1640`).

### 3.0 Shared components

**Pill button `.tv-btn`** (`styles.css:142-182`):
- Size: height 72px → **36 dp**, padding 0 40px → 0 20 dp. Content is centred with a gap of 14px → 7 dp.
- Text: 28px → 14 sp, w650, `nowrap`.
- At rest: background `--surface-2`, text `--text`.
- Variants:
  - `primary`: background `--accent`.
  - `danger`: background `rgba(229,56,59,.18)`, text `#ffb3ae`.
  - `wide`: full width.
  - `chip`: height 60px → 30 dp, padding 0 30px → 15 dp, text 26px → 13 sp.
  - `text`: transparent background, `--text-2`, height 60px → 30 dp, text 26px → 13 sp, no shadow (`:901-902`).
- **Focused:** background white, text `#0b0c0f`, **scale 1.06** (transform transition 140 ms `--ease`).
- Focused shadow: a drop shadow `0 12px 32px rgba(0,0,0,.55)` → offset y 6 dp, blur 16 dp. It is a pre-drawn layer whose alpha goes 0→1 over 240 ms `--ease` (`:160-169, 179`).
- Disabled (`aria-disabled="true"`): opacity .5 (`:182`), and **not focusable** (`focus.rs:198-203`).
- Icons are 30px → 15 dp, stroke-only, `stroke-width 2.4` (in 24-unit viewBox), round caps/joins (`:114-123`). `.tv-icon-fill` is a filled variant (`:124`).

**Text field `.tv-input`** (`styles.css:186-198`):
- Label: 24px → 12 sp, w500, `--text-2`, padding-left 28px → 14 dp, gap 10px → 5 dp above the field.
- Field: height 76px → 38 dp, padding 0 32px → 16 dp, text 30px → 15 sp, pill shape.
- At rest: border 2px → 1 dp `rgba(255,255,255,.12)`, background `rgba(21,23,28,.9)`. Placeholder in `--text-3`.
- Focused: border white, background `--surface-2`. No scale. No transition.

**Badge `.tv-badge-warn`** (`:202-211`):
- Height 34px → 17 dp, padding 0 14px → 7 dp, pill shape.
- Text 20px → 10 sp, w700.
- Background amber, text `#15110a`.
- Text: "已無集數" when `unavailableReason=="no-episodes"`, otherwise "已下架", shown only when `isUnavailable` is true (`screens/common.rs:68-76`).

**Card chip `.tv-chip`** (`:212-225`):
- Placed absolutely at top 10px / right 10px → 5 dp.
- Height 32px → 16 dp, padding 0 12px → 6 dp, pill shape.
- Background `rgba(8,9,12,.82)`, text green, 20px → 10 sp, w700.

**Plain chip separator** (`:226-227`): consecutive `.tv-chip-plain` get a "·"
with 14px → 7 dp margin each side, in `--text-3`, and `--text-2` text.

**Poster card `PosterCard`** (`screens/common.rs:96-167`, CSS `styles.css:408-517`):
```
Card (100 × 150 dp art + labels; flex 0 0 100 dp; transformOrigin (50%, 45%))
├─ shadow layer   left/right 4 dp, top 12 dp, height 142 dp, radius 7 dp,
│                 shadow 0 13dp blur 25dp rgba(0,0,0,.75), alpha 0 → 1 focused (240 ms)
├─ Art 100 × 150 dp, radius 7 dp, clip, bg --surface
│  ├─ fallback: whole box, gradient 160° #23262f→#15171c, the title's first
│  │            character centred, 84px→42 sp w800 rgba(245,245,247,.16)
│  ├─ image: cover, full box (only when posterUrl non-blank); opacity .4 if unavailable
│  ├─ badge (top 5 / left 5 dp) when unavailable
│  ├─ chip (top 5 / right 5 dp) "下一集" for next-up continue rows
│  ├─ progress (only when p > 0): left/right/bottom 6 dp, height 3 dp, radius 1.5 dp,
│  │            track rgba(255,255,255,.28), fill --accent, width = clamp(p, 2, 100) %
│  ├─ sheen (z3): see animation
│  └─ ring (z4): inset 1.5 dp (3px) white stroke, radius 7 dp, alpha 0 → 1 focused (240 ms)
├─ title: margin-top 7 dp, 12 sp, lh 1.3, w600, --text-2 → --text when focused, 1 line ellipsis
└─ subtitle: 10 sp, lh 1.4, --text-3 → --text-2 when focused, 1 line ellipsis, min-height 1.4em
```
- **Focused transform:** `scale(1.08) translateY(-6px)` → scale 1.08 and translationY −3 dp. Transition 240 ms `--ease`. z above neighbours (`:463`).
- **Sheen** (`:451-470`):
  - A strip 60% of the art width and 120% of its height (top −10%, bottom −10%).
  - Gradient at 100°: white 0 → `rgba(255,255,255,.22)` at 48% → 0.
  - Skewed −14°. At rest it sits at translateX −170% of its own width, so it is off-card.
  - On focus it plays **once**: translateX −170% → +260%, 650 ms `--ease`, 60 ms delay.
  - It restarts each time the card gains focus.
- Subtitle: the card's subtitle, or, when that is empty and `provider` is set, the provider's display name (reactive; `common.rs:155-163`).
- Focus id `data-focus-id = card.id`. Attributes `data-playable` (if `on_play`), `data-longpress` (if `on_long`) and `data-favable` (if `on_favorite`) are set (`common.rs:119-127`). The click dispatches on `focus::intent()`: Play → `on_play` (else open); LongPress → `on_long` (else open); Favorite → `on_favorite`; Open → open (`common.rs:128-137`).

**Rail `Rail`** (`common.rs:175-199`, CSS `styles.css:363-404`):
```
Row (data-row) — opacity .72 at rest, 1 when current, 0 when past (Home/Search only); 240 ms --ease
├─ heading: margin 0 48 dp, 16 sp lh 1.2 w650, row baseline-aligned, gap 7 dp
│  ├─ title text
│  ├─ note (count): 11 sp w500 --text-3 tnum
│  └─ caption (collapsed rows only): 12 sp w500 --text-3; error variant --danger
└─ rail (full width) → track (data-rail-track, data-focus-group=<group>)
       Row layout, top-aligned, gap 14 dp, width = max(content, 100%),
       padding 13 dp top / 48 dp sides / 14 dp bottom (26/96/28px),
       translateX animated 240 ms --ease
```
- Row padding-bottom is 28px → 14 dp by default (`:370`). Overrides: Home rows 6px → 3 dp (`:389`); Home tracks have padding-top 22px → 11 dp and bottom 18px → 9 dp (`:390`).
- Collapsed rows (`caption` given): no track. Padding-bottom 24px → 12 dp (Search; on Home the Home override of 3 dp wins by source order, `:388-389`). Title 28px → 14 sp, `--text-2` (`:391`).

**Skeletons** (`common.rs:203-213`, `styles.css:520-542`):
- Non-focusable placeholder cards with the same card box, art background `--surface`, and two lines:
  - 10 dp high (20px), 78% wide, margin-top 8 dp (16px), radius 3 dp;
  - then 46% wide with margin-top 5 dp (10px).
- **Shimmer:** a full-size overlay in the art with a 90° gradient (0 → `rgba(255,255,255,.16)` at 50% → 0) at alpha .5. It translates −100% → +100% of its own width, 1.2 s **linear**, infinite.
- Counts: 8 per loading rail (`home.rs:211`, `search.rs:229`); the Detail episode rail uses 7 tile skeletons (`detail.rs:675`).
- Their presence makes the screen "loading" for key buffering (`focus.rs:726-728`).

**Heart** (`common.rs:217-223`, `styles.css:125-138`):
- 15 dp outline heart, stroke 2.2 (24-unit viewBox). Path: `M12 20.5s-7.5-4.6-9.6-9.2C1 8.1 3 4.5 6.6 4.5c2.1 0 3.6 1.2 5.4 3.2 1.8-2 3.3-3.2 5.4-3.2 3.6 0 5.6 3.6 4.2 6.8-2.1 4.6-9.6 9.2-9.6 9.2z`.
- Filled: fill and stroke `--accent`, with a pop over 240 ms `--ease`: scale .6 → 1.2 (at 60%) → 1.
- On an `is-active` button the stroke is `--accent`.

**Toast** (`common.rs:231-253`, `styles.css:961-982`):
- One at a time. A new toast replaces the old one and restarts the timer. Auto-hides **2200 ms** after the last `toast()`.
- Layout: fixed, horizontally centred, bottom 32 dp, z120, max width 600 dp (1200px), padding 9/18 dp, pill shape.
- Style: background `--surface-2`, border 0.5 dp `rgba(255,255,255,.08)`, 13 sp w600, one line with ellipsis.
- Enter: opacity 0 → 1 and translateY 8 dp → 0, 240 ms `--ease`. No exit animation.

**Modal** (`styles.css:906-941`):
- Backdrop: full screen `rgba(0,0,0,.6)`, fades in 240 ms `--ease`, z100.
- Panel:
  - width 860px → 430 dp, padding 32/36/30 dp (64/72/60px), radius 10 dp;
  - background `--surface-2`, border 0.5 dp `rgba(255,255,255,.06)`, text centred;
  - shadow `0 40px 90px rgba(0,0,0,.6)` → 0 20 dp blur 45 dp (static);
  - **rises:** opacity 0 → 1 and translateY 12 dp → 0, 420 ms `--ease`.
- Title 22 sp lh1.15 w700. Text margin-top 8 dp, 13 sp `--text-2`. Actions margin-top 24 dp, centred row, gap 12 dp. Buttons min-width 120 dp.
- `.tv-menu` variant: width 380 dp, title 20 sp on one line with ellipsis, actions margin-top 20 dp (`:984-991`).
- Every modal panel is a **focus scope** (section 4.4).

**Backdrop** (`home.rs:316-375`, CSS `styles.css:231-277`):
- Two layers, each holding:
  - a full-canvas "wash" image, cover, alpha .16;
  - the poster right-aligned at full height, **360 × 540 dp** (720×1080px), cover, alpha .92 (.7 on Detail).
- The poster has a static left-edge mask: a horizontal alpha gradient 0 at 0% → .6 at 22% → 1 at 45%. Implement it with `drawWithContent` and a `BlendMode.DstIn` gradient.
- Layers cross-fade alpha over 420 ms `--ease-in-out`.
- **Home scrim**, three gradients stacked over the layers:
  1. top→bottom `rgba(8,9,12,.7)` → 0 over the first 110 dp (220px);
  2. left→right `--bg` 0% → `rgba(8,9,12,.92)` 40% → `.55` 62% → `.1` 76% → 0 86%;
  3. bottom→top `--bg` 0% → `.85` 30% → 0 62%.
- **Detail scrim:**
  1. left→right `--bg` 0% → `--bg` 30% → `.9` 48% → `.35` 68% → `.05` 84%;
  2. bottom→top `--bg` 0% → `.9` 34% → 0 70%.
- A layer with no image is invisible (`:262-263`).

### 3.1 Login (`screens/login.rs`, CSS `styles.css:760-902`)

Tree (data hook `data-screen="login"`):
```
Screen (bg --bg, column, centred)
├─ blobs (non-interactive, clipped)
│  ├─ A: 600 dp circle, top −260 dp, left −180 dp, radial(closest-side) rgba(229,56,59,.2)→0,
│  │     drifts translate (0,0)→(160 dp, 90 dp), 26 s CSS-ease-in-out, infinite, alternate
│  └─ B: 600 dp circle, bottom −280 dp, right −160 dp, radial rgba(84,98,220,.18)→0,
│        drifts (0,0)→(−140 dp, −100 dp), 34 s, infinite, alternate          (styles.css:763-790)
├─ brand "StreamHub": absolute top 32 dp, full width, centred, 20 sp w800 ls −0.25 sp --accent,
│     preceded by the logo mark (tizen/app/img/logo.svg) 1.15em square, margin-right .3em,
│     raised .12em                                                     (styles.css:96-107, 793-803)
└─ stage (one mode at a time): margin-top 20 dp, column, centred,
       enters with opacity 0→1 and translateY 8 dp→0, 240 ms --ease, each time the mode switches (:804-815)
```

**Pair mode** (default, `login.rs:186, 272-349`):
```
pair panel (data-focusable, data-action="pair", focus-id "login-pair")
  550 × 260 dp, padding 0 32 dp (right) 0 30 dp (left), row, centred vertically, gap 30 dp,
  radius 14 dp, bg = radial(ellipse at top-left) rgba(255,255,255,.07)→0 at 55%, over --surface.
  Focused: inset 1.5 dp white ring at alpha .9 (240 ms). No scale.        (styles.css:816-843)
├─ QR box 200 × 200 dp, white, radius 10 dp, content centred
│  ├─ code present: QR of `verificationUrlComplete`, 190 × 190 dp, black on white,
│  │                with the standard 4-module quiet zone              (login.rs:32-44; styles.css:845-855)
│  └─ else: wait text, 13 sp, #333, padding 12 dp, centred:
│           pair_message ?: "正在取得配對碼…"                                  (login.rs:295-305)
└─ body (column, centred vertically)
   ├─ eyebrow "用手機登入" 11 sp w700 ls .24em --accent
   ├─ title "掃描 QR 碼登入" margin 5 dp top / 9 dp bottom, 22 sp lh1.15 w700
   ├─ step "1. 用手機相機掃描左邊的 QR 碼" 13 sp lh1.6 --text-2
   ├─ step "2. 在手機上按「核准」"
   ├─ alt (margin-top 13 dp, 11 sp --text-3): "或前往 {verificationUrl without http(s)://} 輸入"
   │      or, with no code, "或在手機上輸入代碼"                                        (login.rs:312-319)
   ├─ user code (data-user-code): margin-top 3 dp, MONO 36 sp lh1.1 w700 ls .14em tnum, nowrap,
   │      min-height 1.1em; exactly the server's `userCode` text, or empty
   ├─ countdown (hidden but space kept when no code): margin-top 7 dp, row gap 8 dp
   │  ├─ track 150 × 2 dp radius 1 dp rgba(255,255,255,.12); fill --text-2, scaleX from the left
   │  │       = (expiresAt − now)/(expiresAt − issuedAt) clamped 0…1, transition 1 s linear
   │  └─ text 10 sp --text-3 tnum: "{m:ss} 後更新代碼"
   │         (m:ss of ceil(ms/1000) seconds)                             (login.rs:172-175, 324-334)
   └─ pair error (only when a code is showing AND pair_message set): .tv-error
switch button (data-autofocus, data-action="use-password", focus-id "login-use-password"):
  "改用帳號密碼登入", margin-top 20 dp
```

**Password mode** (`login.rs:351-412`):
```
card (form): 320 dp wide, padding 26/32/18 dp, column gap 11 dp, radius 14 dp, same bg as the panel
├─ title "帳號密碼登入" 22 sp lh1.15 w700, margin-bottom 3 dp
├─ field label "帳號" + input name=username (data-autofocus, focus-id "login-username"), text, no autocorrect
├─ field label "密碼" + input name=password (focus-id "login-password"), password
├─ primary wide button (data-action="login"): "登入", or "登入中…" while busy; margin-top 6 dp
├─ error line (.tv-error: 12 sp --danger, min-height 1.3em, centred): the error or empty
└─ text button (data-action="use-pairing"): "改用手機登入"
```

Behaviour:
- **Pairing keeps polling in both modes.** A phone approval signs in even while
  someone is typing (`login.rs:196-204`). It stops only when the screen unmounts.
- Pairing loop (`login.rs:73-161`):
  1. `POST /auth/device/start`. On failure: clear the code, set the message "正在等待伺服器…", retry after **5000 ms** (`RETRY_MS`, `:20`).
  2. Empty `deviceCode`: the message "無法取得配對碼", retry after 5 s.
  3. Otherwise show `userCode`, the scheme-less `verificationUrl` and a QR of `verificationUrlComplete`. **`deviceCode` is never displayed or put in the QR.**
  4. Poll every `intervalSeconds` (default 5, min 1). Sleep in 250 ms slices so leaving or renewing takes effect quickly.
  5. At `expiresInSeconds` (default 600, min 5) after issue, measured on the **monotonic** clock, break and start a new code.
  6. A failed poll keeps the code up.
  7. `approved` → role check (`accept`, `:62-68`). Non-USER → message "管理員帳號無法在電視上觀看，請使用一般觀眾帳號。" and a new code. Otherwise store the session, which switches the root to Home.
  8. `denied` / `expired` → new code.
- OK on the pair panel requests a new code **only if none is showing**. A live code is never replaced by a press (`login.rs:280-292`).
- Countdown ticks every 1000 ms (`login.rs:188-191`).
- Mode switch (`login.rs:206-213`): clears the error. Password → focus `input[name=username]` (the IME opens). Pair → focus `[data-action=use-password]`.
- Screen key handler: **Back in Password mode → Pair mode** (consumed). Back in Pair mode is not consumed, so the global chain opens the **exit prompt over the login screen** (`login.rs:217-227`, `focus.rs:948-967`).
- Sign in (`login.rs:230-255`):
  - Ignored while busy.
  - Trimmed username or empty password → "請輸入帳號與密碼".
  - `POST /auth/login {login,password}`. Success → role check as above. Errors → `friendly_error` (section 3.7).
  - Enter / IME Done inside either field submits the form (`focus.rs:1010-1014`).
- **Initial focus:** `data-autofocus` is on the "改用帳號密碼登入" button, so the 200 ms watchdog lands there (`focus.rs:937-943`). The source comment at `login.rs:3-4` says "focus starts on the pairing panel"; the **code** puts it on the button. Copy the code. Up from the button reaches the panel.

### 3.2 Home (`screens/home.rs`, CSS `styles.css:279-391`)

Tree (`data-screen="home"`, `data-loading` until the first load answers):
```
Screen
├─ Backdrop (two cross-fading layers of the hero's poster; §3.0)
├─ top bar: absolute top 32 dp, left/right 48 dp, height 36 dp, row, centred, gap 14 dp, z3
│  │   animates translateY and alpha over 240 ms --ease.
│  │   Browsing → alpha 0 + translateY −14 dp                                   (:281-297)
│  ├─ brand "StreamHub" with the logo mark: 20 sp w800 ls −0.25 sp --accent  (:94-107)
│  ├─ user display name (displayName ?: username): 12 sp w500 --text-2       (session.rs:32-39)
│  └─ actions (margin-left auto, gap 10 dp); top-bar buttons are 32 dp tall, padding 0 16 dp,
│     13 sp, icons 14 dp
│     ├─ [primary] search icon + "搜尋"  (data-autofocus, data-action=search, focus-id home-search)
│     ├─ speaker icon + "音效 開" | "音效 關"  (data-action=sfx, focus-id home-sfx)
│     └─ "登出"  (data-action=signout, focus-id home-signout)
├─ hero: absolute left 48 dp, bottom 320 dp (its bottom edge is at y=220 dp), width 520 dp, z1;
│  │   translateY 0 → −12 dp while browsing (240 ms --ease)                    (:299-307)
│  ├─ title: 36 sp lh1.05 w800 ls −0.25 sp, max 2 lines with ellipsis
│  ├─ meta (margin-top 9 dp, row wrap, gap 8 dp, 13 sp w500):
│  │     line (--text) · provider display name (chip-plain, "·" margin-right 8 dp --text-3) · badge
│  ├─ progress (continue items with p > 0): margin-top 9 dp, 180 × 3 dp, radius 1.5 dp,
│  │     rgba(255,255,255,.2); fill --accent scaleX(clamp(p/100, .02, 1)) from the left
│  └─ hint (continue items): margin-top 7 dp, 11 sp --text-3
├─ rows viewport: absolute top 228 dp (456px) to bottom, full width, clipped
│  └─ column (data-row-column, anchor 0; translateY 240 ms --ease)
│     ├─ Rail "繼續觀看"   group home-continue
│     ├─ Rail "我的收藏"   group home-favorites
│     ├─ Rail "觀看紀錄"   group home-history
│     └─ empty line (only when all three are loaded AND empty):
│          "還沒有內容。按「搜尋」找點東西來看吧。" padding 4 dp / 48 dp, 14 sp --text-2  (:110)
├─ card menu modal (long press)
├─ Toast
└─ sign-out confirm modal
```
SVG paths (24 viewBox):
- search: circle (10.5,10.5) r6.5 plus `m20 20-4.6-4.6`;
- speaker: `M4 9v6h4l5 4V5L8 9z`;
- waves when on: `M16.5 8.5a5 5 0 0 1 0 7M19 6a8.5 8.5 0 0 1 0 12`;
- cross when off: `m17 9 5 6m0-6-5 6` (`home.rs:557-586`).

**Hero content** (`home.rs:150-167, 603-628`):
- With a hero: the title; `line` = the card's subtitle; `meta` = the provider key, shown as the display name; the badge; progress; hint.
- Hint text: "按 OK 播放下一集" if `nextUp`, else "按 OK 繼續播放". Continue rail only.
- With no hero: title "歡迎回來", line "選一部繼續看，或按「搜尋」找新片。".
- **Animation:** the hero subtree is rebuilt on *every* hero change (every card focus). Each rebuild replays:
  - title: opacity 0 → 1 and translateY 6 dp → 0, 240 ms `--ease`;
  - meta: the same, with a 40 ms delay;
  - hint and progress: opacity only, 80 ms delay (`styles.css:88-92, 317, 327, 335, 344`).
- A card's focus sets the hero (`home.rs:243`).
- The initial hero is the first continue row, else the first favourite (`home.rs:418-427`).

**Backdrop timing** (`home.rs:305-343`):
- 200 ms after the last hero change, swap to the new poster.
- A newer change cancels the pending swap.
- If the front layer already shows that URL, do nothing.
- Otherwise load the image into the back layer and flip the front **when it has loaded**, then cross-fade 420 ms.
- An empty URL clears the back layer and flips at once, fading to `--bg`.
- Never more than two images.

**Cards per rail:**
- Continue (`home.rs:73-94`):
  - id `cw:{id}`, or `cw:{providerKey}:{itemUrl}:{seasonUrl}:{episodeLabel}` when there is no id (`:60-71`).
  - Subtitle: if `nextUp`, "{ep} 已看完 · 接著看下一集"; else the non-empty parts of [ep, "剩 {ceil(remaining/60)} 分鐘"] joined by " · ", where remaining = duration − position and the minutes part appears only when > 0.
  - Progress: `progressPercent`, none if `nextUp`. Chip "下一集" if `nextUp`.
- Favourite (`:96-114`): id `fav:…`. Subtitle: non-empty [season_parts(seasonLabel).main, ep caption] joined by " · ". When empty, the subtitle falls back to the provider display name.
- History (`:116-130`):
  - id `his:…`. Subtitle: [ep caption, `watchedAt` first 10 chars with `-`→`/`] joined by " · ".
  - Progress = position/duration·100 if duration > 0.
  - Rows are de-duplicated by `provider|item|season|episode`, keeping the first, and capped at **40** (`:28, 134-148`).
- `{ep}` = `episode_caption(label)`: bare digits become 「第N集」 with the padding kept; anything else is shown as given (`tizen/core/src/labels.rs:8-15`).
- Rail note = the row count. A loading rail shows 8 skeletons. A failed rail is a collapsed row with the friendly error, in `is-error` red. An empty rail is not rendered at all (`home.rs:208-216`).

**Card actions** (`home.rs:226-257`):
- OK → push Detail(target), where `from_history` is true only for History rows (`:46-58`).
- Play key (`Intent::Play`) → `request_autoplay(provider,url)`, then push Detail. Detail replaces itself with the Player once it is ready (§3.4).
- **Long press OK** (Continue and Favourites only; History has **no** long press, so its OK clicks on key-*down*):
  - Opens the menu; focus goes to the first menu button (`[data-menu-first]`).
  - Menu: title = the row title. Continue: "從清單移除" (data-action `menu-remove`). Favourites: "取消收藏" (`menu-unfavorite`). Both have "查看詳情" (`menu-details`) (`:639-698`).
- Red key (`Intent::Favorite`):
  - Favourites rail → remove that one row (`DELETE /me/favorites/{id}`), toast "已取消收藏：{title}", reload.
  - Other rails → toggle favourite for the title (`common.rs:270-307`): if any favourite rows exist for (provider, itemUrl), delete them all and toast "已取消收藏：{title}"; else `POST /me/favorites` and toast "已加入收藏：{title}". Then reload.
- Menu "從清單移除" → `DELETE /me/progress {scope:"title", providerKey, itemUrl, title}`, toast "已從繼續觀看移除：{title}", reload (`:281-296`).
- Any API error → toast `friendly_error`.

**Loading** (`home.rs:394-469`):
- Fetch `/me/continue-watching`, `/me/favorites` and `/me/history` **in parallel**.
- Loads are numbered. An older answer that arrives after a newer one is dropped.
- A rail is replaced only if its rows changed, so a no-op reload moves nothing.
- After the first answer: clear `data-loading`.
- If any rail failed: auto-retry after **2, 4, 8, 15, 30 s** (then 30 s forever). Any full success resets the backoff (`:307-309, 433-444`).
- Then, on the next tick:
  - if (no key was pressed since mount **or** nothing is focused) **and** route memory restores → done;
  - else, if nothing is focused → focus the **first focusable in `.tv-rows`** (`:450-466`).
  - Result: a fresh Home lands on the first card. If every rail is empty, the watchdog lands on 搜尋.
- **Realtime:** each `favorites` or `progress` frame (and each socket *re*connect) bumps `library_version`. Home waits **1000 ms of quiet** after the last bump and then reloads (`:310-311, 479-493`).

**Top bar:**
- 搜尋 → push Search.
- 音效 → toggle sfx (`localStorage "streamhub.sfx"`), update the label and icon, and when turning **on** play `select` (the OK that switched it on was silent) (`home.rs:570-577`).
- 登出 → open the confirm modal and focus `signout-cancel`.

**Sign-out confirm** (`home.rs:700-731`):
- Title "要登出這台電視嗎？", text "登出後，需要再配對一次或輸入密碼才能使用。".
- Buttons: "取消" (`data-autofocus`, `signout-cancel`) → close and focus 登出; "登出" (danger, `signout-confirm`) → `POST /auth/logout {refreshToken}`, then clear the session whatever the result (`:521-528`).

**Browsing mode** (`home.rs:386-387, 540, 545, 630`): focus inside the rows column sets `is-browsing`; focus inside the top bar clears it. Browsing hides the top bar (alpha 0, −14 dp) and raises the hero by 12 dp.

**Home key handler** (`home.rs:498-519`, Back only):
1. Menu open → close it. Focus returns to the card via `before_scope`.
2. Confirm open → close it and focus 登出.
3. Otherwise `back_to(".tv-rows [data-focusable]")`: if not already on the first card with every rail at rest, reset all rails and columns, focus the first card and consume. Otherwise do not consume, so the global chain opens the **exit prompt**.

Row behaviour on Home: past rows alpha 0, the current row 1, others .72 (`styles.css:371-375`). When focus is in the top bar the column is at rest and **all** rows are .72.

### 3.3 Search (`screens/search.rs`, CSS `styles.css:544-580`)

Tree (`data-screen="search"`):
```
Screen
├─ search bar (a form): absolute top 32 dp, left/right 48 dp, height 48 dp, row centred, gap 12 dp, z2
│  ├─ pill (fixed width 620 dp)
│  │  ├─ magnifier icon: absolute left 18 dp, vertically centred, 18 dp, --text-3;
│  │  │     --text while the field is focused
│  │  └─ input name=q (focus-id "search-input"; data-autofocus only when there are no results):
│  │        height 48 dp, padding-left 46 dp, 18 sp, placeholder "輸入片名，按 OK 搜尋",
│  │        IME action = search
│  └─ button "搜尋" (data-action="search-submit"), height 40 dp
├─ results: absolute top 100 dp to bottom, clipped
│  ├─ notice (when set): margin 4 dp top / 48 dp sides / 10 dp bottom, 13 sp --text-2
│  └─ column (data-row-column, anchor 0)
│     ├─ "最近搜尋" row (only when there are no groups AND recents is non-empty):
│     │     track group "search-recent", gap 9 dp, chip buttons (30 dp tall, 13 sp),
│     │     focus-id "recent:{q}"
│     └─ one ProviderRail per allowed provider, in /me/providers order (data-provider=<key>)
└─ Toast
```

**Search flow** (`search.rs:122-187`):
- `run_search(q)`: trim; if empty do nothing.
- Bump `SEQ`; answers for an older SEQ are dropped.
- Remember the query in recents: `localStorage "streamhub.tizen.recentSearches"`, JSON array, most recent first, de-duplicated, **max 8** (`:29-30, 92-109`).
- Notice "搜尋中…" and clear the groups.
- `GET /me/providers` (default, not `all`). Failure → the notice shows `friendly_error`.
- Keep the providers with `allowed != false` and a non-empty key. If none, the notice is "這個帳號沒有可搜尋的來源".
- Otherwise clear the notice and add one group per provider, all Loading.
- Per provider, **in parallel**: `GET /search?q=…&provider=<key>` with a **20 000 ms** deadline (`:28`).
  - `results[0].error` set → Failed(friendly_error(502, err)).
  - Otherwise Ready(`results[0].items`), in server order. **Never re-sort.**
  - Timeout → Failed("搜尋逾時").
  - HTTP error → Failed(friendly_error).
- Each group renders as soon as it answers.

**ProviderRail** (`search.rs:223-300`):
- Loading: 8 skeletons.
- Failed: a collapsed row, error caption.
- Ready and empty: a collapsed row with caption "沒有結果".
- Ready: note = the total count.
- **Paged:** shows `max(30, remembered)` cards and adds 30 more whenever a focused card index i satisfies `i + 6 >= shown` (`PAGE=30`, `PREFETCH=6`, `:33-34, 250-257, 281-285`). The grown count is remembered per provider, so Back from Detail can restore a card past the first page (`:49-60`). Appending never re-renders the focused card.
- Card: id `s:{provider}:{url}`; subtitle = non-empty [`year`, "電影" | "影集" by `mediaType`] joined by " · " (`:200-220`).
- OK → push Detail (no season, episode or history). Play key → autoplay + push Detail. Red → toggle favourite with a toast (no reload).

**Search key handler** (`search.rs:325-338`):
- Back while focus is inside `.tv-results` → `back_to("input[name=q]")`: resets every rail and column and focuses the field. If focus is already there and everything is at rest, it is not consumed, so the stack pops.
- **Up while inside the first row section** → focus the field, whatever is geometrically nearest.

**State lifetime** (`search.rs:43-86, 303-346`):
- Query, groups and notice survive a remount while Search is in the stack.
- On remount with results: no autofocus on the field; restore route focus on the next tick.
- When Search leaves the stack for good, everything resets.
- `prefill(title)` (from Detail's 已下架) resets, sets the query and runs the search on mount (`:74-78, 310-312`).

**IME:**
- The field takes real input focus so the IME opens.
- Left/Right move the caret and leave the field only from position 0 (Left) or the end (Right) with no selection.
- Up/Down leave the field.
- Backspace edits; it is not Back.
- Enter or IME Done submits.
- IME Cancel is ignored by the app; the platform closes the IME (`focus.rs:1004-1029`).
- README checklist (`tizen/README.md` "IME text entry"): Back closes the keyboard without leaving the screen.

### 3.4 Detail (`screens/detail.rs`, CSS `styles.css:582-758`)

Tree (`data-screen="detail"`):
```
Screen
├─ Backdrop (detail variant): ONE layer, always shown, the title's poster (no cross-fade), img alpha .7,
│     detail scrim (detail.rs:549-560)
└─ column (data-row-column, data-row-anchor = 720px → 360 dp; padding-top 100 dp; translateY 240 ms)
   ├─ info row (data-row): padding 0 48 dp, bottom 22 dp, max width 620 dp
   │  ├─ title: 36 sp lh1.05 w800 ls −0.25 sp, 2 lines max (target title, then the item's title)
   │  ├─ chips (margin-top 10 dp, 13 sp w500): provider name · 電影|影集 · [season main if >1 season]
   │  │     · ["{N} 集" for series once episodes are loaded and non-empty] + badge (margin-left 9 dp)
   │  │     if the entry row is unavailable                                       (detail.rs:457-476)
   │  ├─ description (if any): margin-top 9 dp, max width 500 dp, 13 sp --text-2, 2 lines max
   │  ├─ body, by phase:
   │  │   Loading → bar track 180 × 2 dp, margin-top 24 dp, rgba(255,255,255,.12); a 40%-wide
   │  │             --text-2 block translating −100% → +250%, 1.1 s --ease-in-out, infinite
   │  │   Gone    → (margin-top 16 dp, column, gap 10 dp) "已下架" 22 sp w700 amber;
   │  │             "這個來源已經不再提供這部片。到其他來源搜尋看看？" 14 sp --text-2;
   │  │             [primary, autofocus, data-action=search-elsewhere] "搜尋「{title}」"
   │  │   Failed  → error text; [primary, autofocus, data-action=retry] "重試" (re-runs load)
   │  └─ (Ready only) actions row (data-focus-group="detail-actions", margin-top 18 dp, gap 11 dp)
   │      ├─ PLAY [primary, min-width 140 dp, height 40 dp, padding 0 22 dp, 15 sp]
   │      │     filled play icon `M7 4.5v15l13-7.5z` + label (tnum); aria-disabled until ready
   │      │     (data-action=play, focus-id detail-play)
   │      ├─ "從頭播放" with restart icon `M4 12a8 8 0 1 0 2.4-5.7M4 4v4.5h4.5`
   │      │     (only when ready AND resume position > 0; data-action=restart)
   │      └─ heart + "加入收藏" | "已收藏" (is-active when favourited; data-action=favorite)
   │     + error line for favourite failures
   ├─ seasons row (only when >1 season), compact: heading "季"; track group "detail-seasons",
   │     gap 6 dp; segmented pills (below)
   └─ episodes row (Ready and series), compact: heading "集數" + count note; track group
         "detail-episodes"; Loading → 7 tile skeletons; Failed → error caption; Ready → tiles
```
- Compact rows (`styles.css:648-650`): heading 14 sp, track padding 10 dp top / 12 dp bottom, row padding-bottom 4 dp.
- Detail rows have alpha 1 at rest; while the column has focus, non-current rows are .72 (`:593-594`). There is **no** "past → 0" rule on Detail.

**Season pill `.tv-btn-seg`** (`styles.css:652-674`):
- Height 36 dp, padding 0 16 dp, column layout (gap 0), 13 sp lh1.2, background `rgba(31,34,42,.9)`.
- Main line, w700: `season_parts(label).main` (「第 N 季」), or "季" for an empty label.
- Sub line (when there is a rest part): max width 110 dp, 10 sp w500 `--text-3`, ellipsis.
- Selected (current season): background `rgba(229,56,59,.22)` plus an inset 1 dp accent ring.
- Focused: white. Selected and focused: white, no ring. Sub-line colour when focused: `rgba(11,12,15,.6)`.
- `season_parts` rules are in `tizen/core/src/labels.rs:41-86`:
  - "Season N rest", "第N季" / Chinese numerals, "SNN" as the first token;
  - otherwise truncate at 14 chars plus "…".
- Focus id `season:{url}`. OK on another season → `open_season(non-initial)`.

**Episode tile `.tv-ep`** (`styles.css:676-754`, `detail.rs:679-736`):
- Box: 100 × 52 dp, padding 7/10/9 dp, radius 7 dp, background `rgba(31,34,42,.92)`, content bottom-left.
- Label: `episode_caption` at 14 sp lh1.15 w800 tnum, ellipsis. A watched tile's label is `--text-2`.
- Caption (only when there is a status): margin-top 3 dp, gap 3 dp, 10 sp `--text-3`.
  - Done: an 11 dp green check `m5 12.5 4.5 4.5L19 7.5` (stroke 3) followed by "已看完".
  - Partial: "剩 {max(1, ceil(left/60))} 分鐘" if duration > 0, else "看到 {clock(position)}".
  - Unwatched: no caption.
- Progress (partial only, pct > 0): bottom bar 2.5 dp, `rgba(255,255,255,.16)`; fill `--accent` scaleX(clamp(pct/100, .03, 1)) from the left.
- Selected (current episode): a left accent bar 2.5 dp wide, inset 10 dp top and bottom, right corners rounded 1.5 dp.
- Focused: white background, `#0b0c0f` text, **scale 1.08 + translateY −2 dp** (240 ms). Shadow `0 8dp blur 18dp rgba(0,0,0,.6)` fades in. Caption `rgba(11,12,15,.6)`, check `#1a8f3c`.
- Skeleton tile: background `--surface` with the shimmer.
- Focus id `ep:{label}`. OK → set the episode and play it (resume rules).

**Play label** (`detail.rs:478-495`):
- Not ready:
  - "無法載入集數" if the episodes failed;
  - "沒有可播放的集數" if a series has no episodes;
  - else "載入中…".
- Ready: with `pos = resume_position_seconds(progress[seasonKey::episode])`:
  - pos > 0 → "繼續播放{ep} · {clock(pos)}";
  - else "播放{ep}";
  - where `{ep}` = " " + caption, or "" for a movie.
- `clock` = `H:MM:SS` or `M:SS` (`common.rs:50-58`).
- Play is `aria-disabled`, hence unfocusable, until ready.

**Load** (`detail.rs:151-252`):
1. In parallel:
   - `GET /me/progress?providerKey&itemUrl`;
   - `GET /item?provider&url&title&mediaType(or "unknown")&posterUrl`;
   - `GET /me/favorites`, filtered to (provider, itemUrl).
2. Item 404 → **Gone**. Any other error → Failed(friendly).
3. Take the item's `title`, `posterUrl` and `mediaType` when non-empty. Description = `description` ?: `summary`. `detailUrl` = `detailUrl` ?: `seasonUrl` ?: target url.
4. Discriminate (**note the order**):
   - `seasons` with a non-empty url → Series hub:
     - `entry_season` picks the season (`tizen/core/src/entry.rs:26-37`): a history row's own season if the hub still lists it, else the latest-progress season, else the entry's season, else the first.
     - Then `open_season(initial=true)`.
   - else the `episodes` **key present** → Series; progress season key = the item's `seasonUrl` (**only** `seasonUrl`, not `detailUrl`); episode = `entry_episode(...)`, else the first.
   - else → **Movie**.
5. `open_season(initial)` (`detail.rs:96-149`):
   - `GET /episodes?provider&sourceUrl=<season url>`. A season-seq guard drops stale answers.
   - Pick with `entry_episode` (`entry.rs:52-80`).
   - If the season is finished and this is the initial open: open the next season, or wrap to season 0 if none and this isn't already 0.
   - A non-initial (manual) season pick that is finished selects its first episode.

**Autoplay and focus** (`detail.rs:400-424`):
- The first time the screen is *ready* (a movie, or a series with an episode chosen):
  - if `take_autoplay(provider,url)` matches → **replace** this Detail with the Player, so Back from the player returns to where Play was pressed;
  - otherwise on the next tick `restore()` route focus, else focus `[data-action=play]`.
- `play(from_start, replace)` (`detail.rs:279-314`) builds a PlayTarget:
  - `start_seconds` = 0 if from_start, else the resume position;
  - `episodes` = the season's episode list;
  - `season_url` = the season key;
  - `season_label` from the hub;
  - `media_type` "movie" | "tv";
  - `source` none.

**Favourite** (`detail.rs:316-366`):
- Busy guard. Not a favourite → `POST /me/favorites` with:
  - `providerKey`, `mediaType` (coerced to movie|tv|unknown), `title`;
  - `posterUrl` (only if it starts with `http`, else "");
  - `itemUrl`, `detailUrl`, `seasonUrl` (season key or ""), `seasonLabel`;
  - `episodeLabel` (the current episode or "").
- Already a favourite → `DELETE` **every** favourite row of the title. Show the error line on failure.

**Detail key handler** (`detail.rs:430-449`):
- **Play/PlayPause anywhere** → play with resume rules when ready (consumed).
- **Back:** if focus is NOT inside `detail-actions` → `back_to("[data-action=play]")` (reset rails, focus Play; consumed if it moved). Otherwise not consumed, so the stack pops.

### 3.5 Exit prompt (`screens/exit.rs`)

- Modal (`data-screen="exit"`, focus scope), rendered over any screen including Login (`exit.rs:9-42`).
- Title "離開 StreamHub？", text "看到一半的進度都已經存好了。".
- "取消" (`data-autofocus`, `exit-cancel`) → close, then `ensure_valid` on the next tick (focus returns to where it was).
- "離開" (danger, `exit-confirm`) → leave the app (`focus.rs:156-176`). On Android: `finish()`.
- Back while open → close it (`focus.rs:956-961`). While it is open, **no screen handler runs** (`focus.rs:1002`).

### 3.6 Player (`player/mod.rs`, CSS `player.css`)

Tree (`data-screen="player"`, with QA attributes `data-position`, `data-duration`,
`data-controls`, `data-state`, `data-backend`, `data-source`, `data-scrub`,
`data-episode`; `mod.rs:1638-1659`):
```
Root (full screen over the video; text 14 sp)
├─ driver badge (while controlled_by() is fresh; data-overlay=driver): absolute top 32 / right 48 dp,
│     row, gap 7 dp, padding 5 13 5 10 dp, pill, bg rgba(21,23,28,.86), 12 sp w500, max width 360 dp,
│     fades in 240 ms
│  ├─ dot 6 dp --accent, pulses alpha 1 → .3, 1.4 s --ease-in-out, alternate, infinite
│  └─ name, ellipsis: driver_caption(name) (§6.9)
├─ flash glyph (data-overlay=flash): centred at x 50%, y 42% of the canvas; 76 dp circle
│     rgba(8,9,12,.6); 36 dp white glyph (play `M8 5v14l11-7z` / pause
│     `M7 5h3.5v14H7zM13.5 5H17v14h-3.5z`); keyframes 600 ms --ease, forwards:
│     0% α0 s.8 → 25% α1 s1 → 70% α1 s1 → 100% α0 s1.04; the element is removed 650 ms after
│     the press; a new press restarts it                                  (player.css:281-303; mod.rs:1068-1084)
├─ chrome (the bottom block): padding 132 dp top / 48 dp sides / 32 dp bottom; its background is
│     a gradient to top rgba(8,9,12,.94) 0% → .78 30% → .38 62% → 0 100%, so it fades with the chrome.
│     Hidden: α0 + translateY 10 dp; shown: α1 + 0; 240 ms --ease              (player.css:67-85)
│  ├─ head (margin-bottom 32 dp, max width 650 dp)
│  │  ├─ status pill (while busy; data-overlay=loading): inline row, gap 6 dp, margin-bottom 9 dp,
│  │  │     padding 3 10 3 8 dp, pill, rgba(255,255,255,.1), 12 sp w500 --text; leading 5 dp
│  │  │     dot pulsing 1.1 s; fades in 240 ms. Text = notice ?: "載入中…"
│  │  ├─ kicker: 12 sp w500 --text-2 ls .02em, 1 line, margin-bottom 3 dp:
│  │  │     "{ep caption} · {source label}" | ep caption | source label
│  │  └─ title: 22 sp lh1.15 w700, 1 line, ellipsis
│  ├─ scrubber (17 dp tall)
│  │  ├─ track: top 6 dp, height 5 dp, radius 2.5 dp, rgba(255,255,255,.22), clipped;
│  │  │     scaleY .6 at rest → 1 while seeking (240 ms --ease)
│  │  │  ├─ target layer (while seeking): rgba(255,255,255,.5), scaleX(previewFrac) from the left
│  │  │  ├─ fill: --accent, scaleX(pos/dur) from the left, transition 240 ms LINEAR
│  │  │  └─ busy (buffering, no error): 26% wide gradient 0 → rgba(255,255,255,.9) → 0,
│  │  │        translateX −100% → +385% of its own width, 1.25 s --ease-in-out, infinite
│  │  └─ rail (full width, translateX(shownFrac × 100% of the rail width), 240 ms --ease)
│  │     ├─ thumb: 11 dp white circle at left −5.5 dp, top 3 dp; scale .64 → 1 while seeking (240 ms);
│  │     │     hidden when the duration is 0
│  │     └─ seek pill (while seeking): bottom 22 dp, centred on the thumb (translateX −50%),
│  │           padding 3/11 dp, pill, white bg, #08090c text, 15 sp w700 tnum, the target time;
│  │           tail = a 7 dp white square rotated 45°, bottom −3.5 dp, radius 1 dp;
│  │           enter: α0 + translateY 4 dp → in, 140 ms --ease
│  ├─ times (margin-top 5 dp, space-between, 12 sp w500 tnum): elapsed (--text) |
│  │     "−{remaining}" (U+2212, --text-2), or "--:--" when the duration is 0
│  └─ buttons (only while `controls`): margin-top 18 dp, row, gap 10 dp, rise-in
│        (α0 + translateY 12 dp → in, 240 ms); focus group "vp-controls"
│        ├─ [default] toggle: play icon + "播放" when paused / pause icon + "暫停"   (data-action=toggle)
│        ├─ back10: `M11 6V2L5 7l6 5V8a6 6 0 1 1-6 6H3a8 8 0 1 0 8-8z` + "10 秒"
│        ├─ fwd10:  `M13 6V2l6 5-6 5V8a6 6 0 1 0 6 6h2a8 8 0 1 1-8-8z` + "10 秒"
│        ├─ (has next) next-episode: `M6 5v14l9-7zM16 5h2.5v14H16z` + "下一集"
│        └─ sources: `M4 6h16v2H4zm0 5h16v2H4zm0 5h10v2H4z` + "片源"
├─ episode strip (data-overlay=episodes): bottom block, padding 110 dp top / 48 dp sides / 32 dp bottom,
│     gradient to top .96 0% → .8 45% → 0 100%; rise-in 240 ms
│  ├─ head (baseline row, gap 10 dp, margin-bottom 12 dp): "選集" 16 sp w650 ·
│  │     "共 {n} 集" 12 sp --text-2 · hint (margin-left auto, 11 sp --text-2):
│  │     [◀ ▶]瀏覽 [OK]播放 [返回]關閉
│  └─ viewport (margin −12 dp vertical / −48 dp horizontal, padding 12/48 dp, clipped)
│     └─ track (row, gap 12 dp, translateX(−strip_offset) 240 ms --ease)
│        └─ tile ×n: 130 × 75 dp, padding 11 dp top / 12 dp sides / 0 bottom, radius 7 dp,
│              --surface-2, clipped
│              ├─ label: ep caption 16 sp lh1.2 w700, ellipsis
│              ├─ meta: margin-top 3 dp, 11 sp --text-2, min-height 14.5 dp:
│              │     "播放中" (current; --accent w600) | "已看完" | "已看 {N}%" | ""
│              └─ bar: bottom 3 dp rgba(255,255,255,.14); fill --accent scaleX(f, or 1 if completed)
│              Focused (the strip index): white, #08090c text, scale 1.08, translateY −3 dp (240 ms);
│              meta rgba(8,9,12,.66); bar rgba(8,9,12,.14)
├─ up-next card (data-overlay=up-next): absolute right 48 dp, bottom 150 dp, 320 dp wide,
│     row gap 15 dp, padding 14 20 14 14 dp, radius 10 dp, gradient 135° #23262f → #15171c,
│     shadow 0 12dp blur 30dp rgba(0,0,0,.55), border 0.5 dp rgba(255,255,255,.08);
│     slides in α0 + translateX 40 dp → in, 420 ms --ease
│  ├─ ring 56 dp (viewBox 80): bg circle r34 stroke 6 rgba(255,255,255,.16); fg circle r34 stroke 6
│  │     white, round cap, starts at 12 o'clock, dasharray C=2π·34≈213.63,
│  │     dashoffset = C·(1 − left/120) (clamped), transition 1 s linear; centre number = seconds
│  │     left, 26 user-units bold tnum (≈ 18.2 sp on screen, because the 80-unit box draws at 56 dp)
│  └─ body: kicker "即將播放" 10 sp w600 ls .12em --text-3; title "下一集 {caption}" 18 sp w700,
│        margin 2 dp top / 6 dp bottom, ellipsis; hint 11 sp --text-2: [OK]立即播放 [返回]取消
│     (key chips: padding .5/5 dp, radius 4 dp, rgba(255,255,255,.14), 10 sp w600 --text,
│      margin-right 4 dp; each later chip margin-left 11 dp)
├─ source picker modal (data-overlay=sources): backdrop rgba(0,0,0,.6) fade 240 ms; panel
│     (left-aligned text, min width 410 dp, max width 500 dp, padding 28/32/30 dp, radius 10 dp,
│      gradient 160° #1f222a 0% → #15171c 70%, border 0.5 dp rgba(255,255,255,.07),
│      shadow 0 16dp blur 40dp rgba(0,0,0,.6); rise-in 420 ms)
│  ├─ "選擇片源" 22 sp w700; "切換後會從目前位置繼續播放" margin-top 7 dp, 14 sp --text-2
│  └─ list: margin-top 14 dp, column gap 8 dp, max height 308 dp clipped (the focused row is
│        scrolled into view), horizontal bleed 14 dp for the scale
│     └─ row (vp-pill, full width, padding 0 16 dp right / 18 dp left, space-between, gap 12 dp;
│          focus group vp-sources; data-action=source; is-current on the playing one)
│          ├─ label (flex, ellipsis): sourceLabel, or "片源 {i+1}"
│          ├─ "免廣告" badge when adSeconds > 0 (rounded): padding 1/7 dp, pill,
│          │     rgba(46,204,113,.16), #4ade80, 11 sp w700
│          ├─ runtime when durationSeconds > 0: "H:MM:SS" | "M:SS", 12 sp w500 --text-2 tnum
│          └─ "播放中" with a 5 dp accent dot (current only), 11 sp w600 --text-2
│        Focused: white, scale **1.03** (not 1.06); badge #15803d on rgba(22,163,74,.16);
│        runtime and 播放中 rgba(8,9,12,.7)
└─ error modal (data-overlay=error, data-error-code=<engine code>): same panel, centred
   ├─ icon: 42 dp circle with a 2 dp accent border, "!" 24 sp w800 accent
   ├─ "無法播放"; "這一集目前沒有可用的片源。可以重試，或返回改看其他集數。"
   └─ actions (margin-top 22 dp, gap 12 dp; pills min width 100 dp; group vp-error):
        [primary] "重試" (data-action=retry) · "返回" (data-action=back)
```
- Player pill `.vp-pill` (`player.css:246-278`):
  - Height 36 dp, padding 0 17 dp, gap 6 dp, 14 sp w600 lh1.
  - Background `--surface-2`; primary `--accent`.
  - Focused: white, `#08090c` text, scale 1.06 (140 ms). Icons 15 dp, filled.
- `strip_offset(idx, n) = clamp((idx − 2) × 142 dp, 0, max(n × 142 − 12 − 864, 0))` dp (`mod.rs:53-55, 69-72`; 284/24/1728 px).
- Behaviour, timings and the state machine are in section 6.

### 3.7 Error copy (`friendly_error`, `screens/common.rs:313-359`)

Applied in order to `(status, message)`. Status 0 means no answer; on Android
that is an `IOException` or timeout.
1. The message already contains a CJK char (U+4E00–U+9FFF) → show it as is.
2. Contains "timeout" / "timed out" / "aborted" → "連線逾時，請再試一次".
3. Offline, or contains "failed to fetch" / "network" → "無法連線到伺服器".
4. Contains "invalid credentials" → "帳號或密碼不正確".
5. Contains "admin" → "管理員帳號無法在電視上觀看，請使用一般觀眾帳號。".
6. Contains "too many" or status 429. Take the first integer in the message:
   - seconds ≥ 60 → "嘗試次數過多，請在 {ceil(s/60)} 分鐘後再試";
   - seconds → "嘗試次數過多，請在 {s} 秒後再試";
   - minutes → "嘗試次數過多，請在 {m} 分鐘後再試";
   - else "嘗試次數過多，請稍後再試".
7. Contains "expired" → "代碼已過期，請重新取得".
8. Contains "not found" or status 404 → "找不到這個內容".
9. Otherwise, with detail = "：{msg}" unless msg is empty or starts with "伺服器錯誤":
   - 401 → "登入已失效，請重新登入";
   - 403 → "沒有權限";
   - 400 → "要求的內容不正確{detail}";
   - 5xx → "伺服器發生錯誤{detail}";
   - 0 → "無法連線到伺服器";
   - else "發生錯誤（{s}）{detail}".

A missing server `error` field becomes "伺服器錯誤（{status}）" (`api.rs:130-137`).
`:core` produces "Request failed ($status)." instead (`StreamHubApi.kt:573-579`).
That is *not* equivalent: Tizen's fallback contains CJK, so rule 1 shows
"伺服器錯誤（502）" verbatim, while the English one would reach rule 8/9.
`screens/common/FriendlyError.kt` rewrites the `:core` fallback to Tizen's
before applying the rules.

---

## 4. Focus engine (`focus.rs`)

Reimplement this as **one engine that owns all keys**, fed from
`Activity.dispatchKeyEvent` (both `ACTION_DOWN` and `ACTION_UP`). Do not use
Compose's built-in focus traversal. Each focusable registers:
- its **visual** bounds in canvas units, after `graphicsLayer` scale and translation (Tizen uses `getBoundingClientRect`, which includes transforms — `focus.rs:575-578`);
- its **untransformed layout** offset inside its track or column (for `offset_within`, `focus.rs:290-302`);
- its attributes: `focusId`, `group`, `autofocus`, `longpress`, `playable`, `favable`, `selected`, `disabled`, and whether it is a text field;
- its containers: rail track, row, row column, scope.

"Focused" is a single engine state, mirrored to `is-focused` styling. Text fields
additionally receive real Android input focus, which the IME needs (`focus.rs:259-273`).

### 4.1 Candidates
- `is_usable(el)` = attached, not `aria-disabled`, not `disabled`, and a non-zero visual width and height (`focus.rs:193-203`).
- Candidates = the usable focusables inside the **active scope**: the *last* visible `[data-focus-scope]` in document order, else the whole page (`:205-220`).
- `group_of(el)` = the nearest `data-focus-group` on the element or an ancestor (`:224-226`). Tracks carry the group for their cards.

### 4.2 Choosing a target (`measure` / `score` / `find_target`, `focus.rs:587-723`)

`cur` = the focused element's rect; `c` = a candidate's rect; rect = (l, t, r, b), cx, cy.

```
SLACK = 2px (1 dp)
slack_x = max(0.25·(cur.r − cur.l), SLACK);  slack_y = max(0.25·(cur.b − cur.t), SLACK)
gap(a0,a1,b0,b1) = b0−a1 if a1<b0; a0−b1 if b1<a0; else 0

Right: c.cx > cur.cx + slack_x  AND  c.l ≥ cur.l + SLACK   → primary = c.l − cur.r
Left:  c.cx < cur.cx − slack_x  AND  c.r ≤ cur.r − SLACK   → primary = cur.l − c.r
Down:  c.cy > cur.cy + slack_y  AND  c.t ≥ cur.t + SLACK   → primary = c.t − cur.b
Up:    c.cy < cur.cy − slack_y  AND  c.b ≤ cur.b − SLACK   → primary = cur.t − c.b
otherwise the candidate is not in that direction.

Horizontal measure: primary = max(primary, 0); cross_gap = gap(cur.t,cur.b,c.t,c.b);
                    cross_centre = |c.cy − cur.cy|;   cross_edge = |c.t − cur.t|
Vertical measure:   primary = max(primary, 0); cross_gap = gap(cur.l,cur.r,c.l,c.r);
                    cross_centre = |c.cx − cur.cx|;   cross_edge = |c.l − cur.l|

score(m) = primary + 6·cross_gap + 0.3·cross_centre
```
Ties: pick the first minimum in candidate (document/composition) order
(`min_by` with `total_cmp`, `:647-653`).

`find_target(cur, dir)`:
1. Measure every candidate except `cur`. If there are none → no target (an edge).
2. **Left/Right:**
   - If `cur` has a group, pick the min `score` among the measured candidates **in the same group**.
   - If there are none, pick the min `score` among the measured candidates with `cross_gap == 0` (strictly the same line).
   - If still none → no target. The end of one rail never jumps diagonally.
3. **Up/Down:**
   - pool = the measured candidates inside the same `[data-row-column]` as `cur` if that set is non-empty, else all measured.
   - `nearest` = the min of `primary + 0.001·cross_centre`.
   - Row = the pool elements vertically overlapping `nearest` (`r.t < n.b && r.b > n.t`).
   - Choose the min of `10·cross_gap + cross_edge` in that row, else `nearest`.
4. **Group entry:** if the chosen element's group differs from `cur`'s group:
   - return the group's **remembered** element if it is still usable and a candidate;
   - else the group's `is-selected` element (the current season or episode);
   - else the chosen element.

### 4.3 Groups and route memory
- Each focus records `groups[group] = el` (`focus.rs:432-438`).
- **Route memory** (`:228-246, 395-409, 528-538`):
  - key = `"{depth}|{what}"`, where depth = the nav stack length and what = `home` | `search` | `detail:{provider}:{url}` | `player:{itemUrl}`.
  - Value = the `data-focus-id` last focused on that entry.
  - Elements inside a modal scope are **not** recorded.
  - Every record prunes keys whose depth > the current depth.
  - `restore()` prunes the same way, then focuses the element with the saved id if it is usable.
  - Recording happens on every `focus_element` with `remember=true`, **and on OK** (`:1084, 1110`), because pressing OK is a choice even where focus landed by default.
- Watchdog fallbacks do **not** record (`remember=false`, `:411-413, 942`).

### 4.4 Modal scopes
- The active scope is the last visible `[data-focus-scope]`. Navigation is confined to it.
- `ensure_valid` (§4.12) moves focus into a newly opened scope:
  - it first saves the outside element in `before_scope`;
  - it then focuses the scope's `[data-autofocus]`, else its first focusable.
- When no scope remains, `before_scope` is restored if it is still usable (`focus.rs:898-929`).
- Scopes in the app: the exit prompt, the Home menu, the sign-out confirm. The player's picker and error panel are **not** scopes; the player's key handler confines them.

### 4.5 Reveal by transform (`reveal`, `reveal_in_track`, `reveal_in_column`, `focus.rs:311-388`)

Applied on the **next frame** after focus changes. A burst of steps applies one
transform, for the latest element only (`schedule_reveal`, `:449-471`).

**Track (horizontal):**
```
start = 96px (48 dp)                       // data-rail-start default; nothing overrides it
left  = untransformed x of el inside the track (includes the track's 48 dp left padding)
viewport = width of the track's parent (the full-width rail = 960 dp)
max_shift = max(track_width − viewport, 0)    // track_width = content + both paddings, ≥ viewport
shift = round(clamp(left − start, 0, max_shift))
track.translationX = −shift                   // animated 240 ms --ease, retargeting
```
- A track's first reveal (no previous shift recorded) **snaps** without animation (`:318-329`).
- The engine records `shift` per track (`data-shift`), which is how `back_to` tests "at rest".
- Example, Home: card pitch = 100 + 14 = 114 dp, so card *i* sits at x = 48 + 114·i. Focus therefore stays at x = 48 dp until the rail's end clamps it.

**Column (vertical):**
```
row = the nearest [data-row] containing el
anchor = data-row-anchor (Home 0, Search 0, Detail 720px → 360 dp)
top = untransformed y of row inside the column
column.translationY = round(min(anchor − top, 0))     // rows above the anchor never move down
```
- The column gets `has-focus`.
- Each direct row of this column gets `is-current` (the focused row) or `is-past` (rows before it). Only rows whose nearest column is this one are counted.
- Any *other* column that had focus returns to rest: translation 0, flags cleared (`rest_column`, `:356-363`).
- **Neither:** an element outside any track or column is brought into view with an instant "nearest" scroll (the picker's source list, the login stage). Elements under `[data-no-scroll]` are skipped (`:385-387`); nothing currently uses that attribute.

**`back_to(selector)`** (`focus.rs:856-874`):
- target = the first usable match.
- If focus is already on it and every track's recorded shift is 0 → return false; the caller lets Back go further.
- Otherwise every track goes to translation 0 (shift 0), every column rests, focus moves to the target, and it returns true.

### 4.6 Hold-to-scroll pacing (`hold_steps`, `focus.rs:822-851`)
- A first press (not a repeat), or a different key → **1 step**, and the hold clock resets: `hold_key = key`, `hold_start = last_move = now`, `hold_edged = false`.
- Repeats of the same key:

| Key | Held for | Minimum interval | Steps |
|---|---|---|---|
| Up/Down | any | 220 ms | 1 |
| Left/Right | < 400 ms | 160 ms | 1 |
| Left/Right | 400–1500 ms | 80 ms | 1 |
| Left/Right | ≥ 1500 ms | 80 ms | **3** |

- A repeat within the interval is **dropped** (0 steps), never queued.
- Key-up of an arrow clears `hold_key` (`:1145-1147`), so release stops exactly where focus is.
- CH+/CH−/PgUp/PgDn always take 1 step on *every* keydown, repeats included, with no pacing (`:1057-1063`).
- Use the monotonic clock. Tizen uses `Date.now()` here; use `SystemClock.uptimeMillis()`.

`step(dir, steps, repeat)` (`focus.rs:776-820`):
- No usable focused element → buffer the key (non-repeat only), `ensure_valid_now()`, return.
- Otherwise call `find_target` up to `steps` times, focusing each result.
- Nothing moved:
  - Down, non-repeat, and the screen is loading → buffer it;
  - else **bump** (§4.7). If the bump was shown and this is the first edge of this hold → `Edge` sound. `hold_edged` is set so a held key thuds once per wall.
- Moved → clear `hold_edged`, then play `Move` (repeat → `play_repeat`).

### 4.7 Bump (`focus.rs:739-773`, CSS `styles.css:945-959`)
- If another bump happened < **300 ms** ago (globally, any element), show nothing and return false, which also means no sound.
- Otherwise restart a 160 ms `--ease` animation on the element. At 45% of the timeline it is translated 12px → **6 dp** in the pressed direction, composed *before* the element's own focus transform. Card: `translate · scale(1.08) · translateY(−6px)`. Button: `· scale(1.06)`. Episode tile: `· scale(1.08) translateY(−4px)`. Others: `· scale(1)`. Keyframes 0% and 100% are the element's normal transform.
- Remove the class 220 ms later. Record `data-bump=<dir>` on the element; it is cleared when focus moves away.
- Note: `.vp-pill` gets no `--fx` override, so a bumping *focused player pill* drops to scale 1 at the 45% keyframe (`styles.css:948-951`). Reproduce it for strict parity; see the ambiguities.

### 4.8 Key buffering (`focus.rs:729-737, 473-501`)
- Buffer: up to **3** directions, each stamped with its time.
- After any `focus_element`, a 0 ms task replays the buffer once (guarded by `replaying`). Each key is replayed only if it is ≤ **2000 ms** old, via `step(dir, 1, false)`, which produces Move sounds.
- Back clears the buffer (`:949`).
- "Loading" = any skeleton or `[data-loading]` on screen (`:726-728`).

### 4.9 OK, long press, intents (`focus.rs:108-146, 1070-1115, 1148-1157`)
- `Intent`: Open (default) | Play | LongPress | Favorite. It is readable by the click handler during the click only.
- OK keydown, repeats ignored:
  - A usable target → `Select` sound **on the press**.
  - Target has `longpress`: record the route; start a press generation; after **700 ms** (`LONG_PRESS_MS`), if still pressed and unfired → click with `LongPress` and mark it fired. On key-up: unfired → click (Open); fired → nothing.
  - Target without `longpress`: record the route and **click immediately on keydown**.
  - No target → `ensure_valid_now()`.
- Play / PlayPause key on a focused `playable` element → `Select` sound and click with `Play`. Otherwise, if no screen handler took it, nothing happens.
- Red key (Tizen 403) on a focused `favable` element → `Select` and click with `Favorite`.

### 4.10 Dispatch order (`on_keydown`, `focus.rs:992-1140`)
1. Count the key (`key_count`, used by Home, `:126-133`).
2. IME composing (Tizen `isComposing` / 229) → ignore.
3. **In a text field** (`:1004-1029`):
   - Backspace → leave it to the field.
   - IME Cancel → ignore.
   - Enter / IME Done → submit the enclosing form.
   - Left/Right → leave the field only when the caret is collapsed at 0 (Left) or at the end (Right); otherwise the field consumes the key.
4. While the exit prompt is open, the screen handler is disabled.
5. For any key except Back, call the **screen handler** first. True → consumed, and play a sound: arrows `Move` (repeat → `play_repeat`), Enter (non-repeat) `Select` (`:1033-1048`).
6. Arrows and CH/Page keys → pacing → `step`.
7. Enter → §4.9. Back → §4.11. Exit → leave the app. Play/PlayPause → playable. Red → favable.

On key-up: arrows clear the hold; Enter resolves a long press (`:1142-1160`).
Programmatic focus changes (IME returning focus) re-sync the engine (`on_focusin`, `:1164-1171`).

### 4.11 Back chain (`handle_back`, `focus.rs:948-967`)
1. Clear the key buffer.
2. Play the `Back` sound (silent while the player is up).
3. Screen handler(Back) → if true, stop.
4. Exit prompt open → close it, run `ensure_valid` on the next tick, stop.
5. `nav.back()` pops if depth > 1 → stop.
6. At the root → **open the exit prompt** and run `ensure_valid` on the next tick. Focus goes to "取消".

Per-screen handlers (Login, Home, Search, Detail, Player) are in sections 3 and 6.

### 4.12 `ensure_valid` (`focus.rs:881-946`)

Runs every **200 ms** (`:1185-1187`) and after modal open/close. `ensure_valid_now`
is the same pass on behalf of a key press: it ignores `[data-loading]`.

- **A scope is active** and focus is not inside it: save the outside element to `before_scope`, then focus the scope's autofocus, else its first focusable.
- **No scope:**
  - take `before_scope`;
  - if focus is valid and not inside a dead scope → done;
  - else restore `before_scope` if usable → done;
  - else `restore()` route memory → done;
  - else, if passive and `[data-loading]` is present → do nothing. Home's first load must not park focus on 搜尋.
  - else focus the first `[data-autofocus]`, else the first focusable, with `remember=false`.

### 4.13 Text-input caret rules
See §4.10 step 3. Android: implement the fields as `BasicTextField` with a
`TextFieldValue` selection that you can read. Up/Down always leave. The IME
action `Search`/`Done` submits.

---

## 5. Key map

Tizen codes from `keys.rs:29-53`, `focus.rs:74-76, 1057-1058, 1131`, `tizen-keys.js:225-232`.

| Action | Tizen keyCode | Android `KeyEvent` |
|---|---|---|
| Up / Down / Left / Right | 38 / 40 / 37 / 39 | `KEYCODE_DPAD_UP` 19 / `DPAD_DOWN` 20 / `DPAD_LEFT` 21 / `DPAD_RIGHT` 22 |
| OK / Enter | 13 | `KEYCODE_DPAD_CENTER` 23, `ENTER` 66, `NUMPAD_ENTER` 160 (optionally `BUTTON_A` 96) |
| Back | 10009 (desktop 27 Esc, 8 Backspace outside fields) | `KEYCODE_BACK` 4, `ESCAPE` 111; `DEL` 67 **outside** text fields only. Handle on **ACTION_DOWN** and swallow the matching UP. Bypass `OnBackPressedDispatcher`, which fires on up |
| Play/Pause toggle | 10252 (desktop Space / "MediaPlayPause") | `KEYCODE_MEDIA_PLAY_PAUSE` 85, `HEADSETHOOK` 79, `SPACE` 62 outside fields |
| Play | 415 | `KEYCODE_MEDIA_PLAY` 126 |
| Pause | 19 | `KEYCODE_MEDIA_PAUSE` 127 |
| Stop | 413 | `KEYCODE_MEDIA_STOP` 86 |
| Rewind ⏪ | 412 | `KEYCODE_MEDIA_REWIND` 89 |
| Fast-forward ⏩ | 417 | `KEYCODE_MEDIA_FAST_FORWARD` 90 |
| Previous / Next episode | 10232 / 10233 | `KEYCODE_MEDIA_PREVIOUS` 88 / `MEDIA_NEXT` 87 |
| Row up / Row down | CH+ 427 / CH− 428, PgUp 33 / PgDn 34 | `KEYCODE_CHANNEL_UP` 166 / `CHANNEL_DOWN` 167, `PAGE_UP` 92 / `PAGE_DOWN` 93 |
| Favourite (red) | 403 | `KEYCODE_PROG_RED` 183 |
| Exit app | 10182 | **No Android equivalent.** HOME belongs to the system and arrives as `ON_STOP`. See §6.8 and the report |
| IME Done / Cancel | 65376 / 65385 | `EditorInfo.IME_ACTION_SEARCH`/`DONE` via `KeyboardActions`; the IME's own Back closes it |
| Composing | 229 / `isComposing` | ignore keys while a composition is active |

Registered on Tizen but unused by the app: `Info`, the digits 0–9 and the other
colour keys (`tizen-keys.js:230-231`). Ignore them.

`repeat` = `event.repeatCount > 0`. Long-press Back belongs to the platform on
Tizen (`tizen-keys.js:218-219`). On Android, **ignore Back repeats**
(`repeatCount > 0`); otherwise holding Back would pop several levels.
`focus.rs:1116` does not check repeat, but Tizen never delivers repeats of Back
to the page.

---

## 6. Player

### 6.1 Engine abstraction

The Rust side talks to a shim with `open(url, startMs)`, `play`, `pause`,
`seek(ms)`, `close`, `position()`, `duration()`, `backendName()`, and listener
events `onState`, `onTime(ms)`, `onBuffering(bool)`, `onEnded`, `onError(msg)`
(`player-shim.js:9-13, 380-423`; `player/avplay.rs:35-43`). States are
`idle | loading | playing | paused | ended | error`.

Map this onto ExoPlayer (Media3, already a `:tv` dependency):
- **`open(url, startMs)`:**
  - bump a generation counter; events from older generations are dropped (`player-shim.js:14-15, 383-387`, `avplay.rs:46-52, 91-96`);
  - state → `loading`, buffering → true;
  - `setMediaItem(url, startMs)`, `prepare()`, `playWhenReady = !wantPaused`;
  - Tizen's AVPlay does prepare → `seekTo(start)` → play (`player-shim.js:126-139`).
- **wantPaused:** `pause()` or `play()` during `loading` only records the intent. The source then comes up in `paused`, not playing over the press (`player-shim.js:29-31, 400-409`). Each `open` resets wantPaused to false (`:384`).
- **Event mapping:**
  - `STATE_BUFFERING` → `onBuffering(true)`; `STATE_READY` → `onBuffering(false)`;
  - first READY with playWhenReady → `playing`; READY without it → `paused`;
  - `onIsPlayingChanged` / `playWhenReady` → `playing`/`paused`;
  - `STATE_ENDED` → `ended` + `onEnded`;
  - `onPlayerError` → `error` + `onError(errorCodeName)`.
- **Time:** emit `onTime` with the current position roughly every 250–500 ms. AVPlay's `oncurrentplaytime` and the html5 `timeupdate` run at that cadence.
- **seek:** emit buffering true, `seekTo`, then buffering false when the seek completes (`player-shim.js:147-158`).
- **duration():** 0 until known.
- **Visibility:** Tizen calls `avplay.suspend()` when the app is hidden and `restore()` when it is shown again (`player-shim.js:170-176`). Android: on `ON_STOP`, pause (or release and remember the position and intent); on `ON_START`, restore. The page also reports `pause` progress when hidden (`mod.rs:351-360`).
- A **mock backend** exists for QA (`player-shim.js:286-372`). It is optional on Android.

### 6.2 State machine (`player/mod.rs`)

`Ui` signals (`mod.rs:75-131`):
- `status`, `buffering` (initially **true**), `pos_s`, `dur_s`;
- `controls` (the button row) and `osd` (the info bar; initially **true**);
- `seek_preview`, `notice` (initially **"尋找片源中…"**), `fatal`;
- `up_next(caption, secondsLeft)`, `has_next`, `picker`, `sources`, `current_source`, `source_label`, `driver`, `scrub`, `flash`, `strip(index)`, `strip_info`.

**Chrome visible** = `osd || controls || busy || paused`, where busy =
`(buffering || notice != null) && fatal == null` (`mod.rs:1624-1628`).

**Mount** (`mod.rs:282-397`):
1. Tear down any previous player first.
2. Start at `start_seconds`.
3. Compute neighbours:
   - next = `up_next(episodes, current)` (episode only);
   - prev = `previous_episode` (never crosses seasons);
   - if the current episode is the season's last → `resolve_next_season()` (§6.6).
4. Start a 1 s ticker.
5. Register the cast command handler.
6. Listen for visibility changes, and install capture-phase listeners for `repeat` and key-up.
7. `set_body_playing(true)`: the sfx are silenced (§8).
8. If the target carries a `source` (a cast `play`), list it as index 0 and `begin(0)` at once.
9. **Always** `load_sources()`.
10. `show_osd()`, then `publish()`.

**Teardown** (`mod.rs:247-279`), on any route change away from or replacement of the player:
1. Remove the key listeners.
2. If started and not ended → report `pause`.
3. Publish a null state.
4. Clear the command handler and the screen handler.
5. Abort the source stream.
6. Close the player.
7. `set_body_playing(false)`.

**Shim events** (`mod.rs:703-752`):
- `playing`: clear the notice if not buffering. Apply a deferred seek. If the viewer paused meanwhile, pause. Publish.
- `paused`: apply a deferred seek; `intent_play = false`; report `pause`; `show_osd`; publish.
- `time`: update pos/dur (`last_good_ms` when > 0), the whole-second UI and up-next.
- `buffering(b)`: if !b and playing and not offline → clear the notice. Publish.
- `ended` → `on_ended`.
- `error` → `on_fault`.

**Ended** (`mod.rs:804-826`):
1. Set ended and position = max(dur, pos).
2. Report `ended`.
3. Hide up-next and publish.
4. If there is a next episode and up-next was not dismissed → `nav.replace(Player(next))`, starting at 0. Otherwise `nav.back()`, which leaves to the title page.

### 6.3 OSD / controls timing
- `show_osd()` sets osd and (re)arms a **4000 ms** hide timer (`CONTROLS_TIMEOUT_MS`, `mod.rs:40, 1247-1270`).
- When the timer fires it does nothing if the picker is open, the error panel is up or status is `paused`. Hidden controls on a paused picture would offer no way back. If a seek preview is showing, it re-arms. Otherwise it sets controls = false and osd = false.
- `show_controls()` = controls on + `show_osd` + focus `[data-vp-default]` (the toggle pill) on the next tick (`:1272-1276`).
- `hide_controls()` = controls off; osd = paused; cancel the timer (`:1278-1282`).
- While the controls are shown, every arrow or Enter re-arms the timer and is then handled by the focus engine, which moves between or clicks the pills (`:1368-1381`).

### 6.4 The remote in the player (`on_key` / `on_dpad`, `mod.rs:1317-1424`)

`repeat` comes from the key event. Order:
1. **Exit** → report `pause`, then let the app exit.
2. **Error panel up** → Back = leave. Every other key goes to the focus engine (重試/返回).
3. **Picker open** → Back = `close_picker()`, which closes it and calls `show_controls`. Other keys go to the focus engine.
4. Media keys (always, unless 2 or 3 applied):

   | Key | Action |
   |---|---|
   | PlayPause (non-repeat) | `toggle_with_feedback()`: toggle, flash the glyph of the resulting state, `show_osd` |
   | Play / Pause (non-repeat) | play / pause, flash, `show_osd` |
   | Repeats of those three | ignored |
   | Stop | leave |
   | Rewind / FF | `nudge(∓30 000 ms)` (`MEDIA_SEEK_STEP_MS`) |
   | Next / Previous | replace with the neighbour episode, if any |

5. **Episode strip open** (`strip_key`, `:1140-1152`):
   - Left/Right: index ∓1, clamped, no wrap. Every keydown counts, repeats included.
   - Enter (non-repeat) → `choose_episode(i)`.
   - Up / Back → close the strip, then `show_osd`.
   - Down and Enter repeats → swallowed.
   - Anything else → not handled.
6. **Controls shown** → Back = `hide_controls`. Arrows and Enter re-arm the hide timer and pass to the focus engine.
7. **Scrubbing** → Left/Right = `scrub_hold(dir)`; Enter (non-repeat) = commit; Back = cancel; all other keys swallowed.
8. **Up-next card up** → Enter (non-repeat) = go next; Back or Down = dismiss (`up_next_dismissed = true`); Up = `show_controls`; **everything else is swallowed**, including Left/Right.
9. **Base:**
   - Enter → `ok_down(repeat)` (§6.5).
   - Left/Right **repeat** → `scrub_hold(dir)`.
   - Left/Right first press → `key_down_at = now`, then `nudge(±10 000 ms)`.
   - Up → `show_controls`.
   - Down → open the episode strip if the season has episodes, else `show_controls`.
   - Back → if a seek is pending, cancel it; else leave.

Back therefore peels one layer per press: error → picker → strip → controls →
scrub → up-next → pending seek → leave.

### 6.5 Seek semantics
- **Taps** (`advance` / `nudge`, `mod.rs:857-891`):
  - target = (pending ?: deferred ?: pos) + delta, clamped to [0, dur − 1000] when dur > 0;
  - show the preview pill and `show_osd`;
  - taps accumulate, and one seek is sent **400 ms** after the last one (`SEEK_DEBOUNCE_MS`).
  - It is refused while fatal or before any source started.
- **Hold** (`scrub_hold` / `scrub_tick` / `commit_scrub`, `mod.rs:896-969`):
  - The first Left/Right repeat enters scrub mode: `data-scrub=1`, track and thumb expand. It cancels the tap debounce.
  - The acceleration clock starts at the original key-down if that was < 2000 ms ago.
  - Every **120 ms** (`SCRUB_TICK_MS`) advance by dir × step:

    | Held for | Step |
    |---|---|
    | < 1 s | 10 s |
    | < 3 s | 30 s |
    | ≥ 3 s | 60 s |

  - Each repeat extends `held_until = now + 450 ms`. A tick past `held_until` means the release was lost → commit.
  - Reversing direction resets the acceleration clock.
  - Key-up of Left/Right, or an OK keydown (non-repeat), → commit, which is one seek to the target (`mod.rs:985-991, 1390`). Back → cancel: no seek, preview cleared, `show_osd`.
- `seek_to(ms)` (`mod.rs:1179-1200`):
  - clears pending; sets pos = `last_good`;
  - if not playing or paused yet → store it as the **deferred seek**, applied on `playing`/`paused`;
  - otherwise seek the player;
  - then clear the preview, update the whole-second position, check up-next and publish.
- **Scrubber visuals:**
  - the fill shows the real position;
  - the target layer, rail, thumb and pill show `seek_preview ?: pos`;
  - the pill text is `format_time(preview)` (`media.rs:175-182`).

### 6.6 OK: tap vs long press, flash, strip, picker, up-next
- **OK** (`mod.rs:999-1055`):
  - keydown arms a **700 ms** timer (`OK_LONG_PRESS_MS`); repeats set `ok_repeat`;
  - key-up before the timer → **tap** = `toggle_with_feedback` (ignored while the picker or the error panel is open);
  - timer fired with repeats seen → **long press** = `open_picker`, and the key-up does nothing;
  - timer fired with no repeats and no key-up → the platform lost the release; treat it as a tap.
- **Flash** (`mod.rs:1068-1084`): `flash(playing)` shows the play glyph if the result is playing, else pause. A new nonce restarts it. It clears after 650 ms.
- **Strip** (`mod.rs:1092-1173`):
  - On open, hide controls and osd, cancel the hide timer, and set the index to the current episode.
  - Then `GET /me/progress?providerKey&itemUrl` → per episode (fraction, completed) using the key `season_url::label`.
  - Choosing the current episode just closes the strip.
  - Choosing another → `replace(Player)` at that episode's `resume_position_seconds` (completed → 0, else pos − 30), or 0 if it has no progress.
- **Picker** (`mod.rs:1221-1243, 1284-1311`):
  - Open → controls off, picker on, cancel the hide timer, focus the `is-current` row, else the first row.
  - Selecting the current source → close and `show_controls`.
  - Otherwise:
    1. `ladder.select(idx)`;
    2. report `switch`;
    3. notice "切換片源…";
    4. `start_source(idx, Manifest, pos)`;
    5. `POST /me/source-preference {providerKey, mediaType, title, sourceLabel}` (skipped if the label or title is empty);
    6. `show_osd`.
- **Up-next** (`mod.rs:777-802`, `tizen/core/src/resume.rs:105-110`):
  - Offered while `next` exists, not ended, `0 < dur` and `dur − pos < 120 s`, unless dismissed.
  - Leaving the window clears the dismissal.
  - Caption "下一集 {caption}"; seconds left = ceil(dur − pos). It is not shown over the error panel.
  - It **does not** auto-jump at 0; the stream's end triggers the advance (§6.2 Ended).
- **Next season** (`mod.rs:1563-1600`):
  1. `GET /item?provider&url=itemUrl&title&mediaType=tv` → seasons;
  2. `up_next(...)` = the next season;
  3. `GET /episodes?provider&sourceUrl=<next season url>`;
  4. next = the first episode of that season, with `season_label`, `season_url` and `episodes` replaced;
  5. `has_next = true`.

### 6.7 Source ladder (`player/ladder.rs`, `player/media.rs`)

**URL tiers.** Tizen **never plays the raw `directUrl`**:
- **Manifest** tier: `GET /api/v1/manifest?target=<directUrl>&accessToken=<token>`. Ads are cut and segments go straight to the CDN (`media.rs:44-47`, `api.rs:68-73`). `directUrl` falls back to `url` (`media.rs:49-53`).
- **Relay** tier: the line's `proxyUrl`, made absolute and pinned to `/api/v1`, plus `accessToken` if it is missing. With no `proxyUrl` (a cast source) it is `/api/v1/stream?target=<directUrl>&accessToken=…` (`media.rs:58-83`). It is not ad-filtered.

Android:
- Manifest: `api.manifestUrl(directUrl)`.
- Relay: `proxyUrl` rebased onto `/api/v1` when present, else `api.streamUrl(directUrl)`.
- Load both through an OkHttp data source built on `api.authenticatedClient`. It attaches `Authorization` to this server's host only (`AuthInterceptor.kt`), which shared/api/README.md "Media" requires. The query token is unnecessary on Android but harmless.

**Ladder** (`ladder.rs:40-73`):
```
new(source) = { source, tier: Manifest, retries_used: 0, retries_per_tier: 1 }
next(known):  retries_used < 1     → retries_used++ ; Retry
              tier == Manifest     → tier = Relay; retries_used = 0; SwitchToRelay
              source+1 < known     → *self = new(source+1); NextSource(source+1)
              else                 → Exhausted
forgive():    tier = Manifest; retries_used = 0
select(i):    *self = new(i)
```
The sequence per source is: retry manifest → relay → retry relay → next source.

**Choosing the first source** (`mod.rs:405-548`):
1. `preferred = GET /me/source-preference?providerKey&mediaType&title` → `preference.sourceLabel` (`media.rs:185-197`).
2. Stream the sources (`media.rs:215-250`):
   - **tv** → NDJSON `GET /sources?provider&sourceUrl=<season_url or item_url>&episode&preferredLabel`.
   - **movie** → `GET /item?…&mediaType=movie` → `streams`, then NDJSON `POST /check-sources {streams, provider, preferredLabel}`.
   - mediaType = the target's, else "movie" when there is no episode label and no list (`media.rs:20-31`).
3. Each new line (de-duplicated by `directUrl`; a duplicate only fills in missing fields, `merge_missing`, `mod.rs:1898-1909`) is appended and the picker list refreshes.
4. Not started yet:
   - no preference → `begin(this)`;
   - label == preference → `begin(this)`;
   - else start a **2500 ms** timer (`PREFERENCE_WAIT_MS`), then `begin(0)`.
5. Stream finished and not started → begin(the preferred index, else 0). With no lines → give up "找不到可播放的片源", or the stream error's message. An offline error before the start → wait for the network.
6. While `waiting_more` (exhausted, stream still open), a newly arrived source is selected at once: notice "改用其他片源…", Manifest, `last_good`.

**Faults** (`mod.rs:582-699`):
- `on_fault(msg)`: ignored if already probing, offline or fatal. Otherwise notice "重新連線中…" and probe reachability.
- Reachability = online **and** `GET /health` got *any* HTTP answer (`media.rs:254-263`).
  - Reachable → `climb`.
  - Unreachable → `wait_for_network`: notice "網路連線中斷，恢復後自動繼續…"; probe after 2 s, doubling to a 30 s cap. Once the server answers: notice "重新連線中…" and restart the **same rung** at `last_good` (or reload sources if nothing had started).
  - A network outage never climbs the ladder.
- `climb` (`mod.rs:639-667`):

  | Step | Action |
  |---|---|
  | Retry | restart the same source and tier |
  | SwitchToRelay | notice "改用伺服器轉送…" |
  | NextSource | notice "改用其他片源…", Manifest |
  | Exhausted, stream still open | `waiting_more`, notice "尋找其他片源…" |
  | Exhausted, stream finished | give up |

- **Stall:** buffering with intent to play, started, and not fatal, offline, probing or waiting, with the position unchanged for **45 s** → `on_fault("STREAM_STALLED")` (`mod.rs:44, 1480-1491`).
- **Forgiveness:** 60 s of non-buffering playback after the last fault → `ladder.forgive()` (`mod.rs:45, 1471-1477`).
- **Give up** (`mod.rs:669-681`):
  - intent off, fatal = msg (the msg is only exposed as `data-error-code`);
  - notice, buffering, controls, picker, up-next and osd all off;
  - publish; focus 重試 after 30 ms.
- **重試** (`mod.rs:683-699`): clear fatal; notice "重新連線中…"; `ladder.select(0)`; with no sources → reload them; else `start_source(0, Manifest, last_good)`. **返回** → leave.

### 6.8 Progress reporting and resume
- **Body** (`media.rs:96-126`), `PUT /me/progress`:
  - `providerKey`, `mediaType` (resolved), `title`;
  - `posterUrl` (only `http(s)…`, else "");
  - `itemUrl`, `detailUrl` (= season_url or item_url), `seasonUrl`, `episodeLabel`, `sourceLabel`;
  - `positionSeconds` = round(ms/1000) capped at the duration; `durationSeconds` = round;
  - `event`;
  - `seasonLabel` **only when non-empty**.
  - Never send `progressPercent` or `isCompleted`.
- **Position** (`mod.rs:1508-1532`): `ended` → the duration; else the live player position if > 0 and no seek is pending; else the tracked position. **Nothing is sent while the duration is unknown (0).**
- **Cadence:**
  - `progress` every **15 s** while `status == playing`, checked by the 1 s tick against `last_progress_at`, which every report of any kind resets (`mod.rs:41, 1470, 1498-1500`);
  - `pause` on the `paused` state, on teardown (started and not ended), when the app is hidden (`ON_STOP`) and on the Exit key;
  - `ended` on stream end;
  - `switch` on a manual source change.
- Use a monotonic clock (`mod.rs:212-216`).
- **Resume:**
  - Detail passes `start_seconds = resume_position_seconds` = max(position − 30, 0), and **0 for a completed episode** (`tizen/core/src/resume.rs:95-103`); 從頭播放 passes 0.
  - Next/prev/auto-advance start at 0 (`mod.rs:238-245`).
  - The strip uses the episode's resume position.
  - A cast `play` uses floor(positionMs/1000) (`realtime.rs:525, 537`).

### 6.9 Cast-driver caption (`media.rs:143-167`)
- `friendly_device(name)`:
  - "StreamHub (android)" → "Android 手機"; "(ios)" → "iPhone"; "(web)" / "(browser)" → "瀏覽器"; "(tv)" → "電視"; any other kind → "另一部裝置";
  - empty or "Unknown device" → "另一部裝置";
  - otherwise the name as given.
- Caption = "由{sp}{name}{sp}控制". A space is used only where an ASCII alphanumeric touches the CJK frame: "由 Pixel 7 控制", "由瀏覽器控制".
- The badge shows while `controlled_by()` is fresh (12 s since the last command carrying `fromName`). It refreshes every tick and on each command (`mod.rs:1427, 1459-1462`).

---

## 7. Realtime and the cast receiver (`realtime.rs`)

- **Socket:** `wss://<base>/api/realtime`, **unversioned** (`config.rs:33-46`).
  - Connect whenever a session exists. On sign-out, disconnect and reset all state (`realtime.rs:171-185`).
  - A token refresh under a healthy socket does **not** reconnect (`:193-208`).
- **Auth:** on open send `{type:"auth", token}` (`:317-323`).
- **Pre-connect refresh:** if the token has < 60 s of its lifetime (exp − iat) left, measured from when *this client first saw it* on the monotonic clock, refresh first, once per attempt (`:43-45, 236-272, 278-290`).
- **Heartbeat:** `POST /auth/heartbeat` immediately when a session appears, then every **45 s** (`:40, 187-218`).
- **On `ready`:**
  - mark ready; reset attempts; store `sessionId`;
  - **send the last published playback state again**, or `{type:"playback", state:null}` when idle. The server forgets receivers on reconnect, and sending a frame at all is what lists the set;
  - if this is a **re-connect** after an earlier ready in the same sign-in, bump `library_version` so screens refetch (`:431-451`).
- **Frames:** `favorites` / `progress` → bump `library_version` (Home debounces 1 s). `command` → below. Anything else is ignored (`:452-456`).
- **Close codes** (`:67-80, 361-412`):
  - **4002** → reset attempts, `refresh()`, then connect. If the refresh fails offline → backoff.
  - **4003** → if the current token is the refused one, wait for a different token; else connect now.
  - **4001** and anything else → backoff `min(1000 << min(attempts,5), 30000)` ms (1, 2, 4, 8, 16, 30 s…), attempts++. A connection that reached ready resets attempts first.
- **Commands** (`:459-502`):
  - `action` ∈ pause | resume | stop | next | previous | seek(`positionMs`) | play. Anything else (including `fullscreen`) is **ignored entirely**.
  - A seek without `positionMs` is ignored.
  - If `fromName` is non-empty, record the driver (name, now) for the 12 s freshness window.
  - Transport commands go to the registered player's handler; with no player they are dropped.
  - **`play`:** build a PlayTarget from `command.playback` (`:507-540`):
    - requires `streamUrl`;
    - episodes = [prev?, episode, next?] built from `prevEpisodeLabel`/`nextEpisodeLabel`;
    - `season_url = episodeUrl`; `media_type` and `season_label` empty;
    - `source = {directUrl: streamUrl, sourceLabel: subtitle}`;
    - start = floor(positionMs/1000); `from_cast = true`.
    - If the Player is on top → **replace**, else **push**. This applies from any signed-in screen.
- **Player handling of commands** (`player/mod.rs:1426-1453`):
  - pause / resume;
  - seek clamped to ≤ dur − 1000 and ≥ 0 → `seek_to`;
  - stop → leave (no OSD);
  - next / previous;
  - then `show_osd`, and **publish again 60 ms later** (the echo).
- **Published state** (`player/mod.rs:1534-1558`):
  - `provider`, `itemUrl`, `title`, `subtitle` (the source label), `posterUrl`, `episodeLabel`;
  - `positionMs` (pending seek target ?: position); `durationMs`;
  - `paused = !intent_play` (intent, not engine state);
  - `buffering`, `hasNext`, `hasPrevious`, `controlledBy`.
- **Published when:**
  - on mount; on `playing` / `paused`; on every buffering change;
  - every 1 s tick while playing, or while intending to play and buffering;
  - on `seek_to`; on give-up; on ended; on the command echo;
  - `null` on teardown.
- Tizen **never withdraws** from the receiver list and has no "refuse remote control" UI. The current Android `TvCastReceiver.refuse()` (`android/tv/.../cast/TvCastReceiver.kt`) has no Tizen counterpart and must not be exposed.

---

## 8. Sounds (`tizen/app/js/sfx.js`, `sfx.rs`)

**The Android port must reproduce the identical synthesis parameters, priorities,
gaps, fades and player silence.** Synthesize the buffers at runtime. Do not ship
recorded files.

**Synthesis** (`sfx.js:76-115`). Rate = the output device rate (Tizen uses
`ctx.sampleRate`; Android: `AudioTrack.getNativeOutputSampleRate`). Each buffer
is mono, n = ceil(rate · dur) samples:
```
for i in 0..n:
  t      = i / n
  glide  = from + (to − from) · (1 − (1 − t)²)
  env    = (i < A ? i / A : 1) · exp(−(i / rate) / decay)      A = max(1, floor(rate·attack))
  tailN  = floor(rate · 0.003); if (n − i) < tailN: env *= (n − i) / tailN   // last 3 ms → 0
  for each partial k (mult_k, amp_k): phase_k += 2π · glide · mult_k / rate
  s[i] = Σ_k sin(phase_k) · amp_k · env
normalise: s /= max|s|
```
| Sound | dur | attack | decay | from → to Hz | partials (mult, amp) |
|---|---|---|---|---|---|
| move (tick) | 0.045 s | 0.002 | 0.012 | 1400 → 1320 | (1, 1), (2, 0.12) |
| edge (thud) | 0.09 | 0.003 | 0.03 | 190 → 130 | (1, 1), (2, 0.25) |
| select (rising pop) | 0.11 | 0.003 | 0.035 | 700 → 940 | (1, 1), (1.5, 0.3), (2, 0.12) |
| back (falling pop) | 0.11 | 0.003 | 0.035 | 900 → 620 | (1, 1), (1.5, 0.25) |

**Mixing and rules** (`sfx.js:27-38, 118-170`):
- Master gain **0.5**. Per sound:

  | Sound | Gain | Priority | Gap | Held gap |
  |---|---|---|---|---|
  | move | 0.11 | 1 | 70 ms | **120 ms**, and gain × **0.6** for held repeats |
  | edge | 0.30 | 2 | 260 ms | — |
  | select | 0.17 | 3 | 140 ms | — |
  | back | 0.15 | 3 | 140 ms | — |

- `play(name, repeat)` drops the sound (returns false) when:
  - sounds are disabled, the player is up, or the app is hidden;
  - the same sound was *accepted* less than its gap ago (use the held gap when repeat and one exists);
  - a voice is still sounding (`now < voice.end`) with **strictly higher** priority;
  - there is no audio output;
  - the context is suspended: resume it and drop this sound rather than play it late.
- **One voice:** a new sound cuts a still-sounding previous one with a linear gain ramp to 0 over **8 ms**, stopping 2 ms after that. It never layers.
- `voice.end` = start time + buffer duration. Record `last[name]` only on acceptance.
- Use wall-clock ms for gaps, as `Date.now()` does in the JS; a monotonic clock is fine.

**Player silence** (`sfx.js:172-185`, `player/mod.rs:222-236`):
- `setPlayer(true)` on player mount: from then on every play is dropped. The output is suspended after the current voice ends + 30 ms, so the OK that opened the player finishes its pop.
- `setPlayer(false)` on teardown resumes the output.
- App hidden → silence the voice and suspend; visible again (and not in the player) → resume (`sfx.js:194-199`).

**Enable flag:** `"streamhub.sfx"`; `"0"` = off, a missing value = **on** (`sfx.js:49-51, 187-192`). Disabling also silences the current voice. Use SharedPreferences with the same key and semantics.

**Where sounds fire** (`focus.rs`):

| Event | Sound | Source |
|---|---|---|
| Focus moved by an arrow / CH / replay | `move` (held → `play_repeat`) | `focus.rs:812-818` |
| A screen handler consumed an arrow | `move` / `play_repeat(move)` | `:1040-1041` |
| A screen handler consumed Enter (non-repeat) | `select` | `:1042` |
| Edge (bump shown, first per hold) | `edge` | `:801-811` |
| A buffered Down while loading | none | `:799-800` |
| OK keydown on a usable element (including long-press elements) | `select` | `:1076-1080` |
| Play key on `playable` / red key on `favable` | `select` | `:1127, 1134` |
| Any Back | `back`, before the handler | `:950` |
| 音效 switched on | `select` | `home.rs:573-576` |

Implementation advice: one low-latency `AudioTrack` (`MODE_STREAM`,
`PERFORMANCE_MODE_LOW_LATENCY`) fed by a tiny render loop that plays the single
voice and applies the 8 ms cut ramp. `SoundPool` cannot do the ramp or the
priority logic. Every entry point must swallow errors: a sound is decoration.

---

## 9. Data and API per screen, mapped onto `:core`

`StreamHubApi` (`android/core/src/main/kotlin/com/streamhub/core/net/StreamHubApi.kt`)
sends `X-StreamHub-Client: tv` and pins `/api/v1` (`ApiConfig.kt`). Refresh on
401 is single-flight (`TokenRefresher.kt`, `TokenAuthenticator.kt`).

| Screen | Tizen call | `:core` function |
|---|---|---|
| Login | `POST /auth/device/start` | `api.startDevicePairing()` (`:157`) |
| | `POST /auth/device/poll` | `api.pollDevicePairing(deviceCode)` (`:174`) → `DevicePairingStatus` |
| | `POST /auth/login` | `api.login(login, password)` (`:139`); it stores the session itself |
| Home | `/me/continue-watching`, `/me/favorites`, `/me/history` | `continueWatching()` (`:471`), `favorites()` (`:451`), `history()` (`:468`) |
| | `/me/providers?all=true` (display names) | `providers(includeUnavailable = true)` (`:298`) |
| | `DELETE /me/favorites/{id}` | `removeFavorite(id)` (`:462`) |
| | `POST /me/favorites` | `addFavorite(NewFavorite)` (`:454`) |
| | `DELETE /me/progress {scope:"title",…}` | `deleteProgress(ProgressDelete(providerKey, itemUrl, scope="title", title))` (`:532`) |
| | `POST /auth/logout` | `logout()` (`:230`). It clears the store **before** the request; Tizen clears after. The visible result is the same |
| | `/api/poster?target=` | `posterUrl(target)` (`:446`) + Coil on `authenticatedClient` (`:128`) |
| Search | `/me/providers` | `providers()` |
| | `/search?q&provider=<key>` | `search(q, setOf(key))` (`:321`), with a 20 s `withTimeout` |
| Detail | `/me/progress?providerKey&itemUrl` | `progress(providerKey, itemUrl)` (`:474`) |
| | `/item?provider&url&title&mediaType&posterUrl` | `item(...)` (`:329`) → `ItemDetail` |
| | `/episodes?provider&sourceUrl` | `episodes(provider, sourceUrl)` (`:345`) |
| | favourites add / remove | as on Home |
| Player | `/me/source-preference` GET / POST | `sourcePreference(providerKey, title, mediaType)` (`:497`) / `rememberSourcePreference(...)` (`:512`) |
| | NDJSON `/sources` | `sources(provider, sourceUrl, episode, preferredLabel)` (`:358`) → `Flow<Source>` |
| | movie `/item` + NDJSON `/check-sources` | `item(...)` → `ItemDetail.Movie.streams` → `checkSources(provider, streams, preferredLabel)` (`:392`) |
| | manifest / relay URLs | `manifestUrl(directUrl)` (`:425`), `streamUrl(directUrl)` (`:436`) |
| | `PUT /me/progress` | `putProgress(ProgressUpdate)` (`:482`) |
| | strip progress, next season | `progress(...)`, `item(...)`, `episodes(...)` |
| | reachability `/health` | `health()` (`:306`). An `IOException` means offline; a `StreamHubException` means reachable |
| App | `POST /auth/heartbeat` every 45 s | `heartbeat()` (`:256`) |
| | realtime | `RealtimeClient.events()` (`RealtimeClient.kt:116`), `publishPlayback(state)` (`:95`), `connected`, `sessionId`; `CastPlaybackState` / `CastCommand` / `CastPlayRequest` (`model/Cast.kt`) |
| Resume | `tizen/core/src/resume.rs` | `ResumeRules` (`resume/ResumeRules.kt`): `progressKey`, `progressMap`, `resumeSeason`, `resumeEpisode`, `resumePositionSeconds`, `shouldOfferUpNext`, `upNext`, `previousEpisode` |
| Ladder | `player/ladder.rs` | `RecoveryLadder` (`playback/RecoveryLadder.kt`) has only RETRY / SWITCH_TO_RELAY / GIVE_UP. **The NextSource rung is missing**; see below |
| Session | localStorage `streamhub.session` | `EncryptedSessionStore` (`net/SessionStore.kt:41`) |
| Pairing | — | `UserCode` (`model/DevicePairing.kt:73`) is not needed: the TV only displays the server's text |

**What `:core` lacks or gets differently.** Each item needs a `:core` change or
a `:tv`-side workaround.
1. **Unavailable marks.** `Favorite`, `ContinueItem` and `WatchHistoryEntry` (`model/Library.kt:11, 71, 137`) have no `isUnavailable` / `unavailableReason`, so the 已下架 / 已無集數 badges cannot render. `ContinueItem` also has no `id`; Tizen falls back to a composite id, which is fine.
2. **`SearchItem.year`** is missing (`model/Media.kt:6-13`). The search card subtitle needs it.
3. **`ItemDetail` discrimination** differs (`StreamHubApi.kt:586-618`).
   - core: `seasons` key → Seasons, even if empty; `streams` → Movie; else Episodes(possibly empty).
   - Tizen: seasons **with a non-empty url** → hub; else the `episodes` key present → Series; else **Movie** (`detail.rs:216-251`).
   - `ItemDetail` also lacks `description`/`summary`, `mediaType` and `detailUrl`.
   - `Episodes.sourceUrl` = `seasonUrl ?: detailUrl`, but Tizen keys progress on **`seasonUrl` only** (`detail.rs:237-238`). Falling back to `detailUrl` would break resume against other clients.
   - Expose the raw fields, or parse the body in `:tv`.
4. **`Source.adSeconds: Int` / `durationSeconds: Int?`** (`model/Media.kt:92-93`). Tizen reads them as floats and rounds them; its test line carries `"adSeconds": 31.6` (`player/mod.rs:1944-1946`). A fractional value would fail kotlinx decoding of the whole NDJSON line and kill the flow. Make them `Double`.
5. **Refresh sign-out policy.** `TokenRefresher.refresh` clears the session on *any* null renewal (`TokenRefresher.kt:42`), and `refreshBlocking` returns null on network errors, 429 and 5xx (`StreamHubApi.kt:279-292`). Tizen signs out only on **400/401/403** from `/auth/refresh` (`api.rs:184-202`). Without the fix a Wi-Fi blip logs the TV out.
6. **Re-announce on reconnect.** `RealtimeClient` does not resend the last playback state on `ready`. The current `TvCastReceiver` publishes **null** on every connect (`TvCastReceiver.kt:98`), which blanks a playing TV on the phones until the next tick. Tizen resends the last state (`realtime.rs:433-444`). Keep the last published state in `:tv` and publish it on each `connected → true`.
7. **Reconnect → library refetch.** `:core` emits no "reconnected" event. Derive it from `connected` going false → true after an earlier true, and bump the library version (`realtime.rs:446-450`).
8. **Pre-connect token refresh** (§7) is absent in `RealtimeClient`. The 4002 path does exist (`RealtimeClient.kt:142-148`).
9. **Driver freshness.** `TvCastReceiver.controlledBy` (`:84`) never expires. Tizen uses 12 s (`realtime.rs:42, 577-585`).
10. **Error copy.** `:core` messages are English (`login()` role refusal: "This account cannot play anything…", `StreamHubApi.kt:147`). Map them with `friendly_error` (§3.7). Map the role refusal *explicitly* to "管理員帳號無法在電視上觀看，請使用一般觀眾帳號。"; rule 5 would not catch it.
11. **Not in `:core` at all:**
    - `entry_season` / `entry_episode` (`tizen/core/src/entry.rs`);
    - `episode_caption` / `season_parts` (`tizen/core/src/labels.rs`);
    - `friendly_error`, `friendly_device` / `driver_caption`;
    - the progress body's `posterUrl` absolute-or-empty rule (`media.rs:130-138`);
    - `SourceRow`.
    Port them into `:tv` or `:core` with their Rust tests.
12. **Recovery ladder** needs the NextSource rung and `select(i)` (`ladder.rs:46-72`).
13. QR **encoding** is not in `:core` (`QrDecoder` only decodes). The existing `android/tv/.../ui/QrCode.kt` can be kept.

---

## 10. Parity checklist

1. The UI is laid out on a 960×540 dp canvas and looks identical on 720p, 1080p and 4K. System font scale has no effect.
2. Cold start with no session shows Login in pairing mode. Focus is on "改用帳號密碼登入". Both blobs drift slowly.
3. The pairing code and QR appear. The QR encodes `verificationUrlComplete`. `deviceCode` appears nowhere.
4. The countdown text "m:ss 後更新代碼" ticks every second. The bar shrinks linearly. A new code arrives by itself at expiry.
5. With the server down: QR area "正在等待伺服器…", retried every 5 s.
6. Approving on a phone signs in while the password card is open.
7. Approving with an admin account shows "管理員帳號無法在電視上觀看，請使用一般觀眾帳號。" and issues a new code.
8. Password mode: Back returns to pairing with focus on 改用帳號密碼登入. Back again opens the exit prompt.
9. An empty username or password shows "請輸入帳號與密碼". Wrong credentials show "帳號或密碼不正確". The button reads "登入中…" while busy.
10. Home first load shows 8 shimmering skeletons per rail. Focus is **not** parked on 搜尋 while loading.
11. Pressing Right during the first load focuses 音效 (the key wakes 搜尋, then replays). The late load does not steal focus afterwards.
12. After load, focus is on the first card of the first non-empty rail. The hero shows that title, meta, progress and "按 OK 繼續播放" / "按 OK 播放下一集".
13. Moving between cards restarts the hero text fade-up. The backdrop cross-fades 200 ms after focus settles. Scrubbing fast does not swap posters per card.
14. Focused card: scale 1.08, lift 3 dp, white ring, shadow, a single sheen sweep. Title and subtitle brighten.
15. A rail keeps the focused card at x = 48 dp until its end. The last card stays fully visible.
16. Down moves to the next row. The column slides so that row's top sits at the rows viewport top. Past rows vanish; others are at .72.
17. Entering the rows hides the top bar (fade + 14 dp up) and raises the hero by 12 dp. Up into the top bar brings it back.
18. Holding Right: 1 step per 160 ms, then per 80 ms after 0.4 s, 3 cards per step after 1.5 s. Releasing stops exactly where focus is.
19. Holding Down steps at most once per 220 ms.
20. At a rail's start, Left bumps 6 dp with one thud. Holding Left against the wall thuds once. Nothing wraps.
21. Right from the end of one rail never jumps diagonally into another rail.
22. Entering a rail from above returns to the card last focused in it.
23. CH−/CH+ (and PgDn/PgUp) move a whole row.
24. Long-pressing OK (700 ms) on a Continue card opens a menu with "從清單移除" / "查看詳情", focus on the first. The release does not open Detail.
25. Long-pressing OK on a Favourite opens "取消收藏" / "查看詳情". A History card opens Detail immediately on OK down.
26. 從清單移除 removes the title from 繼續觀看 with the toast "已從繼續觀看移除：{title}". Focus lands somewhere valid.
27. Red key on a Favourite removes it ("已取消收藏：…"). On other cards it toggles ("已加入收藏：…" / "已取消收藏：…"). The toast lasts about 2.2 s.
28. The Play key on a card opens the title and starts the player at the resume point. Back from the player returns to Home.
29. Back on Home from deep in the rails returns to the first card with every rail at rest. The next Back opens "離開 StreamHub？" with focus on 取消.
30. Back with the exit prompt open closes it and focus returns. 離開 quits.
31. 登出 opens the confirm modal focused on 取消. Back cancels to 登出. Confirming returns to Login.
32. 音效 toggles the label 開/關 and the icon. Turning it on plays the pop. The choice survives a restart.
33. A phone finishing an episode updates Home about 1 s later without input. A socket reconnect also refreshes.
34. A failed Home rail shows a red caption and retries after 2, 4, 8, 15, 30 s.
35. With everything empty, the column shows "還沒有內容。按「搜尋」找點東西來看吧。".
36. Search with no history autofocuses the field and opens the IME. Recent searches (≤ 8, most recent first) show as chips when there are no results.
37. In the field, Left/Right move the caret and leave only from the ends. OK or IME Search submits. Backspace edits.
38. Searching shows "搜尋中…", then one rail per allowed provider in server order. Each shows skeletons until it answers and then its count.
39. A provider error shows as a collapsed row with a red caption. No hits shows "沒有結果". A 20 s timeout shows "搜尋逾時".
40. Holding Right through 240+ results never hits a wall (30-card pages grow ahead of focus).
41. Up from the first result row always lands on the field. Back from the results returns to the field with rails at rest. Back again leaves Search.
42. Back from Detail to Search restores the same card, even past the first page.
43. Detail shows the loading bar, then the title, chips (provider · 影集/電影 · 第 N 季 · N 集) and the description.
44. Play is unfocusable and reads "載入中…" until the episode is resolved, then gains focus with "繼續播放 第X集 · 12:34" or "播放 第X集".
45. 從頭播放 appears only with a resume position. The heart fills with a pop on 加入收藏 → 已收藏.
46. Entering the episode rail lands on the current episode (`is-selected`) the first time. Watched tiles show the check and "已看完". Partial tiles show "剩 N 分鐘" and a bar.
47. The season pills show 「第 N 季」 with a sub-line. The current season is tinted red with a ring.
48. Back from the episode rail returns to Play with rails reset. Back again leaves Detail.
49. Play/PlayPause anywhere on Detail starts playback.
50. A title the provider dropped shows "已下架" and "搜尋「{title}」". That button opens Search prefilled and searching.
51. Player: the chrome shows "尋找片源中…" and the busy line on the bar until playing, then hides after 4 s.
52. Left/Right taps accumulate (+10 s each) into one seek 400 ms after the last tap. The seek pill follows the thumb and the track thickens.
53. Holding Right scrubs 10 s per 120 ms tick, 30 s after 1 s, 60 s after 3 s. Release seeks once. Back mid-scrub cancels without seeking.
54. An OK tap toggles pause with the centre glyph pop. Paused keeps the chrome up indefinitely.
55. Holding OK 700 ms opens "選擇片源" focused on the playing source, showing 免廣告 and runtime. Selecting another resumes at the same position.
56. Up shows the pills with focus on 播放/暫停. Back hides them. Back again leaves the player.
57. Down opens "選集" focused on the current episode, showing 播放中 / 已看完 / 已看 N%. OK on another episode plays it from its resume point.
58. In the last 2 minutes the up-next card slides in with a draining ring. OK plays now. Back or Down dismisses it. At the end the next episode starts unless dismissed.
59. Killing the manifest path recovers on the relay ("改用伺服器轉送…"), then the next source ("改用其他片源…").
60. With every source failing: "無法播放" with 重試 focused. 返回 leaves.
61. Pulling the network shows "網路連線中斷，恢復後自動繼續…" and resumes at the same position on reconnect without climbing the ladder.
62. Progress reaches the server every 15 s while playing, and on pause, leave, app background and the end. A relaunch resumes 30 s before the stop point.
63. ⏪/⏩ seek 30 s. ⏮/⏭ change episode. ■ leaves.
64. No interface sounds play while the player is up. The OK that opened the player finishes its pop.
65. A phone can cast to the idle TV, which appears in its picker. While playing, its remote controls pause, seek, next and stop. The phone sees state changes within about a second.
66. "由 Android 手機控制" (and similar) appears top-right for 12 s after a phone command.
67. A socket drop or 4002 reconnects without signing out. The TV reappears in the phone's picker with its current state, not idle.
68. Sounds: a tick on move, a pop on OK, a falling pop on Back, a thud at an edge. They never layer or rattle when held. Spacing: 70/120 ms moves, 260 ms edge, 140 ms OK/Back.
69. Screens enter with fade + 1.015 → 1 scale (420 ms). Modals fade the backdrop and rise 12 dp (420 ms).
