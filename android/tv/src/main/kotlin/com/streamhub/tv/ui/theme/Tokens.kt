package com.streamhub.tv.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Design tokens, ported from the Tizen client's `:root` (tizen/app/assets/styles.css:11-46)
 * and `.vp-root` (player.css:22-38). See android/tv/PARITY.md §2.
 *
 * Units: everything is on the fixed 960×540 dp canvas (TvCanvas), where
 * 1 CSS px = 0.5 dp and a CSS font-size of N px is N/2 sp. Names follow the CSS
 * variables: `--surface-2` is `TvColors.Surface2`, `--t-slow` is `TvMotion.TSlow`.
 */

/** `--*` colours, plus the literal colours individual rules use (PARITY §2.1). */
object TvColors {
    /** `--bg`: page, scrim base, letterbox. */
    val Bg = Color(0xFF08090C)
    /** `--surface`: cards, card art, skeletons. */
    val Surface = Color(0xFF15171C)
    /** `--surface-2`: buttons at rest, modals, raised. */
    val Surface2 = Color(0xFF1F222A)
    /** `--text`. */
    val Text = Color(0xFFF5F5F7)
    /** `--text-2`: rgba(245,245,247,.68). */
    val Text2 = Color(245, 245, 247).copy(alpha = 0.68f)
    /** `--text-3`: rgba(245,245,247,.44). */
    val Text3 = Color(245, 245, 247).copy(alpha = 0.44f)
    /** `--accent`: brand, progress fills, primary button at rest. Never a focus colour. */
    val Accent = Color(0xFFE5383B)
    /** `--focus`: every focus state. */
    val Focus = Color(0xFFFFFFFF)
    /** `--on-focus`: text on the white focus fill. */
    val OnFocus = Color(0xFF0B0C0F)
    /** `--green`: 下一集 chip, episode check. */
    val Green = Color(0xFF46D369)
    /** `--amber`: warning badge, 已下架 title. */
    val Amber = Color(0xFFF5A623)
    /** `--danger`: error text. */
    val Danger = Color(0xFFFF8A80)
    /** `--tv-bg`. */
    val TvBg = Bg

    // ── literals (styles.css / player.css) ────────────────────────────────
    /** Text on the amber badge (`styles.css:211`). */
    val OnAmber = Color(0xFF15110A)
    /** `.tv-btn-danger` background rgba(229,56,59,.18). */
    val DangerButtonBg = Color(229, 56, 59).copy(alpha = 0.18f)
    /** `.tv-btn-danger` text. */
    val DangerButtonText = Color(0xFFFFB3AE)
    /** `.tv-chip` background rgba(8,9,12,.82). */
    val ChipBg = Color(8, 9, 12).copy(alpha = 0.82f)
    /** Card fallback / up-next gradient, top. */
    val CardFallbackTop = Color(0xFF23262F)
    /** Card fallback / up-next gradient, bottom (= Surface). */
    val CardFallbackBottom = Color(0xFF15171C)
    /** The fallback initial: rgba(245,245,247,.16). */
    val CardFallbackGlyph = Color(245, 245, 247).copy(alpha = 0.16f)
    /** Login blob B rgba(84,98,220,.18). */
    val LoginBlobB = Color(84, 98, 220).copy(alpha = 0.18f)
    /** 免廣告 badge text / background. */
    val AdFreeText = Color(0xFF4ADE80)
    val AdFreeBg = Color(46, 204, 113).copy(alpha = 0.16f)
    /** 免廣告 badge on a focused (white) row. */
    val AdFreeTextFocused = Color(0xFF15803D)
    val AdFreeBgFocused = Color(22, 163, 74).copy(alpha = 0.16f)
    /** The episode check on a focused tile (`styles.css:753`). */
    val EpisodeCheckFocused = Color(0xFF1A8F3C)
    /** QR wait text (`styles.css:856`). */
    val QrWait = Color(0xFF333333)
    /** `.tv-input` border at rest rgba(255,255,255,.12). */
    val InputBorder = Color.White.copy(alpha = 0.12f)
    /** `.tv-input` background at rest rgba(21,23,28,.9). */
    val InputBg = Color(21, 23, 28).copy(alpha = 0.9f)
    /** Progress track rgba(255,255,255,.28). */
    val ProgressTrack = Color.White.copy(alpha = 0.28f)
    /** Modal backdrop rgba(0,0,0,.6). */
    val ModalBackdrop = Color.Black.copy(alpha = 0.6f)
    /** Modal border rgba(255,255,255,.06). */
    val ModalBorder = Color.White.copy(alpha = 0.06f)
    /** Toast border rgba(255,255,255,.08). */
    val ToastBorder = Color.White.copy(alpha = 0.08f)
    /** Sheen peak rgba(255,255,255,.22). */
    val Sheen = Color.White.copy(alpha = 0.22f)
    /** Skeleton shimmer peak rgba(255,255,255,.16). */
    val Shimmer = Color.White.copy(alpha = 0.16f)

