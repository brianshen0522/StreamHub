package com.streamhub.core.resume

import com.streamhub.core.model.SeasonRef
import com.streamhub.core.model.WatchProgress

/**
 * How a title was opened: the season and episode the entry point carried, and
 * whether it was a row of *history*.
 *
 * Only history means "take me to that viewing" (the web's `x: 1` view-state
 * flag). A favourite, a continue-watching card or a search result offers its
 * season and episode only as a fallback, and progress decides — see CLAUDE.md
 * "Where a title opens is decided by progress, not by the link".
 */
data class EntryPoint(
    val seasonUrl: String? = null,
    val episodeLabel: String? = null,
    val fromHistory: Boolean = false,
)

/** Which episode an entry point opens in a season. */
sealed interface EpisodePick {
    data class Episode(val label: String) : EpisodePick

    /**
     * Everything in this season is finished: the caller moves to the next
     * season, or back to the start when there is none.
     */
    data object SeasonFinished : EpisodePick

    /** No episodes at all. */
    data object Empty : EpisodePick
}

/**
 * Where a title opens, given the entry point it was opened from. A port of
 * `tizen/core/src/entry.rs`, which mirrors the season/episode choice in
 * `frontend/src/App.jsx` `handleSelectItem`. Built on [ResumeRules]; change all
 * clients together.
 */
object EntryRules {

    /**
     * A history row's own season when the hub still lists it, else the first;
     * otherwise the season holding the latest progress, the entry's season as a
     * fallback, then the first.
     */
    fun entrySeason(
        seasons: List<SeasonRef>,
        progress: Map<String, WatchProgress>,
        entry: EntryPoint,
    ): SeasonRef? {
        val entrySeason = entry.seasonUrl?.takeIf { it.isNotEmpty() }
        if (entry.fromHistory && entrySeason != null) {
            return seasons.firstOrNull { it.url == entrySeason } ?: seasons.firstOrNull()
        }
        return ResumeRules.resumeSeason(seasons, progress, entrySeason)
    }

    /**
     * The entry's episode is honoured when it pins one viewing, or while it
     * cannot contradict progress: on the season it was recorded against, and
     * only if that season has never been watched. A completed one is never
     * reopened.
     *
     * @param seasonUrl the resume key of the season being opened — for a
     *   single-page series the item's `seasonUrl`, normally null.
     */
    fun entryEpisode(
        episodes: List<String>,
        seasonUrl: String?,
        progress: Map<String, WatchProgress>,
        entry: EntryPoint,
    ): EpisodePick {
        if (episodes.isEmpty()) return EpisodePick.Empty

        val seasonWatched = progress.values.any { it.seasonUrl.orEmpty() == seasonUrl.orEmpty() }
        val usePassed = entry.fromHistory ||
            (!seasonWatched && seasonUrl.orEmpty() == entry.seasonUrl.orEmpty())
        if (usePassed) {
            val episode = entry.episodeLabel?.takeIf { it.isNotEmpty() }
            if (episode != null) {
                val done = progress[ResumeRules.progressKey(seasonUrl, episode)]?.isCompleted == true
                if (!done && episode in episodes) return EpisodePick.Episode(episode)
            }
        }
        return ResumeRules.resumeEpisode(episodes, seasonUrl, progress)
            ?.let { EpisodePick.Episode(it) }
            ?: EpisodePick.SeasonFinished
    }
}
