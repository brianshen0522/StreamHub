package com.streamhub.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.streamhub.tv.ui.theme.TvColors
import com.streamhub.tv.ui.theme.TvSpace

/** The canvas's size in dp. Tizen's page is 1920×1080 CSS px; 1 CSS px = 0.5 dp. */
const val CANVAS_WIDTH_DP = 960f
const val CANVAS_HEIGHT_DP = 540f

/** `min(widthPx / 960, heightPx / 540)`: the density that maps the canvas onto the display. */
fun canvasScale(widthPx: Int, heightPx: Int): Float =
    minOf(widthPx / CANVAS_WIDTH_DP, heightPx / CANVAS_HEIGHT_DP)

/**
 * The fixed 960×540 dp canvas every screen lays out on (PARITY §1).
 *
 * The density is overridden so 960 dp spans the display's width whatever its
 * resolution: 720p, 1080p and 4K render the same layout, only sharper.
 * `fontScale` is pinned to 1, because Tizen has no large-text setting and a
 * system one must not reflow the layout. A non-16:9 display is letterboxed in
 * `--bg`, and nothing draws outside the canvas (`.app { overflow: hidden }`).
 */
@Composable
fun TvCanvas(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(TvColors.Bg)) {
        val scale = canvasScale(constraints.maxWidth, constraints.maxHeight).takeIf { it > 0f } ?: 1f
        CompositionLocalProvider(LocalDensity provides Density(density = scale, fontScale = 1f)) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .requiredSize(TvSpace.CanvasWidth, TvSpace.CanvasHeight)
                    .clipToBounds()
                    .background(TvColors.TvBg),
            ) {
                content()
            }
        }
    }
}