    /** `rgba(8,9,12,a)`: every scrim stop is the background at some alpha. */
    fun bg(alpha: Float): Color = Bg.copy(alpha = alpha)
}

/** Shapes and lengths (PARITY §2.3). */
object TvShapes {
    /** `--r-card` 14px. */
    val RCard: Dp = 7.dp
    /** `--r-panel` 20px. */
    val RPanel: Dp = 10.dp
    val Card = RoundedCornerShape(RCard)
    val Panel = RoundedCornerShape(RPanel)
    /** `border-radius: 999px`. */
    val Pill = RoundedCornerShape(50)
}

/** Spacing and the safe area (PARITY §2.3). */
object TvSpace {
    /** `--safe-x` / `--vp-safe-x` 96px. */
    val SafeX: Dp = 48.dp
    /** `--safe-y` / `--vp-safe-y` 64px. */
    val SafeY: Dp = 32.dp
    /** `--rail-gap` 28px. */
    val RailGap: Dp = 14.dp
    /** `--row-gap` 56px. Rows use `calc(56px - 28px)` = 14 dp as padding-bottom. */
    val RowGap: Dp = 28.dp
    /** `.tv-row` padding-bottom: calc(var(--row-gap) - 28px). */
    val RowPaddingBottom: Dp = 14.dp

    /** The fixed canvas every screen lays out on. */
    val CanvasWidth: Dp = 960.dp
    val CanvasHeight: Dp = 540.dp
}

/**
 * Motion (PARITY §2.4). Durations in ms. Animate only transform and alpha
 * (`graphicsLayer`); never size, padding, shadow or blur.
 */
object TvMotion {
    /** `--ease` cubic-bezier(.2,.8,.2,1). */
    val Ease: Easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    /** `--ease-in-out` / `--vp-ease-io` cubic-bezier(.45,0,.2,1). */
    val EaseInOut: Easing = CubicBezierEasing(0.45f, 0f, 0.2f, 1f)
    /** CSS keyword `ease-in-out` cubic-bezier(.42,0,.58,1) (login blobs only). */
    val CssEaseInOut: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
    /** CSS keyword `ease` cubic-bezier(.25,.1,.25,1), the default timing function. */
    val CssEase: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
    val Linear: Easing = LinearEasing

    /** `--t-fast` 140 ms: press / button scale. */
    const val TFast = 140
    /** `--t` 240 ms: focus, controls, rails. */
    const val T = 240
    /** `--t-slow` 420 ms: backdrop cross-fade, screen enter, modals. */
    const val TSlow = 420

    /** Edge bump: 160 ms `--ease`, peak 6 dp at 45 %. */
    const val BumpMs = 160
    val BumpDistance: Dp = 6.dp
    const val BumpPeakAt = 0.45f

    /** Card sheen: 650 ms `--ease` after a 60 ms delay. */
    const val SheenMs = 650
    const val SheenDelayMs = 60
    /** Skeleton shimmer: 1.2 s linear, infinite. */
    const val ShimmerMs = 1200
    /** Toast auto-hide after the last `toast()`. */
    const val ToastMs = 2200

    fun <T> tween(ms: Int = T, easing: Easing = Ease, delayMs: Int = 0): TweenSpec<T> =
        androidx.compose.animation.core.tween(ms, delayMs, easing)

