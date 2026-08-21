package com.streamhub.tv

/** A title chosen somewhere, on its way to the detail screen. */
data class MediaSelection(
    val provider: String,
    val itemUrl: String,
    val title: String,
    val mediaType: String?,
    val posterUrl: String?,
)

/** Everything the player needs, resolved before it opens. */
data class PlaybackRequest(
    val providerKey: String,
    val mediaType: String,
    val title: String,
    val posterUrl: String?,
    val itemUrl: String,
    val seasonUrl: String?,
    val seasonLabel: String?,
    val episodeLabel: String?,
    val sourceLabel: String,
    val directUrl: String,
    val durationSeconds: Int?,
    val resumeAtSeconds: Int,
    /** What follows this episode, so the player can roll into it. Null at the end. */
    val nextEpisodeLabel: String? = null,
)

/**
 * Handovers between screens.
 *
 * These carry URLs and titles that would make a navigation route unreadable, so
 * they are passed in memory rather than encoded into one. Same trade as the
 * phone app: a process death mid-playback returns to the home screen.
 */
class Handover {
    var selection: MediaSelection? = null
    var playback: PlaybackRequest? = null

    /** Set when the player rolls into the next episode of the same title. */
    var pendingEpisode: String? = null
}
