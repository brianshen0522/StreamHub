package com.streamhub.tv.ui.theme

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * CSS `linear-gradient(<angle>deg, stops…)` as a Compose brush.
 *
 * CSS angles point *towards* the end: 0deg is to top, 90deg to right, 180deg to
 * bottom. The gradient line passes through the box's centre and is long enough
 * for the corners to land exactly on the first and last stop
 * (`|w·sin a| + |h·cos a|`), which is what makes a 160° card fallback or a 100°
 * sheen match the browser rather than merely resemble it.
 */
fun cssLinearGradient(angleDeg: Float, vararg stops: Pair<Float, Color>): Brush = object : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val a = Math.toRadians(angleDeg.toDouble())
        val dx = sin(a).toFloat()
        val dy = -cos(a).toFloat()
        val len = abs(size.width * dx) + abs(size.height * dy)
        val c = Offset(size.width / 2f, size.height / 2f)
        val half = Offset(dx * len / 2f, dy * len / 2f)
        return LinearGradientShader(
            from = c - half,
            to = c + half,
            colors = stops.map { it.second },
            colorStops = stops.map { it.first },
        )
    }

    override fun equals(other: Any?) = other === this
    override fun hashCode() = System.identityHashCode(this)
}

/**
 * A CSS gradient whose stops are lengths (`rgba(...) 0%, rgba(...) 220px`) on a
 * vertical or horizontal axis. [stops] are (dp from the start, colour); the
 * brush converts them to fractions of the box once its size is known.
 */
fun cssLengthGradient(vertical: Boolean, density: Float, vararg stops: Pair<Dp, Color>): Brush = object : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val extent = if (vertical) size.height else size.width
        val fractions = stops.map { (it.first.value * density / extent).coerceIn(0f, 1f) }
        return LinearGradientShader(
            from = Offset.Zero,
            to = if (vertical) Offset(0f, size.height) else Offset(size.width, 0f),
            colors = stops.map { it.second },
            colorStops = fractions,
        )
    }
}

/** The gradients the CSS uses more than once (PARITY §2.1, §3.0). */
object TvGradients {
    /** `.tv-card-fallback`: 160deg #23262f → #15171c. Also the up-next card. */
    val CardFallback: Brush = cssLinearGradient(160f, 0f to TvColors.CardFallbackTop, 1f to TvColors.CardFallbackBottom)

    /** The card sheen strip: 100deg, 0 → .22 at 48 % → 0. */
    val Sheen: Brush = cssLinearGradient(100f, 0f to Color.Transparent, 0.48f to TvColors.Sheen, 1f to Color.Transparent)

    /** The skeleton shimmer: 90deg, 0 → .16 at 50 % → 0 (drawn at alpha .5). */
    val Shimmer: Brush = cssLinearGradient(90f, 0f to Color.Transparent, 0.5f to TvColors.Shimmer, 1f to Color.Transparent)

    /** Backdrop poster mask: 90deg alpha 0 → .6 at 22 % → 1 at 45 %. Draw with BlendMode.DstIn. */
    val BackdropMask: Brush = cssLinearGradient(
        90f,
        0f to Color.Black.copy(alpha = 0f),
        0.22f to Color.Black.copy(alpha = 0.6f),
        0.45f to Color.Black,
        1f to Color.Black,
    )

    /** Home scrim, layer 2: left→right. */
    val HomeScrimLeft: Brush = cssLinearGradient(
        90f,
        0f to TvColors.Bg,
        0.40f to TvColors.bg(0.92f),
        0.62f to TvColors.bg(0.55f),
        0.76f to TvColors.bg(0.1f),
        0.86f to TvColors.bg(0f),
        1f to TvColors.bg(0f),
    )

    /** Home scrim, layer 3: bottom→top. */
    val HomeScrimBottom: Brush = cssLinearGradient(
        0f,
        0f to TvColors.Bg,
        0.30f to TvColors.bg(0.85f),
        0.62f to TvColors.bg(0f),
        1f to TvColors.bg(0f),
    )

    /** Home scrim, layer 1: top→bottom .7 → 0 over the first 110 dp. Needs the density. */
    fun homeScrimTop(density: Float): Brush = cssLengthGradient(
        vertical = true,
        density = density,
        androidx.compose.ui.unit.Dp(0f) to TvColors.bg(0.7f),
        androidx.compose.ui.unit.Dp(110f) to TvColors.bg(0f),
    )

