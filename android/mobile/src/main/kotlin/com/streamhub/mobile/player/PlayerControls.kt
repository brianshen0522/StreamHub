package com.streamhub.mobile.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.streamhub.core.model.AdCut
import com.streamhub.mobile.ui.StreamHubColors
import kotlin.math.roundToInt

private val RATES = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * Everything drawn over the video.
 *
 * Media3's stock controls were the reason this looked unfinished: they are a
 * transport bar and nothing else. This is the same set the web player has —
 * jump buttons, speed, quality, subtitles, and the ad marks on the scrub bar,
 * which are the one thing here that is specific to this app.
 */
@Composable
fun PlayerControls(
    state: PlayerUiState,
    title: String,
    subtitle: String?,
    adCuts: List<AdCut>,
    visible: Boolean,
    isFullscreen: Boolean,
    onPlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onScrub: (Long) -> Unit,
    onSkip: (Long) -> Unit,
    onSpeed: (Float) -> Unit,
    onQuality: (Int?) -> Unit,
    onSubtitles: (Boolean) -> Unit,
    fillScreen: Boolean,
    onFillScreen: (Boolean) -> Unit,
    onFullscreen: () -> Unit,
    onPip: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Buffering shows through whether or not the controls are up, otherwise a
        // stall during a hidden-controls stretch looks like a freeze.
        if (state.isBuffering) {
            CircularProgressIndicator(
                color = StreamHubColors.Accent,
                modifier = Modifier.align(Alignment.Center).size(48.dp),
            )
        }

        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Scrims top and bottom rather than over the whole frame, so
                    // the middle of the picture stays visible while scrubbing.
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.72f),
                            0.25f to Color.Transparent,
                            0.72f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.82f),
                        )
                    )
            ) {
                TopBar(
                    title = title,
                    subtitle = subtitle,
                    state = state,
                    onBack = onBack,
                    onPip = onPip,
                    onSpeed = onSpeed,
                    onQuality = onQuality,
                    onSubtitles = onSubtitles,
                    fillScreen = fillScreen,
                    onFillScreen = onFillScreen,
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                CentreButtons(
                    isPlaying = state.isPlaying,
                    isBuffering = state.isBuffering,
                    onPlayPause = onPlayPause,
                    onSkip = onSkip,
                    modifier = Modifier.align(Alignment.Center),
                )

                BottomBar(
                    state = state,
                    adCuts = adCuts,
                    isFullscreen = isFullscreen,
                    onScrub = onScrub,
                    onSeekTo = onSeekTo,
                    onFullscreen = onFullscreen,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    subtitle: String?,
    state: PlayerUiState,
    onBack: () -> Unit,
    onPip: () -> Unit,
    onSpeed: (Float) -> Unit,
    onQuality: (Int?) -> Unit,
    onSubtitles: (Boolean) -> Unit,
    fillScreen: Boolean,
    onFillScreen: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf<String?>(null) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        IconButton(onClick = onPip) {
            // No dedicated PiP glyph in the icon set that ships with Compose;
            // the shrink arrow reads closely enough for the action.
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Picture in picture", tint = Color.White)
        }

        Box {
            IconButton(onClick = { menu = "root" }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.White)
            }

            DropdownMenu(expanded = menu == "root", onDismissRequest = { menu = null }) {
                DropdownMenuItem(
                    text = { Text("Playback speed") },
                    trailingIcon = { Text(speedLabel(state.speed)) },
                    onClick = { menu = "speed" },
                )
                if (state.qualities.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Quality") },
                        trailingIcon = { Text(state.selectedHeight?.let { "${it}p" } ?: "Auto") },
                        onClick = { menu = "quality" },
                    )
                }
                if (state.hasSubtitles) {
                    DropdownMenuItem(
                        text = { Text("Subtitles") },
                        trailingIcon = { Text(if (state.subtitlesOn) "On" else "Off") },
                        onClick = { onSubtitles(!state.subtitlesOn); menu = null },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Zoom") },
                    trailingIcon = { Text(if (fillScreen) "Fill" else "Fit") },
                    onClick = { onFillScreen(!fillScreen); menu = null },
                )
            }

            DropdownMenu(expanded = menu == "speed", onDismissRequest = { menu = null }) {
                for (rate in RATES) {
                    DropdownMenuItem(
                        text = { Text(speedLabel(rate)) },
                        trailingIcon = { if (state.speed == rate) Icon(Icons.Default.Check, null) },
                        onClick = { onSpeed(rate); menu = null },
                    )
                }
            }

            DropdownMenu(expanded = menu == "quality", onDismissRequest = { menu = null }) {
                DropdownMenuItem(
                    text = { Text("Auto") },
                    trailingIcon = { if (state.selectedHeight == null) Icon(Icons.Default.Check, null) },
                    onClick = { onQuality(null); menu = null },
                )
                for (option in state.qualities) {
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        trailingIcon = { if (state.selectedHeight == option.height) Icon(Icons.Default.Check, null) },
                        onClick = { onQuality(option.height); menu = null },
                    )
                }
            }
        }
    }
}

