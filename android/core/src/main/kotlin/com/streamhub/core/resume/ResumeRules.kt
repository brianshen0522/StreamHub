package com.streamhub.core.resume

import com.streamhub.core.model.SeasonRef
import com.streamhub.core.model.WatchProgress
import java.time.Instant

/**
 * Where a title picks up, which episode plays next, and when a season is done.
 *
 * These rules are not obvious and they already exist — this is a port of what
 * the web player does, not a fresh design. Two clients disagreeing about which
 * episode to resume is worse than either rule being wrong, so behaviour changes
 * belong in both places at once.
 *
 * Note that "completed" is decided by the *server* when progress is recorded
 * (at or past 95%, or within 90 seconds of the end) and arrives on the row.
 * Nothing here recomputes it.
 */
object ResumeRules {

    /**
     * Playback restarts slightly before where it stopped, because the last few
     * seconds before someone closed a player are rarely seconds they took in.
     */
    const val RESUME_REWIND_SECONDS = 30

    /** How long before the end the next episode is offered. */
    const val UP_NEXT_LEAD_SECONDS = 120

    /**
     * Progress rows are identified by season and episode together: an episode
     * label like "EP1" repeats across seasons, and a series with no seasons has
     * neither part.
     */
    fun progressKey(seasonUrl: String?, episodeLabel: String?): String =
        "${seasonUrl.orEmpty()}::${episodeLabel.orEmpty()}"

    fun progressMap(entries: List<WatchProgress>): Map<String, WatchProgress> =
        entries.associateBy { progressKey(it.seasonUrl, it.episodeLabel) }

    /**
     * The season to open when a title is picked.
     *
     * A title is opened from several places — a search result, a favourite, a
     * row of history — and only some of them carry a position. None of that
     * should decide where a person lands: what they watched last should, and it
     * is the same answer whichever door they came through. So the season
     * holding the most recent progress wins, and [fallbackSeasonUrl] is
     * consulted only when nothing has been watched.
     *
     * That fallback is what a favourite remembers: the season that was on
     * screen when the heart was tapped. It is a reasonable guess for a title
     * never watched and simply wrong for one that has been, which is why it
     * loses to progress rather than overriding it.
     *
     * Rolling over from a season watched to its end is not decided here — that
     * needs the season's episode list, which the caller fetches next, so
     * [resumeEpisode] handles it.
     */
    fun resumeSeason(
        seasons: List<SeasonRef>,
        progress: Map<String, WatchProgress>,
        fallbackSeasonUrl: String? = null,
    ): SeasonRef? {
        if (seasons.isEmpty()) return null

        val latest = progress.values
            .filter { !it.seasonUrl.isNullOrBlank() }
            .maxByOrNull { it.lastWatchedAt.toEpochMillis() }

        // A season can go missing between watching it and coming back: providers
        // renumber and re-list. Falling through beats landing on nothing.
        return latest?.let { row -> seasons.firstOrNull { it.url == row.seasonUrl } }
            ?: fallbackSeasonUrl?.let { url -> seasons.firstOrNull { it.url == url } }
            ?: seasons.first()
    }

    /**
     * The episode to open when a title is picked.
     *
     * Nothing watched yet starts at the first episode. Otherwise the most
     * recently watched episode decides: if it was finished the next one plays,
     * and if that was the last episode the season is done and this returns null.
     *
     * @return the episode label, or null when the season is fully watched or
     *   there are no episodes at all.
     */
    fun resumeEpisode(
        episodes: List<String>,
        seasonUrl: String?,
        progress: Map<String, WatchProgress>,
    ): String? {
        if (episodes.isEmpty()) return null

        val watched = episodes
            .mapNotNull { progress[progressKey(seasonUrl, it)] }
            .sortedByDescending { it.lastWatchedAt.toEpochMillis() }

        val latest = watched.firstOrNull() ?: return episodes.first()

        if (!latest.isCompleted) return latest.episodeLabel

        val index = episodes.indexOf(latest.episodeLabel)
        // indexOf can miss if the provider renamed a label since it was watched;
        // treating that as "start over" beats crashing or skipping ahead.
        if (index < 0) return episodes.first()
        return episodes.getOrNull(index + 1)
    }

    /**
     * Where to seek on open. Zero means start from the beginning rather than
     * seek — a rewind that lands at or before the start is not worth doing, and
     * seeking to 0 on some players costs an extra buffering round trip.
     *
     * **One deliberate difference from the web player:** a finished episode
     * restarts from zero here. The web applies the same rewind unconditionally,
     * so deliberately reopening a watched episode drops you 30 seconds from the
     * end. That only shows up on a rewatch, because resuming a title normally
     * moves to the next episode instead — but it is still wrong, and worth
     * fixing on the web rather than reproducing here.
     */
    fun resumePositionSeconds(progress: WatchProgress?): Int {
        val position = progress?.positionSeconds ?: return 0
        if (progress.isCompleted) return 0
        return (position - RESUME_REWIND_SECONDS).coerceAtLeast(0)
    }

    /** True once playback is close enough to the end to offer the next episode. */
    fun shouldOfferUpNext(durationSeconds: Int, positionSeconds: Int): Boolean {
        if (durationSeconds <= 0) return false
        return durationSeconds - positionSeconds < UP_NEXT_LEAD_SECONDS
    }

    /**
     * What follows the episode playing now: the next episode, or the next season
     * when this was the last one. Only providers that expose a season list can
     * roll over; the rest simply end.
     */
    fun upNext(
        episodes: List<String>,
        currentEpisode: String?,
        seasons: List<SeasonRef> = emptyList(),
        currentSeasonUrl: String? = null,
    ): UpNext? {
        val index = episodes.indexOf(currentEpisode)
        if (index < 0) return null

        if (index < episodes.lastIndex) {
            return UpNext.Episode(episodes[index + 1])
        }

        val seasonIndex = seasons.indexOfFirst { it.url == currentSeasonUrl }
        if (seasonIndex < 0 || seasonIndex >= seasons.lastIndex) return null
        return UpNext.Season(seasons[seasonIndex + 1])
    }

    /**
     * The episode before this one, or null at the season's front edge. The
     * mirror of [upNext]'s episode case; it does not cross season boundaries,
     * because stepping backwards into a previous season's last episode is
     * rarely what a person reaching for "previous" wants.
     */
    fun previousEpisode(episodes: List<String>, currentEpisode: String?): String? {
        val index = episodes.indexOf(currentEpisode)
        return if (index > 0) episodes[index - 1] else null
    }

    /** How a season reads in a list: untouched, part-watched, or finished. */
    fun seasonStatus(seasonUrl: String?, progress: Map<String, WatchProgress>): SeasonStatus {
        val entries = progress.values.filter { it.seasonUrl.orEmpty() == seasonUrl.orEmpty() }
        if (entries.isEmpty()) return SeasonStatus.UNWATCHED
        if (entries.all { it.isCompleted }) return SeasonStatus.COMPLETED
        if (entries.any { !it.isCompleted && it.progressPercent > 0 }) return SeasonStatus.IN_PROGRESS
        return SeasonStatus.UNWATCHED
    }

    private fun String?.toEpochMillis(): Long =
        this?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: Long.MIN_VALUE
}

sealed interface UpNext {
    data class Episode(val label: String) : UpNext
    data class Season(val season: SeasonRef) : UpNext
}

enum class SeasonStatus { UNWATCHED, IN_PROGRESS, COMPLETED }
