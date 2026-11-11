# StreamHub on Tizen — design language

A 10-foot UI in the family of the big streaming apps' TV clients: content
first, chrome that gets out of the way, focus you can see from the sofa, and
motion that explains where you are. It borrows *interaction patterns* only —
no other product's logo, wordmark, colours, artwork or copy.

Target: Samsung Smart Monitor M7 (Tizen 9, Chromium M120), a weak GPU, a 4K
panel driven at a 1920×1080 layout. Every rule below has to hold on that.

## Performance rules (non-negotiable)

- Animate **only `transform` and `opacity`**. Never animate width, height,
  top/left, margin, box-shadow, filter or background-position.
- **No `backdrop-filter`, no `filter: blur()`** at runtime. Fake depth with
  gradients and pre-dimmed layers.
- A focus shadow is a pseudo-element whose **opacity** animates, not a
  `box-shadow` transition.
- `will-change: transform` only on things that actually move (rail tracks,
  the row column, the focused card); never on every card.
- Hero/backdrop images: one `<img>` per layer, `object-fit: cover`, two
  layers cross-fading. Never more than two backdrop images in the DOM.
- Keep DOM small: rails render what they have; no hidden duplicates.

## Tokens (CSS custom properties on `:root`)

Colour
- `--bg: #08090c` page; `--surface: #15171c` cards/controls; `--surface-2: #1f222a` raised.
- `--text: #f5f5f7`; `--text-2: rgba(245,245,247,.68)`; `--text-3: rgba(245,245,247,.44)`.
- `--accent: #e5383b` StreamHub red — brand mark, progress fills, primary
  button **at rest**. Never the focus indicator.
- `--focus: #ffffff` — focus is always white (ring, filled button).
- `--scrim: linear-gradient(...)` hero scrims: left-to-right `rgba(8,9,12,.95) → 0` over
  the left 55%, bottom-to-top `--bg → 0` over the bottom 60%.

Type (system CJK first: `"SamsungOneUI", "TizenSans", "Noto Sans CJK TC", "Noto Sans TC", "PingFang TC", sans-serif`)
- Display 72/1.05 weight 800 (hero title); Title 44/1.15 700; Row heading 32/1.2 650;
  Body 28/1.45 400; Meta 24/1.3 500 `--text-2`; Caption 20 `--text-3`.
- Never below 20px. Numbers in `font-variant-numeric: tabular-nums` where they tick.

Shape & space
- Safe area: 96px left/right, 64px top/bottom (5% of 1920/1080, rounded).
- Radius: cards 14px, buttons 999px (pills), panels 20px.
- Rail gap 28px; row gap 56px.

Motion
- `--ease: cubic-bezier(.2,.8,.2,1)` (decelerate) for anything settling;
  `--ease-in-out: cubic-bezier(.45,0,.2,1)` for cross-fades.
- `--t-fast: 140ms` (press/feedback), `--t: 240ms` (focus, controls),
  `--t-slow: 420ms` (backdrop cross-fade, screen enter).
- Key repeat must stay responsive: a rail slide triggered while one is in
  flight restarts from the current transform, never queues.

## Patterns

**Focus (cards).** Focused card: `transform: scale(1.08) translateY(-6px)`,
2px inner white ring (pseudo-element, opacity), a soft drop shadow
(pseudo-element, opacity), and a one-shot specular sheen — a diagonal white
gradient strip that sweeps across once on focus (transform on a
pseudo-element, ~600ms, `--ease`). Title/meta under the card brighten from
`--text-2` to `--text`. Unfocused rows sit at opacity .72; the focused row
at 1.

**Focus (buttons).** Pills. At rest: `--surface-2` with `--text`; primary at
rest `--accent`. Focused: **white fill, near-black text**, scale 1.06. This
is the clearest focus state on a TV and it never relies on colour alone.

**Rails.** Horizontal track moves by `transform: translateX()` so the
focused card stays in a fixed focus column at the left safe edge (until the
end of the rail, where it stops so the last card is fully visible).
Vertical: the column of rows moves by `translateY()` so the focused row's
top sits at a fixed line (~52% of the screen on Home, under the hero).
`scrollIntoView` is not used for rails.

**Home.** Full-bleed backdrop of the focused item (its poster, cover-cropped,
positioned right, scrims on the left and bottom), cross-fading on focus change
after a 200ms debounce so fast scrubbing doesn't thrash. Over it, top-left:
title (Display), a meta line (episode · time left / provider), a progress
bar for continue items. The top bar (wordmark left, 搜尋 / 登出 right) is
visible while focus is in it and slides up + fades out when focus moves into
the rails; it comes back when focus returns up.

**Detail.** Same backdrop treatment, larger and darker. Title (Display),
meta chips (provider · 影集/電影 · N 集), primary **播放 / 繼續播放 第X集 · 12:34**
pill, secondary pills (從頭播放, 加入收藏 with a heart glyph that fills).
Seasons as a segmented row of pills; episodes as a rail of wide tiles with
the episode label large, a thin progress bar along the bottom, a check for
finished. The page is one vertical column moved by translateY like Home.

**Search.** Input as a large pill at the top; while typing nothing else
moves. Results: one rail per provider with the provider name as the row
heading and a count; loading rows show **skeleton cards** with a shimmer
(a gradient pseudo-element translating left→right, 1.2s loop, opacity .5);
empty/error rows collapse to a single caption line.

**Login.** Calm full-screen gradient (two large soft radial blobs drifting
very slowly by transform, 20s+ loops, low contrast). Pairing panel is the
hero: big QR on white, the code in large tracked monospace digits, a short
instruction. The credentials form is the quieter secondary column.

**Screen transitions.** Entering screen: opacity 0→1 and scale 1.015→1 over
`--t-slow` `--ease`. Leaving is instant (the next one fading in covers it).
Overlays/modals (exit prompt, sign-out confirm): backdrop fades to
rgba(0,0,0,.6); the panel rises 24px and fades in.

**Player.** Video is everything. Chrome = a bottom gradient and a bar that
fade in (`--t`) on any key and out after 4s (not while paused). Title and
episode above the bar; scrubber 6px, grows to 10px while seeking, with the
seek-target time in a pill that follows the thumb; elapsed / remaining in
tabular numerals. Control pills appear only when Up is pressed (same pill
focus rule). Buffering: a thin indeterminate line on the bar, not a big
spinner over the picture. Up-next: a card slides in from the right
(translateX + opacity) with the next episode's title and a circular
countdown (SVG stroke-dashoffset), OK plays now, Back dismisses.
Error panel: centered card, same modal motion.

## Hooks

Every `data-screen`, `data-action`, `data-focusable`, `data-focus-group`,
`data-provider`, `data-overlay`, `data-user-code`, `data-position`,
`data-controls` hook the QA suite uses must survive any redesign.
