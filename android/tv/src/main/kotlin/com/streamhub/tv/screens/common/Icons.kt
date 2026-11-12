package com.streamhub.tv.screens.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.streamhub.tv.ui.theme.TvColors
import com.streamhub.tv.ui.theme.TvMotion

/** SVG path data the Tizen screens draw, in a 24-unit viewBox. */
object TvIconPaths {
    const val Play = "M7 4.5v15l13-7.5z"
    const val Restart = "M4 12a8 8 0 1 0 2.4-5.7M4 4v4.5h4.5"
    const val Heart =
        "M12 20.5s-7.5-4.6-9.6-9.2C1 8.1 3 4.5 6.6 4.5c2.1 0 3.6 1.2 5.4 3.2 1.8-2 3.3-3.2 5.4-3.2 3.6 0 5.6 3.6 4.2 6.8-2.1 4.6-9.6 9.2-9.6 9.2z"
    /** `<circle cx=10.5 cy=10.5 r=6.5/><path d="m20 20-4.6-4.6"/>` as one path. */
    const val Search = "M17 10.5a6.5 6.5 0 1 1-13 0a6.5 6.5 0 1 1 13 0zM20 20l-4.6-4.6"
    const val Check = "m5 12.5 4.5 4.5L19 7.5"
}

private val pathCache = HashMap<String, Path>()

private fun pathOf(d: String): Path = pathCache.getOrPut(d) { PathParser().parsePathString(d).toPath() }

/**
 * `.tv-icon`: a 24-unit SVG path drawn at [size] (30px → 15 dp), stroked with
 * round caps and joins at [strokeWidth] viewBox units, or filled
 * (`.tv-icon-fill`). [fillColor] fills *and* strokes (the filled heart).
 */
@Composable
fun TvIcon(
    path: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 15.dp,
    strokeWidth: Float = 2.4f,
    filled: Boolean = false,
    fillColor: Color? = null,
) {
    val p = pathOf(path)
    Canvas(modifier.size(size)) {
        val k = this.size.width / 24f
        scale(k, k, pivot = Offset.Zero) {
            if (filled) {
                drawPath(p, color, style = Fill)
            } else {
                if (fillColor != null) drawPath(p, fillColor, style = Fill)
                drawPath(p, color, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/**
 * The heart (`Heart`, `common.rs:217-223`, `styles.css:125-138`): 15 dp
 * outline, stroke 2.2, round joins. Filled: fill and stroke `--accent`,
 * popping .6 → 1.2 → 1 over 240 ms `--ease` when it fills.
 */
@Composable
fun TvHeart(filled: Boolean, color: Color) {
    val pop = remember { Animatable(1f) }
    LaunchedEffect(filled) {
        if (filled) {
            pop.snapTo(0f)
            pop.animateTo(1f, tween(TvMotion.T, easing = LinearEasing))
        }
    }
    TvIcon(
        TvIconPaths.Heart,
        if (filled) TvColors.Accent else color,
        Modifier.graphicsLayer {
            val s = if (filled) TvMotion.pop(pop.value) else 1f
            scaleX = s
            scaleY = s
        },
        strokeWidth = 2.2f,
        fillColor = if (filled) TvColors.Accent else null,
    )
}
