package com.streamhub.mobile.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamhub.core.model.CastReceiver
import com.streamhub.mobile.player.SeekBar
import com.streamhub.mobile.ui.Poster
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * The phone as a remote.
 *
 * Everything here is a picture of state the television owns, which is what
 * makes the two hard parts hard. The reported position arrives about once a
 * second, so the bar would tick in visible steps unless it is advanced locally
 * between reports; and a scrub has to keep showing where the thumb was left
 * until the television confirms it got there, or the bar snaps back to a stale
 * position for a second and reads as a failed seek.
 */
@Composable
fun RemoteScreen(
    target: CastReceiver,
    lost: Boolean,
    ownDeviceName: String?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFullscreen: () -> Unit,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
    onPlayHere: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = target.state

    // Where the reported position was, and when it arrived, so playback can be
    // interpolated between reports.
    var reportedMs by remember { mutableStateOf(state?.positionMs ?: 0L) }
    var reportedAt by remember { mutableStateOf(0L) }
    var elapsedMs by remember { mutableStateOf(0L) }

    // Held from the moment a scrub ends until the television reports a
    // position near it, so the thumb stays where it was put.
    var pendingSeekMs by remember { mutableStateOf<Long?>(null) }
    var scrubbingMs by remember { mutableStateOf<Long?>(null) }

    // What the last press asked for, shown until the television confirms it
    // or 2.5 seconds pass. Without it the play button lagged a round-trip
    // behind the thumb — long enough to press again and undo the first press.
    var assumedPaused by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(assumedPaused, state?.paused) {
        val assumed = assumedPaused ?: return@LaunchedEffect
        if (state?.paused == assumed) {
            assumedPaused = null
            return@LaunchedEffect
        }
        delay(2_500)
        assumedPaused = null
    }
    val effectivePaused = assumedPaused ?: (state?.paused ?: true)

    LaunchedEffect(state?.positionMs) {
        reportedMs = state?.positionMs ?: 0L
        reportedAt = 0L
        elapsedMs = 0L
    }

    // Advance locally between reports. Only while playing: a paused position
    // that crept forward would be a lie about the television. The assumed
    // pause freezes it too, so an optimistic pause stops the clock at once.
    LaunchedEffect(effectivePaused, state?.positionMs) {
        if (state == null || effectivePaused) return@LaunchedEffect
        while (true) {
            delay(200)
            elapsedMs += 200
        }
    }

    LaunchedEffect(state?.positionMs, pendingSeekMs) {
        val pending = pendingSeekMs ?: return@LaunchedEffect
        val reported = state?.positionMs
        if (reported != null && abs(reported - pending) < 2_000) {
            pendingSeekMs = null
            return@LaunchedEffect
        }
        // The television may have ignored the seek, or ended. Do not hold a
        // wrong position on screen forever.
        delay(4_000)
        pendingSeekMs = null
    }

    val durationMs = state?.durationMs ?: 0L
    val livePosition = (reportedMs + elapsedMs).coerceIn(0L, maxOf(durationMs, reportedMs + elapsedMs))
    val shownPosition = scrubbingMs ?: pendingSeekMs ?: livePosition

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // This screen is its own root, so nothing else keeps the header out
            // from under the status bar clock.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "Back",
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    text = if (lost) "Disconnected" else "Playing on",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (lost) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = target.deviceName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            CastIcon(
                connected = !lost,
                tint = if (lost) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            )
        }

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Poster(url = state?.posterUrl, modifier = Modifier.fillMaxWidth(0.62f))
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = state?.title ?: "Nothing playing",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
            )
            val detail = listOfNotNull(state?.episodeLabel, state?.subtitle).joinToString(" · ")
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            // The other hand on the same device, if any. The receiver names
            // whoever last drove it; showing that back — except when it is
            // this phone — keeps two remotes from reading each other's
            // presses as glitches.
            val otherController = state?.controlledBy?.takeIf { it != ownDeviceName }
            if (otherController != null) {
                Text(
                    text = "Also controlled from $otherController",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SeekBar(
                positionMs = shownPosition,
                // Nothing here knows the television's buffer, and inventing one
                // would be a bar that means nothing.
                bufferedMs = 0,
                durationMs = durationMs,
                adCuts = emptyList(),
                onScrub = { scrubbingMs = it },
                onScrubFinished = { position ->
                    scrubbingMs = null
                    pendingSeekMs = position
                    onSeek(position)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = formatTime(shownPosition),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(modifier = Modifier.weight(1f))
                Text(
                    text = formatTime(durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            // Weighted, not fixed-width: five controls at 72dp apiece plus
            // spacing outgrow a 411dp phone, and a Row does not wrap — the
            // next-episode button rendered off the edge of the screen.
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The season's edges are the television's to know: at the first
            // episode there is no previous and at the last no next, and the
            // buttons say so by being disabled rather than doing nothing.
            SkipButton("⏮", Modifier.weight(1f), enabled = !lost && state?.hasPrevious == true) {
                onPrevious()
            }
            SkipButton("−10", Modifier.weight(1f), enabled = !lost && state != null) {
                onSeek((livePosition - 10_000).coerceAtLeast(0))
            }
            Surface(
                modifier = Modifier.size(68.dp).clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                onClick = {
                    val next = !effectivePaused
                    assumedPaused = next
                    if (next) onPause() else onResume()
                },
                enabled = !lost && state != null,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    PlayPauseGlyph(
                        paused = effectivePaused,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        sizeDp = 32,
                    )
                }
            }
            SkipButton("+10", Modifier.weight(1f), enabled = !lost && state != null) {
                onSeek(livePosition + 10_000)
            }
            SkipButton("⏭", Modifier.weight(1f), enabled = !lost && state?.hasNext == true) {
                onNext()
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            TextButton(
                onClick = onFullscreen,
                enabled = !lost && state != null,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Text("Fullscreen") }
            TextButton(
                onClick = onPlayHere,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Text("Play on this phone") }
            TextButton(
                onClick = onStop,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("Stop") }
        }
    }
}

/**
 * Deliberately not accented. The play button is the one red thing on this
 * screen; three red controls in a row and none of them is the obvious one.
 */
@Composable
private fun SkipButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Text(text = label, style = MaterialTheme.typography.titleMedium)
    }
}

private fun formatTime(millis: Long): String {
    if (millis <= 0) return "0:00"
    val total = millis / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
