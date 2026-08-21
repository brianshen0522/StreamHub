package com.streamhub.core.model

import kotlinx.serialization.Serializable

@Serializable
data class SearchItem(
    val provider: String,
    val title: String,
    val url: String,
    val posterUrl: String? = null,
    val mediaType: String = "unknown",
    val rawType: String? = null,
)

/**
 * One provider's slice of a search. A provider that failed still gets an entry,
 * with [error] set — the request itself answers 200 either way, so this field is
 * the only place a failure shows up.
 */
@Serializable
data class ProviderResults(
    val provider: String,
    val items: List<SearchItem> = emptyList(),
    val error: String? = null,
)

@Serializable
data class SearchResponse(
    val query: String = "",
    val results: List<ProviderResults> = emptyList(),
)

@Serializable
data class SeasonRef(val label: String, val url: String)

@Serializable
data class RawStream(
    val sourceLabel: String,
    val episodeLabel: String? = null,
    val url: String,
)

/**
 * `/api/item` answers a different shape per provider and media type, returned
 * bare rather than wrapped. Which key is present is the discriminator, so this
 * is a sealed type parsed by inspection rather than by a type field.
 */
sealed interface ItemDetail {
    val provider: String
    val title: String
    val posterUrl: String?

    /** A movieffm TV hub: pick a season, then fetch that URL for its episodes. */
    data class Seasons(
        override val provider: String,
        override val title: String,
        override val posterUrl: String?,
        val seasons: List<SeasonRef>,
    ) : ItemDetail

    /** A season, or a series whose single page holds every episode. */
    data class Episodes(
        override val provider: String,
        override val title: String,
        override val posterUrl: String?,
        val episodes: List<String>,
        /** Whichever of seasonUrl / detailUrl the provider supplied. */
        val sourceUrl: String?,
    ) : ItemDetail

    /** A movie: streams are already listed and go straight to /api/check-sources. */
    data class Movie(
        override val provider: String,
        override val title: String,
        override val posterUrl: String?,
        val streams: List<RawStream>,
    ) : ItemDetail
}

/**
 * One line of the `/api/sources` NDJSON stream, written as each source finishes
 * its health probe. Sources that failed are never emitted at all.
 *
 * [durationSeconds] already has detected ad segments subtracted, so it matches
 * the timeline the player will produce rather than the raw manifest.
 */
@Serializable
data class Source(
    val sourceLabel: String,
    val episodeLabel: String? = null,
    val url: String,
    val durationSeconds: Int? = null,
    val adSeconds: Int = 0,
    val ok: Boolean = true,
    val statusCode: Int? = null,
    val directUrl: String,
    val proxyUrl: String? = null,
)

@Serializable
data class EpisodesResponse(
    val provider: String = "",
    val sourceUrl: String = "",
    val episodes: List<String> = emptyList(),
)

@Serializable
data class ProviderInfo(
    val key: String,
    val name: String,
    /** Turned off for everyone by an admin. */
    val isEnabled: Boolean = true,
    /** Enabled *and* permitted for this account. Only these can be searched. */
    val allowed: Boolean = true,
    /**
     * HEALTHY, DEGRADED, DOWN or DISABLED, from the server's own poller.
     *
     * Deliberately the only health field a client gets. The underlying error
     * text and response times describe the server rather than anything a viewer
     * can act on, so they stay in the admin console.
     */
    val status: String? = null,
) {
    /**
     * Why this provider cannot be searched, or null when it can. Distinguishing
     * these is the whole point: an empty search looks the same whether a site is
     * down, switched off, or simply has no match.
     */
    val unavailableReason: String?
        get() = when {
            !isEnabled -> "Turned off by the administrator"
            !allowed -> "Not enabled for this account"
            status == "DOWN" -> "Not responding"
            else -> null
        }
}

@Serializable
data class ServerHealth(val ok: Boolean = false, val apiVersion: Int? = null)

/**
 * A stretch of advertising that was cut out, positioned on the *cleaned*
 * timeline — the one the player shows. Marking these on the scrub bar is why
 * they are worth fetching: a jump in the picture with nothing to explain it
 * looks like a broken stream.
 */
@Serializable
data class AdCut(val at: Double, val removed: Double)

@Serializable
data class AdCuts(
    val removedSeconds: Double = 0.0,
    val reason: String? = null,
    val cuts: List<AdCut> = emptyList(),
)

@Serializable
internal data class ProvidersResponse(val providers: List<ProviderInfo> = emptyList())
