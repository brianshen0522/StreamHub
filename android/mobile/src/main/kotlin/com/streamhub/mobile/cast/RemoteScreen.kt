package com.streamhub.mobile.cast

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamhub.core.model.CastPlayRequest
import com.streamhub.core.model.CastReceiver
import com.streamhub.mobile.player.SeekBar
import com.streamhub.mobile.ui.Poster
import kotlinx.coroutines.delay

/**
 * The phone as a remote.
 *
 * Everything here is a picture of state the television owns. The position is
 * interpolated between its reports and a seek is held until confirmed — both
 * in [RemoteTimeline], where they are tested — and play/pause shows the press
 * at once through [CastController.paused].
 *
 * [starting] is a play this phone sent that the television has not reported
 * yet: its title is shown instead of whatever was on before (or "Nothing
 * playing"), and the transport waits for it.
 */
@Composable
fun RemoteScreen(
    target: CastReceiver,
    lost: Boolean,
    paused: Boolean,
    starting: CastPlayRequest?,
    notice: String?,
    ownDeviceName: String?,
    posterUrl: (String) -> String?,
    onTogglePause: () -> Boolean,
    onFullscreen: () -> Boolean,
    onSeek: (Long) -> Boolean,
    onPrevious: () -> Boolean,
    onNext: () -> Boolean,
    onStop: () -> Unit,
    onPlayHere: (positionMs: Long) -> Unit,
    onClearNotice: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    clock: () -> Long = { SystemClock.uptimeMillis() },
) {
    val state = target.state?.takeIf { !it.idle }
    val controllable = !lost && state != null && starting == null
    val playing = controllable && !paused && !state.buffering

    var timeline by remember(target.sessionId) { mutableStateOf(RemoteTimeline()) }
    var now by remember { mutableLongStateOf(clock()) }
    var scrubbingMs by remember { mutableStateOf<Long?>(null) }

    // A report: fold it in. Keyed on the state itself, so the identical state
    // re-sent with the receiver list does not re-anchor anything.
    LaunchedEffect(state) {
        if (state != null) {
            timeline = timeline.report(state.positionMs, state.durationMs, playing, clock())
        }
    }
    // An optimistic pause, buffering, or the device going away stops the
    // clock at once rather than at the next report.
    LaunchedEffect(playing) { timeline = timeline.withPlaying(playing, clock()) }
    // The clock only runs while something on screen moves with it: playback,
    // or a held seek that has to be let go of.
    LaunchedEffect(playing) {
        now = clock()
        while (playing) {
            delay(TICK_MS)
            now = clock()
        }
    }
    LaunchedEffect(timeline.pendingSeekMs, timeline.pendingSince) {
        if (timeline.pendingSeekMs != null) {
            delay(RemoteTimeline.HOLD_MS)
            now = clock()
        }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_MS)
            onClearNotice()
        }
    }

    val durationMs = if (state != null) timeline.durationMs else 0L
    val livePosition = timeline.positionAt(now)
    val shownPosition = if (state == null) 0L else scrubbingMs ?: timeline.shownAt(now)

    val title = starting?.title ?: state?.title
    val episode = starting?.episodeLabel ?: state?.episodeLabel
    val subtitle = if (starting != null) starting.subtitle else state?.subtitle
    val poster = (starting?.posterUrl ?: state?.posterUrl)?.takeIf { it.isNotBlank() }?.let(posterUrl)

    fun seek(next: RemoteTimeline) {
        val to = next.pendingSeekMs ?: return
        if (onSeek(to)) timeline = next
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // This screen is its own root, so nothing else keeps the header out
            // from under the status bar clock.
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // Landscape, or a small phone: the poster goes and the rest scrolls
        // rather than being crushed — a Column out of height squeezes its last
        // children, which here are the transport controls.
        val compact = maxHeight < COMPACT_HEIGHT
        Column(
            modifier = Modifier
                .fillMaxSize()
                .let { if (compact) it.verticalScroll(rememberScrollState()) else it }
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Back")
                }
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        text = when {
                            lost -> "Disconnected — waiting for it to come back"
                            starting != null -> "Starting on"
                            else -> "Playing on"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (lost) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = target.deviceName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
                CastIcon(
                    connected = !lost,
                    tint = if (lost) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
            }

            if (!compact) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    // Sized off the height it is given, not the width: on a
                    // short phone a width-derived poster overflowed onto the
                    // title below it.
                    Box(modifier = Modifier.fillMaxWidth(0.62f), contentAlignment = Alignment.Center) {
                        Poster(url = poster, modifier = Modifier.fillMaxHeight())
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title ?: "Nothing playing",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                )
                val detail = listOfNotNull(episode?.takeIf { it.isNotBlank() }, subtitle?.takeIf { it.isNotBlank() })
                    .joinToString(" · ")
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
                if (otherController != null && !lost) {
                    Text(
                        text = "Also controlled from $otherController",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
                if (notice != null) {
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
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
                    // Without a duration every position maps to zero: a stray
                    // touch would restart the episode.
                    onScrub = { if (controllable && durationMs > 0) scrubbingMs = it },
                    onScrubFinished = { position ->
                        scrubbingMs = null
                        if (controllable && durationMs > 0) seek(timeline.seekTo(position, clock()))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = formatTime(shownPosition),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (controllable && state.buffering) {
                            Text(
                                text = "Buffering…",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        text = if (durationMs > 0) formatTime(durationMs) else "--:--",
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
                SkipButton("⏮", "Previous episode", Modifier.weight(1f), controllable && state.hasPrevious) {
                    onPrevious()
                }
                // From what is on screen, so a second tap before the first is
                // confirmed adds to it rather than repeating it.
                SkipButton("−10", "Back 10 seconds", Modifier.weight(1f), controllable) {
                    seek(timeline.seekBy(-SKIP_MS, clock()))
                }
                Surface(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .semantics { contentDescription = if (paused) "Play" else "Pause" },
                    color = MaterialTheme.colorScheme.primary,
                    onClick = { onTogglePause() },
                    enabled = controllable,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        PlayPauseGlyph(
                            paused = paused,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            sizeDp = 32,
                        )
                    }
                }
                SkipButton("+10", "Forward 10 seconds", Modifier.weight(1f), controllable) {
                    seek(timeline.seekBy(SKIP_MS, clock()))
                }
                SkipButton("⏭", "Next episode", Modifier.weight(1f), controllable && state.hasNext) {
                    onNext()
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                TextButton(
                    onClick = { onFullscreen() },
                    enabled = controllable,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { Text("Fullscreen") }
                TextButton(
                    // Where the bar is, not the last report — that is up to a
                    // second old, and a handover is meant to be seamless.
                    onClick = { onPlayHere(if (state != null) livePosition else 0L) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { Text("Play on this phone") }
                TextButton(
                    onClick = onStop,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(if (lost) "Disconnect" else "Stop") }
            }
        }
    }
}

private const val SKIP_MS = 10_000L
private const val TICK_MS = 250L
private const val NOTICE_MS = 4_000L
private val COMPACT_HEIGHT = 560.dp

/**
 * Deliberately not accented. The play button is the one red thing on this
 * screen; three red controls in a row and none of them is the obvious one.
 */
@Composable
private fun SkipButton(
    label: String,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics { contentDescription = description },
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