@Composable
private fun CentreButtons(
    isPlaying: Boolean,
    isBuffering: Boolean,
    onPlayPause: () -> Unit,
    onSkip: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkipButton(seconds = 10, forward = false, onClick = { onSkip(-10_000) })

        IconButton(
            onClick = onPlayPause,
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f)),
        ) {
            // The icon set has no pause glyph, so pause is two bars drawn here.
            if (isPlaying) {
                PauseGlyph()
            } else if (!isBuffering) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.White,
                    modifier = Modifier.size(44.dp),
                )
            }
        }

        SkipButton(seconds = 10, forward = true, onClick = { onSkip(10_000) })
    }
}

/**
 * Offered when the episode finishes, rather than as a fourth button in the row
 * above — that put the play control off centre for the whole of playback to
 * serve a moment at the end of it.
 */
@Composable
fun UpNextPrompt(label: String, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(onClick = onPlay)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
        Column {
            Text(
                "Up next",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
            Text(label, style = MaterialTheme.typography.titleSmall, color = Color.White)
        }
    }
}

/**
 * The circular replay arrow with the number inside, mirrored for forward.
 *
 * The icon set that ships with Compose has one circular arrow and no numbered
 * skip glyphs, so using it for both directions left two buttons that looked
 * identical — the number and the mirroring are what make the direction readable.
 */
@Composable
private fun SkipButton(seconds: Int, forward: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(52.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = if (forward) "Forward $seconds seconds" else "Back $seconds seconds",
                tint = Color.White,
                modifier = Modifier
                    .size(36.dp)
                    .scale(scaleX = if (forward) 1f else -1f, scaleY = 1f),
            )
            Text(
                text = "$seconds",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun PauseGlyph() {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        repeat(2) {
            Box(
                modifier = Modifier
                    .size(width = 6.dp, height = 30.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}

@Composable
private fun BottomBar(
    state: PlayerUiState,
    adCuts: List<AdCut>,
    isFullscreen: Boolean,
    onScrub: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        SeekBar(
            positionMs = state.displayPositionMs,
            bufferedMs = state.bufferedMs,
            durationMs = state.durationMs,
            adCuts = adCuts,
            onScrub = { state.scrubbingMs = it; onScrub(it) },
            onScrubFinished = { target ->
                val destination = if (target >= 0) target else state.displayPositionMs
                state.scrubbingMs = null
                onSeekTo(destination)
            },
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${formatTime(state.displayPositionMs)} / ${formatTime(state.durationMs)}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
            Box(modifier = Modifier.weight(1f))
            IconButton(onClick = onFullscreen) {
                Icon(
                    if (isFullscreen) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    contentDescription = if (isFullscreen) "Exit full screen" else "Full screen",
                    tint = Color.White,
                )
            }
        }
    }
}

private fun speedLabel(rate: Float): String =
    if (rate == rate.roundToInt().toFloat()) "${rate.roundToInt()}x" else "${rate}x"

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val seconds = total % 60
    val minutes = (total / 60) % 60
    val hours = total / 3600
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
