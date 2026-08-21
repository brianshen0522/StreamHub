package com.streamhub.core.model

import kotlinx.serialization.Serializable

/**
 * Favorites are keyed per episode, not per title:
 * (providerKey, itemUrl, seasonUrl, episodeLabel), with absent values coerced to
 * "" server-side. Send those consistently or duplicate rows appear.
 */
@Serializable
data class Favorite(
    val id: String,
    val providerKey: String,
    val mediaType: String = "unknown",
    val title: String,
    val posterUrl: String? = null,
    val itemUrl: String,
    val detailUrl: String? = null,
    val seasonUrl: String? = null,
    val seasonLabel: String? = null,
    val episodeLabel: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class WatchProgress(
    val id: String? = null,
    val providerKey: String,
    val mediaType: String = "unknown",
    val title: String,
    val posterUrl: String? = null,
    val itemUrl: String,
    val detailUrl: String? = null,
    val seasonUrl: String? = null,
    val seasonLabel: String? = null,
    val episodeLabel: String? = null,
    val sourceLabel: String? = null,
    val durationSeconds: Int = 0,
    val positionSeconds: Int = 0,
    val progressPercent: Double = 0.0,
    val isCompleted: Boolean = false,
    val lastWatchedAt: String? = null,
)

/**
 * A continue-watching card. The server collapses progress rows to one per title
 * and adds these three fields.
 *
 * When [nextUp] is true the last-watched episode is finished and the client
 * should resolve the *next* one from the episode list on arrival.
 */
@Serializable
data class ContinueItem(
    val providerKey: String,
    val mediaType: String = "unknown",
    val title: String,
    val posterUrl: String? = null,
    val itemUrl: String,
    val detailUrl: String? = null,
    val seasonUrl: String? = null,
    val seasonLabel: String? = null,
    val episodeLabel: String? = null,
    val sourceLabel: String? = null,
    val durationSeconds: Int = 0,
    val positionSeconds: Int = 0,
    val progressPercent: Double = 0.0,
    val isCompleted: Boolean = false,
    val lastWatchedAt: String? = null,
    val nextUp: Boolean = false,
    val episodesTouched: Int = 0,
    val episodesCompleted: Int = 0,
) {
    val isSeries: Boolean get() = !episodeLabel.isNullOrBlank() || mediaType == "tv"
}

/**
 * What a client sends to record progress. `progressPercent` and `isCompleted`
 * are derived server-side and deliberately absent here. Positions are integer
 * seconds — round before sending.
 */
@Serializable
data class ProgressUpdate(
    val providerKey: String,
    val mediaType: String = "unknown",
    val title: String,
    val posterUrl: String? = null,
    val itemUrl: String,
    val detailUrl: String? = null,
    val seasonUrl: String? = null,
    val seasonLabel: String? = null,
    val episodeLabel: String? = null,
    val sourceLabel: String? = null,
    val durationSeconds: Int = 0,
    val positionSeconds: Int = 0,
    val event: String = "progress",
)

/**
 * Dismissing a continue-watching card uses scope "title", which deletes by
 * (providerKey, title) and deliberately ignores itemUrl — a card stands for a
 * whole title, and providers like dramasq give every episode its own itemUrl.
 */
@Serializable
data class ProgressDelete(
    val providerKey: String,
    val itemUrl: String = "",
    val scope: String? = null,
    val title: String? = null,
    val seasonUrl: String? = null,
    val episodeLabel: String? = null,
)

@Serializable
internal data class FavoritesResponse(val favorites: List<Favorite> = emptyList())

@Serializable
internal data class ContinueResponse(val items: List<ContinueItem> = emptyList())

@Serializable
internal data class ProgressResponse(val progress: List<WatchProgress> = emptyList())

@Serializable
internal data class FavoriteResponse(val favorite: Favorite)