    /** `transition: transform var(--t) var(--ease)`. */
    fun <T> focus(): TweenSpec<T> = tween(T, Ease)
    /** `transition: transform var(--t-fast) var(--ease)` (pill buttons). */
    fun <T> fast(): TweenSpec<T> = tween(TFast, Ease)
    /** `var(--t-slow) var(--ease)` (screen enter, modal rise). */
    fun <T> slow(): TweenSpec<T> = tween(TSlow, Ease)

    /**
     * Evaluate a CSS `@keyframes` track at linear progress [p] (0..1).
     *
     * CSS applies the timing function per keyframe interval, not over the whole
     * animation, so `bump` (0% → 45% → 100%, `--ease`) eases into the peak and
     * eases out of it again. [stops] are (offset, value) pairs sorted by offset,
     * first at 0 and last at 1.
     */
    fun keyframes(p: Float, easing: Easing, vararg stops: Pair<Float, Float>): Float {
        val t = p.coerceIn(0f, 1f)
        for (i in 0 until stops.size - 1) {
            val (a, va) = stops[i]
            val (b, vb) = stops[i + 1]
            if (t <= b || i == stops.size - 2) {
                val local = if (b > a) ((t - a) / (b - a)).coerceIn(0f, 1f) else 1f
                return va + (vb - va) * easing.transform(local)
            }
        }
        return stops.last().second
    }

    /** The bump's offset fraction (0 → 1 at 45 % → 0), for progress [p]. */
    fun bump(p: Float): Float = keyframes(p, Ease, 0f to 0f, BumpPeakAt to 1f, 1f to 0f)

    /** `tv-pop` (the heart): scale .6 → 1.2 at 60 % → 1, over 240 ms `--ease`. */
    fun pop(p: Float): Float = keyframes(p, Ease, 0f to 0.6f, 0.6f to 1.2f, 1f to 1f)

    /** `tv-sheen`: translateX −170 % → +260 % of the strip's own width. */
    fun sheenX(p: Float): Float = keyframes(p, Ease, 0f to -1.7f, 1f to 2.6f)

    /** `tv-enter`: opacity 0 → 1 and scale 1.015 → 1, both `--ease`. */
    const val EnterScale = 1.015f

    /** `tv-rise` (modal): translateY 24px → 0 = 12 dp. */
    val RiseDistance: Dp = 12.dp
    /** `tv-fade-up`: translateY 12px → 0 = 6 dp. */
    val FadeUpDistance: Dp = 6.dp
    /** Toast enter: translateY 16px → 0 = 8 dp. */
    val ToastRiseDistance: Dp = 8.dp
}

/**
 * A CSS `box-shadow` (offset y, blur radius, colour). Drawn by
 * [com.streamhub.tv.ui.theme.tvShadow] as a pre-rendered bitmap whose alpha is
 * the only thing that changes (DESIGN.md "Performance rules").
 */
data class TvShadowSpec(val offsetY: Dp, val blur: Dp, val color: Color, val spread: Dp = 0.dp)

object TvShadows {
    /** `.tv-btn::after` 0 12px 32px rgba(0,0,0,.55). */
    val Button = TvShadowSpec(6.dp, 16.dp, Color.Black.copy(alpha = 0.55f))
    /** `.tv-card::before` 0 26px 50px rgba(0,0,0,.75). */
    val Card = TvShadowSpec(13.dp, 25.dp, Color.Black.copy(alpha = 0.75f))
    /** `.tv-modal::before` 0 40px 90px rgba(0,0,0,.6). Static. */
    val Modal = TvShadowSpec(20.dp, 45.dp, Color.Black.copy(alpha = 0.6f))
}

/** Z-order constants (PARITY §2.5), for `Modifier.zIndex`. */
object TvZ {
    const val Hero = 1f
    const val FocusedInRail = 2f
    const val TopBar = 3f
    const val ModalBackdrop = 100f
    const val Toast = 120f
}

/** A linear easing tween, for shimmer / countdown / ring / scrub fill. */
fun <T> linearTween(ms: Int): TweenSpec<T> = tween(ms, 0, LinearEasing)
