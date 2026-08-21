package com.streamhub.mobile.player

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import com.streamhub.core.model.AdCut
import com.streamhub.mobile.ui.StreamHubColors

private val TRACK_HEIGHT = 3.dp
private val TOUCH_HEIGHT = 40.dp
private val THUMB_RADIUS = 7.dp

/**
 * The scrub bar, drawn rather than assembled from a Slider, because it carries
 * three things a Slider will not: the buffered range, the played range, and the
 * marks where advertising was cut out.
 *
 * The marks matter. Ads are removed before playback, so the picture jumps at
 * each splice with nothing to explain it; a mark on the bar is the difference
 * between "the stream is broken" and "that was an advert".
 */
@Composable
fun SeekBar(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    adCuts: List<AdCut>,
    onScrub: (Long) -> Unit,
    onScrubFinished: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var width by remember { mutableFloatStateOf(0f) }

    val duration = durationMs.coerceAtLeast(1)
    val progress = (positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val buffered = (bufferedMs.toFloat() / duration).coerceIn(0f, 1f)

    fun positionFor(x: Float): Long =
        ((x / width.coerceAtLeast(1f)).coerceIn(0f, 1f) * duration).toLong()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TOUCH_HEIGHT)
            .pointerInput(duration) {
                detectTapGestures { offset -> onScrubFinished(positionFor(offset.x)) }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> onScrub(positionFor(offset.x)) },
                    onDragEnd = { },
                    onHorizontalDrag = { change, _ -> onScrub(positionFor(change.position.x)) },
                )
            }
            .pointerInput(duration) {
                // The drag callbacks above report movement; the release position
                // is what playback should actually jump to.
                detectHorizontalDragGestures(
                    onDragEnd = { onScrubFinished(-1) },
                    onHorizontalDrag = { _, _ -> },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(TOUCH_HEIGHT)) {
            width = size.width
            val centerY = size.height / 2f
            val track = TRACK_HEIGHT.toPx()

            fun bar(from: Float, to: Float, color: Color) {
                if (to <= from) return
                drawRoundRect(
                    color = color,
                    topLeft = Offset(from, centerY - track / 2f),
                    size = Size(to - from, track),
                    cornerRadius = CornerRadius(track / 2f),
                )
            }

            bar(0f, size.width, Color.White.copy(alpha = 0.24f))
            bar(0f, size.width * buffered, Color.White.copy(alpha = 0.4f))
            bar(0f, size.width * progress, StreamHubColors.Accent)

            drawAdMarks(adCuts, duration, centerY, track)

            drawCircle(
                color = StreamHubColors.Accent,
                radius = THUMB_RADIUS.toPx(),
                center = Offset(size.width * progress, centerY),
            )
        }
    }
}

private fun DrawScope.drawAdMarks(
    adCuts: List<AdCut>,
    durationMs: Long,
    centerY: Float,
    track: Float,
) {
    if (adCuts.isEmpty()) return
    val markWidth = track * 1.6f

    for (cut in adCuts) {
        val fraction = ((cut.at * 1000.0) / durationMs).toFloat()
        if (fraction !in 0f..1f) continue
        drawRoundRect(
            color = StreamHubColors.Orange,
            topLeft = Offset(size.width * fraction - markWidth / 2f, centerY - track * 1.8f),
            size = Size(markWidth, track * 3.6f),
            cornerRadius = CornerRadius(markWidth / 2f),
        )
    }
}
