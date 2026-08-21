package com.streamhub.mobile.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks

/** A video track the viewer can pick, or Auto. */
data class QualityOption(val height: Int, val label: String)

/**
 * What the controls need to draw, kept out of the composable so a position tick
 * does not recompose anything that has not changed.
 */
@Stable
class PlayerUiState {
    var isPlaying by mutableStateOf(false)
    var isBuffering by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var bufferedMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var speed by mutableFloatStateOf(1f)
    var qualities by mutableStateOf<List<QualityOption>>(emptyList())
    var selectedHeight by mutableStateOf<Int?>(null)
    var hasSubtitles by mutableStateOf(false)
    var subtitlesOn by mutableStateOf(false)

    /** Scrubbing shows the dragged position, not the one playback is still at. */
    var scrubbingMs by mutableStateOf<Long?>(null)

    val displayPositionMs: Long get() = scrubbingMs ?: positionMs

    fun readFrom(player: Player) {
        isPlaying = player.isPlaying
        isBuffering = player.playbackState == Player.STATE_BUFFERING
        positionMs = player.currentPosition.coerceAtLeast(0)
        bufferedMs = player.bufferedPosition.coerceAtLeast(0)
        durationMs = if (player.duration == C.TIME_UNSET) 0 else player.duration
        speed = player.playbackParameters.speed
    }

    fun readTracks(tracks: Tracks) {
        val heights = sortedSetOf<Int>()
        var subtitles = false
        var subtitlesSelected = false

        for (group in tracks.groups) {
            when (group.type) {
                C.TRACK_TYPE_VIDEO ->
                    for (i in 0 until group.length) {
                        group.getTrackFormat(i).height.takeIf { it > 0 }?.let(heights::add)
                    }
                C.TRACK_TYPE_TEXT -> {
                    subtitles = true
                    if (group.isSelected) subtitlesSelected = true
                }
            }
        }

        qualities = heights.sortedDescending().map { QualityOption(it, "${it}p") }
        hasSubtitles = subtitles
        subtitlesOn = subtitlesSelected
    }
}

/** Caps the resolution rather than pinning one track, so adaptation still works below it. */
fun Player.applyQuality(height: Int?) {
    trackSelectionParameters = trackSelectionParameters.buildUpon()
        .setMaxVideoSize(Int.MAX_VALUE, height ?: Int.MAX_VALUE)
        .build()
}

fun Player.applySubtitles(enabled: Boolean) {
    trackSelectionParameters = trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
        .build()
}
