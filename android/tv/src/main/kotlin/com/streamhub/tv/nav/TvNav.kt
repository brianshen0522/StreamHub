package com.streamhub.tv.nav

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import com.streamhub.core.model.Source

/**
 * What the Detail screen opens (`nav.rs` `DetailTarget`). Built from a search
 * result, a library row or a cast command.
 */
data class DetailTarget(
    val provider: String,
    /** The item URL as the provider gave it (search result `url` / row `itemUrl`). */
    val url: String,
    val title: String,
    val posterUrl: String = "",
    val mediaType: String = "",
    /** Season and episode the entry point carried. Only a history row means "take me there" ([fromHistory]). */
    val seasonUrl: String? = null,
    val episodeLabel: String? = null,
    val fromHistory: Boolean = false,
    /** The library row this came from (ContinueItem / Favorite / WatchHistoryEntry), for isUnavailable etc. */
    val row: Any? = null,
)

/** What the Player plays (`nav.rs` `PlayTarget`). */
data class PlayTarget(
    val provider: String,
    /** Progress/favourite key fields, sent exactly as received. */
    val itemUrl: String,
    val seasonUrl: String = "",
    val title: String = "",
    val posterUrl: String = "",
    /** "movie" / "tv" as Detail resolved it, "" when unknown (a cast play). */
    val mediaType: String = "",
    /** The season's display label (movieffm hubs), "" otherwise. */
    val seasonLabel: String = "",
    /** "" for a movie. */
    val episodeLabel: String = "",
    /** Every episode label of the season in order, for next/previous. Empty for a movie. */
    val episodes: List<String> = emptyList(),
    /** The source chosen, or null to let the player take the first that arrives. */
    val source: Source? = null,
    /** Where to start, seconds. Null = the resume rules decide. */
    val startSeconds: Double? = null,
    /** Started by a phone over the cast channel. */
    val fromCast: Boolean = false,
)

/** A navigation entry (`nav.rs` `Route`). [memoryKey] is the "what" of the route-memory key. */
sealed interface TvRoute {
    val memoryKey: String

    data object Home : TvRoute {
        override val memoryKey get() = "home"
    }

    data object Search : TvRoute {
        override val memoryKey get() = "search"
    }

    data class Detail(val target: DetailTarget) : TvRoute {
        override val memoryKey get() = "detail:${target.provider}:${target.url}"
    }

    data class Player(val target: PlayTarget) : TvRoute {
        override val memoryKey get() = "player:${target.itemUrl}"
    }
}

/**
 * The navigation stack (`nav.rs`). Starts as `[Home]`. Only the top entry is
 * mounted, so a screen returned to re-mounts, reloads, and restores focus from
 * route memory. Each entry carries an id the shell keys the screen on, so a
 * replace re-mounts even when the route is equal.
 */
@Stable
class TvNav {
    class Entry internal constructor(val id: Long, val route: TvRoute)

    private var nextId = 0L
    private val entries = mutableStateListOf(Entry(nextId++, TvRoute.Home))

    /** The stack, bottom first. */
    val stack: List<TvRoute> get() = entries.map { it.route }

    val depth: Int get() = entries.size

    val top: Entry get() = entries.last()

    val current: TvRoute get() = entries.last().route

    fun push(route: TvRoute) {
        entries.add(Entry(nextId++, route))
    }

    /** Replace the top entry (the next episode, a cast play while playing). */
    fun replace(route: TvRoute) {
        entries[entries.lastIndex] = Entry(nextId++, route)
    }

    /** Pop one level. False at the root, where the caller asks to exit. */
    fun back(): Boolean {
        if (entries.size <= 1) return false
        entries.removeAt(entries.lastIndex)
        return true
    }

    /** `[Home]` again (signing out). */
    fun reset() {
        entries.clear()
        entries.add(Entry(nextId++, TvRoute.Home))
    }
}
