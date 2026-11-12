package com.streamhub.core.model

import com.streamhub.core.model.serial.LenientStringSerializer
import kotlinx.serialization.Serializable

@Serializable
data class SearchItem(
    val provider: String,
    val title: String,
    val url: String,
    val posterUrl: String? = null,
    val mediaType: String = "unknown",
    val rawType: String? = null,
    /**
     * Release year as the provider printed it, for a card's subtitle. Absent on
     * most providers; a number on the wire is accepted and kept as text.
     */
    @Serializable(with = LenientStringSerializer::class)
    val year: String? = null,
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
 *
 * The rules are the Tizen client's (`tizen/app/src/screens/detail.rs` `load`):
 * 1. a `seasons` list with at least one entry whose `url` is not blank is a
 *    hub ([Seasons], blank-url entries dropped);
 * 2. otherwise an `episodes` key, even an empty list, is a series ([Episodes]);
 * 3. otherwise it is a [Movie], with whatever `streams` it carried — possibly
 *    none. A body with neither episodes nor streams is a movie, not an empty
 *    series.
 */
sealed interface ItemDetail {
    val provider: String
    val title: String
    val posterUrl: String?

    /** `movie`, `tv`, … as the provider reported it; null when absent or blank. */
    val mediaType: String?

    /** `description`, else `summary`; null when the provider sent neither. */
    val description: String?

    /**
     * The title's own page as the provider reported it (`detailUrl`), or null.
     * For display and the progress body's `detailUrl`; never a resume key.
     */
    val detailUrl: String?

    /** A movieffm TV hub: pick a season, then fetch that URL for its episodes. */
    data class Seasons(
        override val provider: String,
        override val title: String,
        override val posterUrl: String?,
        /** Never empty, and every entry has a non-blank url. */
        val seasons: List<SeasonRef>,
        override val mediaType: String? = null,
        override val description: String? = null,
        override val detailUrl: String? = null,
    ) : ItemDetail

    /** A season, or a series whose single page holds every episode. */
    data class Episodes(
        override val provider: String,
        override val title: String,
        override val posterUrl: String?,
        val episodes: List<String>,
        /**
         * The `seasonUrl` key exactly as sent (null when absent or blank).
         * **This is the resume key**: progress rows are matched on
         * `progressKey(seasonUrl, episode)` with this value and recorded with
         * it, as the web app (`App.jsx`: `detail.seasonUrl || null`) and Tizen
         * do. Single-page providers (777tv, dramasq, chinaq) send no seasonUrl,
         * so their rows are keyed on "" — not on [detailUrl].
         */
        val seasonUrl: String? = null,
        override val mediaType: String? = null,
        override val description: String? = null,
        override val detailUrl: String? = null,
    ) : ItemDetail {
        /**
         * What `/api/sources` is fetched against: whichever of seasonUrl /
         * detailUrl the provider supplied. **Not a progress key** — use
         * [seasonUrl] for that.
         */
        val sourceUrl: String? get() = seasonUrl ?: detailUrl
    }

    /** A movie: streams are already listed and go straight to /api/check-sources. */
    data class Movie(
        override val provider: String,
        override val title: String,
        override val posterUrl: String?,
        /** Empty when the body carried no `streams` key. */
        val streams: List<RawStream>,
        override val mediaType: String? = null,
        override val description: String? = null,
        override val detailUrl: String? = null,
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
    /**
     * Seconds, possibly fractional. Any JSON number decodes: a fractional value
     * must never fail the line (Tizen reads these as floats and rounds).
     */
    val durationSeconds: Double? = null,
    /** Seconds of advertising cut, possibly fractional (`31.6`). Round to show. */
    val adSeconds: Double = 0.0,
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
