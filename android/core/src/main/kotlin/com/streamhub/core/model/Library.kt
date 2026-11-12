package com.streamhub.core.model

import com.streamhub.core.model.serial.LenientIntSerializer
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
    /**
     * Set by the server only on a row whose title the provider is known to
     * have dropped (shared/api/README.md). False means *no record*, not a claim
     * that it still plays. Mark such rows; never hide them.
     */
    val isUnavailable: Boolean = false,
    /** `"no-episodes"` or `"not-found"` when [isUnavailable]; see [UnavailableReason]. */
    val unavailableReason: String? = null,
)

/**
 * What a client posts to create a favorite. Separate from [Favorite] because the
 * server assigns the id, so requiring one here would mean inventing a value it
 * ignores.
 */
@Serializable
data class NewFavorite(
    val providerKey: String,
    val mediaType: String = "unknown",
    val title: String,
    val posterUrl: String? = null,
    val itemUrl: String,
    val detailUrl: String? = null,
    val seasonUrl: String? = null,
    val seasonLabel: String? = null,
    val episodeLabel: String? = null,
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
    @Serializable(with = LenientIntSerializer::class)
    val durationSeconds: Int = 0,
    @Serializable(with = LenientIntSerializer::class)
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
    /**
     * The progress row's id when the server sends it. Not guaranteed; Tizen
     * falls back to a composite of providerKey and itemUrl for a stable key.
     */
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
    @Serializable(with = LenientIntSerializer::class)
    val durationSeconds: Int = 0,
    @Serializable(with = LenientIntSerializer::class)
    val positionSeconds: Int = 0,
    val progressPercent: Double = 0.0,
    val isCompleted: Boolean = false,
    val lastWatchedAt: String? = null,
    val nextUp: Boolean = false,
    @Serializable(with = LenientIntSerializer::class)
    val episodesTouched: Int = 0,
    @Serializable(with = LenientIntSerializer::class)
    val episodesCompleted: Int = 0,
    /**
     * Set by the server only on a row whose title the provider is known to
     * have dropped (shared/api/README.md). False means *no record*, not a claim
     * that it still plays. Mark such rows; never hide them.
     */
    val isUnavailable: Boolean = false,
    /** `"no-episodes"` or `"not-found"` when [isUnavailable]; see [UnavailableReason]. */
    val unavailableReason: String? = null,
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

/**
 * An append-only record of what was watched. The server only writes one when an
 * event is not a routine progress tick, or when at least a minute was watched,
 * so this is not a duplicate of [WatchProgress].
 */
@Serializable
data class WatchHistoryEntry(
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
    val sourceLabel: String? = null,
    @Serializable(with = LenientIntSerializer::class)
    val positionSeconds: Int = 0,
    @Serializable(with = LenientIntSerializer::class)
    val durationSeconds: Int = 0,
    val watchedAt: String? = null,
    /**
     * Set by the server only on a row whose title the provider is known to
     * have dropped (shared/api/README.md). False means *no record*, not a claim
     * that it still plays. Mark such rows; never hide them.
     */
    val isUnavailable: Boolean = false,
    /** `"no-episodes"` or `"not-found"` when [isUnavailable]; see [UnavailableReason]. */
    val unavailableReason: String? = null,
)

@Serializable
internal data class HistoryResponse(val history: List<WatchHistoryEntry> = emptyList())

@Serializable
data class SourcePreference(
    val providerKey: String,
    val mediaType: String = "unknown",
    val title: String,
    val sourceLabel: String,
    val lastSelectedAt: String? = null,
)

@Serializable
internal data class FavoritesResponse(val favorites: List<Favorite> = emptyList())

@Serializable
internal data class ContinueResponse(val items: List<ContinueItem> = emptyList())

@Serializable
internal data class ProgressResponse(val progress: List<WatchProgress> = emptyList())

@Serializable
internal data class FavoriteResponse(val favorite: Favorite)

/** The values the server puts in `unavailableReason`. */
object UnavailableReason {
    /** The detail page lists no episodes any more. */
    const val NO_EPISODES = "no-episodes"

    /** The provider answered 404 for the title. */
    const val NOT_FOUND = "not-found"
}