    /** Detail scrim, layer 1: left→right. */
    val DetailScrimLeft: Brush = cssLinearGradient(
        90f,
        0f to TvColors.Bg,
        0.30f to TvColors.Bg,
        0.48f to TvColors.bg(0.9f),
        0.68f to TvColors.bg(0.35f),
        0.84f to TvColors.bg(0.05f),
        1f to TvColors.bg(0.05f),
    )

    /** Detail scrim, layer 2: bottom→top. */
    val DetailScrimBottom: Brush = cssLinearGradient(
        0f,
        0f to TvColors.Bg,
        0.34f to TvColors.bg(0.9f),
        0.70f to TvColors.bg(0f),
        1f to TvColors.bg(0f),
    )
}

// ── shadows ──────────────────────────────────────────────────────────────────

private data class ShadowKey(val w: Int, val h: Int, val radius: Int, val blur: Int, val spread: Int, val offsetY: Int, val argb: Int)

private class ShadowBitmap(val image: ImageBitmap, val pad: Int)

/** Shared by every element of the same size: a rail of cards holds one bitmap, not forty. */
private val shadowCache = object : LruCache<ShadowKey, ShadowBitmap>(4 * 1024 * 1024) {
    override fun sizeOf(key: ShadowKey, value: ShadowBitmap) = value.image.width * value.image.height
}

/**
 * Render the shadow once: a blurred round rect, with the element's own box cut
 * out of it, as an outer CSS box-shadow is never painted beneath its box.
 */
private fun renderShadow(key: ShadowKey, offsetY: Int): ShadowBitmap {
    // CSS: a blur radius B is a Gaussian with sigma B/2. Skia's BlurMaskFilter
    // takes a "radius" with sigma = 0.57735·r + 0.5.
    val sigma = key.blur / 2f
    val pad = key.blur + key.spread + 2
    val bitmap = Bitmap.createBitmap(max(1, key.w + 2 * pad), max(1, key.h + 2 * pad), Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val s = key.spread.toFloat()
    val p = pad.toFloat()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = key.argb
        if (sigma > 0.5f) maskFilter = BlurMaskFilter(max(0.1f, (sigma - 0.5f) / 0.57735f), BlurMaskFilter.Blur.NORMAL)
    }
    val r = key.radius.toFloat()
    canvas.drawRoundRect(RectF(p - s, p - s, p + key.w + s, p + key.h + s), r + s, r + s, paint)
    // The bitmap is drawn offsetY below the box, so the box sits offsetY above.
    val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    canvas.drawRoundRect(RectF(p, p - offsetY, p + key.w, p + key.h - offsetY), r, r, clear)
    return ShadowBitmap(bitmap.asImageBitmap(), pad)
}

/**
 * A CSS outer `box-shadow` behind this element, pre-rendered once per size and
 * drawn at [alpha] — the only property that animates (DESIGN.md "Performance
 * rules"). [cornerRadius] is the element's border radius.
 */
fun Modifier.tvShadow(spec: TvShadowSpec, cornerRadius: Dp, alpha: () -> Float = { 1f }): Modifier = drawWithCache {
    val key = ShadowKey(
        w = size.width.roundToInt(),
        h = size.height.roundToInt(),
        radius = cornerRadius.toPx().coerceAtMost(minOf(size.width, size.height) / 2f).roundToInt(),
        blur = spec.blur.toPx().roundToInt(),
        spread = spec.spread.toPx().roundToInt(),
        offsetY = spec.offsetY.toPx().roundToInt(),
        argb = spec.color.toArgb(),
    )
    val offsetY = spec.offsetY.toPx().roundToInt()
    val shadow = shadowCache.get(key) ?: renderShadow(key, offsetY).also { shadowCache.put(key, it) }
    onDrawBehind {
        val a = alpha()
        if (a > 0f && size.width > 0f && size.height > 0f) {
            drawImage(
                shadow.image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(shadow.image.width, shadow.image.height),
                dstOffset = IntOffset(-shadow.pad, -shadow.pad + offsetY),
                dstSize = IntSize(shadow.image.width, shadow.image.height),
                alpha = a.coerceIn(0f, 1f),
            )
        }
    }
}
