package com.streamhub.core.resume

import com.streamhub.core.model.SeasonRef
import com.streamhub.core.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResumeRulesTest {

    private val season = "http://x/season-1"
    private val episodes = listOf("EP1", "EP2", "EP3")

    private fun progress(
        episode: String,
        seasonUrl: String? = season,
        completed: Boolean = false,
        position: Int = 0,
        percent: Double = 0.0,
        watchedAt: String = "2026-08-01T00:00:00.000Z",
    ) = WatchProgress(
        providerKey = "movieffm",
        title = "Show",
        itemUrl = "http://x/item",
        seasonUrl = seasonUrl,
        episodeLabel = episode,
        durationSeconds = 2820,
        positionSeconds = position,
        progressPercent = percent,
        isCompleted = completed,
        lastWatchedAt = watchedAt,
    )

    private fun map(vararg entries: WatchProgress) = ResumeRules.progressMap(entries.toList())

    // ── which season ────────────────────────────────────────────────────────

    private val s1 = SeasonRef("Season 1", "http://x/season-1")
    private val s5 = SeasonRef("Season 5", "http://x/season-5")
    private val seasons = listOf(s1, SeasonRef("Season 2", "http://x/season-2"), s5)

    @Test
    fun `nothing watched opens the first season`() {
        assertEquals(s1, ResumeRules.resumeSeason(seasons, emptyMap()))
    }

    @Test
    fun `the season watched most recently wins`() {
        val progress = map(
            progress("EP3", seasonUrl = s1.url, watchedAt = "2026-08-01T00:00:00.000Z"),
            progress("EP6", seasonUrl = s5.url, watchedAt = "2026-08-20T00:00:00.000Z"),
        )
        assertEquals(s5, ResumeRules.resumeSeason(seasons, progress))
    }

    /** The reported bug: a favourite made on season one pinned every later visit to it. */
    @Test
    fun `progress beats the season a favourite remembers`() {
        val progress = map(progress("EP6", seasonUrl = s5.url, watchedAt = "2026-08-20T00:00:00.000Z"))
        assertEquals(s5, ResumeRules.resumeSeason(seasons, progress, fallbackSeasonUrl = s1.url))
    }

    @Test
    fun `the remembered season is used when nothing has been watched`() {
        assertEquals(s5, ResumeRules.resumeSeason(seasons, emptyMap(), fallbackSeasonUrl = s5.url))
    }

    @Test
    fun `a season that no longer exists falls through to the first`() {
        val progress = map(progress("EP1", seasonUrl = "http://x/season-gone"))
        assertEquals(s1, ResumeRules.resumeSeason(seasons, progress, fallbackSeasonUrl = "http://x/also-gone"))
    }

    @Test
    fun `progress carrying no season is ignored rather than blanking the choice`() {
        val progress = map(progress("EP1", seasonUrl = null, watchedAt = "2026-08-20T00:00:00.000Z"))
        assertEquals(s5, ResumeRules.resumeSeason(seasons, progress, fallbackSeasonUrl = s5.url))
    }

    @Test
    fun `no seasons at all resumes nothing`() {
        assertNull(ResumeRules.resumeSeason(emptyList(), emptyMap(), fallbackSeasonUrl = s1.url))
    }

    // ── which episode ───────────────────────────────────────────────────────

    @Test
    fun `nothing watched starts at the first episode`() {
        assertEquals("EP1", ResumeRules.resumeEpisode(episodes, season, emptyMap()))
    }

    @Test
    fun `an unfinished episode is resumed, not skipped`() {
        val progress = map(progress("EP2", position = 600, percent = 21.0))
        assertEquals("EP2", ResumeRules.resumeEpisode(episodes, season, progress))
    }

    @Test
    fun `a finished episode advances to the next`() {
        val progress = map(progress("EP2", completed = true, position = 2820, percent = 100.0))
        assertEquals("EP3", ResumeRules.resumeEpisode(episodes, season, progress))
    }

    @Test
    fun `the most recently watched episode decides, not the furthest along`() {
        val progress = map(
            progress("EP3", completed = true, watchedAt = "2026-08-01T10:00:00.000Z"),
            // Watched later, and only part way — this is where the user is.
            progress("EP1", position = 300, percent = 10.0, watchedAt = "2026-08-02T09:00:00.000Z"),
        )
        assertEquals("EP1", ResumeRules.resumeEpisode(episodes, season, progress))
    }

    @Test
    fun `finishing the last episode means the season is done`() {
        val progress = map(progress("EP3", completed = true))
        assertNull(ResumeRules.resumeEpisode(episodes, season, progress))
    }

    @Test
    fun `progress from another season is ignored`() {
        val other = map(progress("EP2", seasonUrl = "http://x/season-2", completed = true))
        assertEquals("EP1", ResumeRules.resumeEpisode(episodes, season, other))
    }

    @Test
    fun `a series with no season url still resumes`() {
        val progress = map(progress("EP2", seasonUrl = null, position = 100, percent = 4.0))
        assertEquals("EP2", ResumeRules.resumeEpisode(episodes, null, progress))
    }

    @Test
    fun `a renamed episode label starts the season over rather than failing`() {
        val progress = map(progress("EP2 (old label)", completed = true))
        assertEquals("EP1", ResumeRules.resumeEpisode(episodes, season, progress))
    }

    @Test
    fun `no episodes means nothing to resume`() {
        assertNull(ResumeRules.resumeEpisode(emptyList(), season, emptyMap()))
    }

    // ── where in the episode ────────────────────────────────────────────────

    @Test
    fun `resuming rewinds a little`() {
        assertEquals(570, ResumeRules.resumePositionSeconds(progress("EP1", position = 600)))
    }

    @Test
    fun `a position inside the rewind window starts from the beginning`() {
        assertEquals(0, ResumeRules.resumePositionSeconds(progress("EP1", position = 12)))
        assertEquals(0, ResumeRules.resumePositionSeconds(null))
    }

    @Test
    fun `rewatching a finished episode starts from the beginning`() {
        // Deliberately unlike the web player, which would land 30s from the end.
        val finished = progress("EP1", completed = true, position = 2820, percent = 100.0)
        assertEquals(0, ResumeRules.resumePositionSeconds(finished))
    }

    // ── what plays next ─────────────────────────────────────────────────────

    @Test
    fun `up next is offered only near the end`() {
        assertEquals(false, ResumeRules.shouldOfferUpNext(2820, 2600))
        assertEquals(true, ResumeRules.shouldOfferUpNext(2820, 2701))
        // A seek backwards withdraws the offer again.
        assertEquals(false, ResumeRules.shouldOfferUpNext(2820, 2700))
        // Unknown duration cannot be near anything.
        assertEquals(false, ResumeRules.shouldOfferUpNext(0, 100))
    }

    @Test
    fun `up next is the following episode`() {
        assertEquals(UpNext.Episode("EP2"), ResumeRules.upNext(episodes, "EP1"))
    }

    @Test
    fun `the last episode rolls over to the next season`() {
        val seasons = listOf(SeasonRef("S1", season), SeasonRef("S2", "http://x/season-2"))

        val next = ResumeRules.upNext(episodes, "EP3", seasons, season)

        assertEquals(UpNext.Season(SeasonRef("S2", "http://x/season-2")), next)
    }

    @Test
    fun `the last episode of the last season ends`() {
        val seasons = listOf(SeasonRef("S1", season))
        assertNull(ResumeRules.upNext(episodes, "EP3", seasons, season))
    }

    @Test
    fun `a provider with no season list simply ends`() {
        assertNull(ResumeRules.upNext(episodes, "EP3"))
    }

    @Test
    fun `an episode that is not in the list has no successor`() {
        assertNull(ResumeRules.upNext(episodes, "EP99"))
    }

    // ── season status ───────────────────────────────────────────────────────

    @Test
    fun `season status reflects the episodes touched`() {
        assertEquals(SeasonStatus.UNWATCHED, ResumeRules.seasonStatus(season, emptyMap()))

        assertEquals(
            SeasonStatus.COMPLETED,
            ResumeRules.seasonStatus(season, map(progress("EP1", completed = true), progress("EP2", completed = true))),
        )

        assertEquals(
            SeasonStatus.IN_PROGRESS,
            ResumeRules.seasonStatus(season, map(progress("EP1", completed = true), progress("EP2", percent = 40.0))),
        )

        // Started but with nothing actually watched yet reads as untouched.
        assertEquals(
            SeasonStatus.UNWATCHED,
            ResumeRules.seasonStatus(season, map(progress("EP1", percent = 0.0))),
        )
    }

    @Test
    fun `season status only counts its own season`() {
        val other = map(progress("EP1", seasonUrl = "http://x/season-2", completed = true))
        assertEquals(SeasonStatus.UNWATCHED, ResumeRules.seasonStatus(season, other))
    }
}
