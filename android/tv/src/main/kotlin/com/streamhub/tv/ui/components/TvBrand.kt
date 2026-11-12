package com.streamhub.tv.ui.components

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.streamhub.tv.ui.theme.TvText
import com.streamhub.tv.ui.theme.TvType

/**
 * The wordmark with the logo mark ahead of it: `.tv-brand` /
 * `.tv-login-brand` and their `::before` (`styles.css:94-107`). The mark is
 * 1.15em square with a .3em gap; at the brand's 40px (20 sp) that is 23 dp and
 * 6 dp.
 */
@Composable
fun TvBrand(modifier: Modifier = Modifier, style: TextStyle = TvType.Brand) {
    val em = style.fontSize.value
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((em * 0.3f).dp)) {
        // `margin-top: -.12em` with `vertical-align: middle` sits the mark .06em above the line's middle.
        TvLogoMark(Modifier.offset(y = (-em * 0.06f).dp), size = (em * 1.15f).dp)
        TvText("StreamHub", style)
    }
}

private const val LOGO_PATH =
    "M368.67,351.26 A48,48 0 0 1 440.28,309.47 L724.72,470.21 A48,48 0 0 1 724.72,553.79 " +
        "L440.28,714.53 A48,48 0 0 1 368.67,672.74 Z"

private val playPath: Path by lazy { PathParser().parsePathString(LOGO_PATH).toPath() }

/**
 * `frontend/public/favicon.svg` (= `tizen/app/img/logo.svg`), drawn: a dark
 * rounded tile with a soft top-left light and two faint diagonal streams, and
 * the red play mark with its glow. The glow is a blur mask, which hardware
 * rendering ignores before API 28: there the mark is simply drawn without it.
 */
@Composable
fun TvLogoMark(modifier: Modifier = Modifier, size: Dp = 23.dp) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val k = s / 1024f
        val tile = Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, s, s, CornerRadius(200 * k, 200 * k)))
        }
        clipPath(tile) {
            drawRect(Brush.linearGradient(listOf(Color(0xFF212123), Color(0xFF070708)), Offset.Zero, Offset(s, s)))
            drawRect(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0f)),
                    center = Offset(0.30f * s, 0.22f * s),
                    radius = 0.78f * s,
                ),
            )
            withTransform({
                translate(512 * k, 512 * k)
                rotate(-34.6f, pivot = Offset.Zero)
            }) {
                val stream = Brush.horizontalGradient(
                    0f to Color.White.copy(alpha = 0f),
                    0.5f to Color.White.copy(alpha = 0.07f),
                    1f to Color.White.copy(alpha = 0f),
                    startX = -1024 * k,
                    endX = 1024 * k,
                )
                drawRoundRect(stream, Offset(-1024 * k, -307.2f * k), Size(2048 * k, 117.76f * k), CornerRadius(58.88f * k))
                drawRoundRect(stream, Offset(-1024 * k, 204.8f * k), Size(2048 * k, 56.32f * k), CornerRadius(28.16f * k))
            }
        }
        val mark = Path().apply {
            addPath(playPath)
            transform(Matrix().apply { scale(k, k) })
        }
        // feDropShadow stdDeviation 28.16, #FF2733 at .45.
        val sigma = 28.16f * k
        if (sigma > 0.5f) {
            drawIntoCanvas { c ->
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color(0xFFFF2733).copy(alpha = 0.45f).toArgb()
                    maskFilter = BlurMaskFilter(sigma * 2f, BlurMaskFilter.Blur.NORMAL)
                }
                c.nativeCanvas.drawPath(mark.asAndroidPath(), paint)
            }
        }
        val b = mark.getBounds()
        drawPath(mark, Brush.linearGradient(listOf(Color(0xFFFF2733), Color(0xFFCB050E)), b.topLeft, b.bottomRight))
    }
}
