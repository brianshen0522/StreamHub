package com.streamhub.mobile

/** What the detail screen needs to know about the title it was opened for. */
data class MediaSelection(
    val provider: String,
    val itemUrl: String,
    val title: String,
    val mediaType: String,
    val posterUrl: String?,
)

/**
 * Everything the player needs, and everything progress reporting needs to write
 * back afterwards.
 */
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
    /** What follows this episode, so the player can offer it. Null at the end. */
    val nextEpisodeLabel: String? = null,
)

/**
 * Handovers between screens.
 *
 * These carry URLs and titles that would make a navigation route unreadable, so
 * they are passed in memory rather than encoded into one. The cost is that a
 * process death mid-playback returns to the tab rather than the player, which is
 * an acceptable trade for a personal app and easy to revisit.
 */
class Handover {
    var selection: MediaSelection? = null
    var playback: PlaybackRequest? = null

    /**
     * Set when the player asks for the next episode. The detail screen is still
     * on the back stack with its state intact, so it picks this up on the way
     * back rather than reloading the title from scratch.
     */
    var pendingEpisode: String? = null
}
