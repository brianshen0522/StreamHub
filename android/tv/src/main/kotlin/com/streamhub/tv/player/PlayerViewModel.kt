package com.streamhub.tv.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamhub.core.model.ProgressUpdate
import com.streamhub.tv.AppContainer
import com.streamhub.tv.PlaybackRequest
import kotlinx.coroutines.launch

/**
 * Writes watch progress back to the server.
 *
 * `progressPercent` and `isCompleted` are derived server-side from what is sent
 * here, so this only reports where playback actually is. Positions are integer
 * seconds — the schema takes nothing finer.
 *
 * The same shape as the phone's, deliberately: what the two devices report has
 * to be identical or Continue watching disagrees with itself depending on where
 * an episode was last touched.
 */
class PlayerViewModel(
    private val container: AppContainer,
    private val request: PlaybackRequest,
) : ViewModel() {

    private var lastReportedSecond = -1

    init {
        viewModelScope.launch {
            runCatching {
                container.api.rememberSourcePreference(
                    providerKey = request.providerKey,
                    title = request.title,
                    mediaType = request.mediaType,
                    sourceLabel = request.sourceLabel,
                )
            }
        }
    }

    fun report(positionMs: Long, durationMs: Long, event: String) {
        val position = (positionMs / 1000).toInt().coerceAtLeast(0)
        val duration = (durationMs / 1000).toInt().coerceAtLeast(0)

        if (event == "progress" && position / REPORT_EVERY_SECONDS == lastReportedSecond) return
        lastReportedSecond = position / REPORT_EVERY_SECONDS

        viewModelScope.launch {
            runCatching {
                container.api.putProgress(
                    ProgressUpdate(
                        providerKey = request.providerKey,
                        mediaType = request.mediaType,
                        title = request.title,
                        posterUrl = request.posterUrl,
                        itemUrl = request.itemUrl,
                        seasonUrl = request.seasonUrl,
                        seasonLabel = request.seasonLabel,
                        episodeLabel = request.episodeLabel,
                        sourceLabel = request.sourceLabel,
                        durationSeconds = if (duration > 0) duration else request.durationSeconds ?: 0,
                        positionSeconds = position,
                        event = event,
                    )
                )
            }
        }
    }

    private companion object {
        const val REPORT_EVERY_SECONDS = 15
    }
}
