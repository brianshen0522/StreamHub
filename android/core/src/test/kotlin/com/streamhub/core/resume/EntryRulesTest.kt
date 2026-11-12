package com.streamhub.core.resume

import com.streamhub.core.model.SeasonRef
import com.streamhub.core.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Test

/** A 1:1 port of the tests in `tizen/core/src/entry.rs`. */
class EntryRulesTest {

    private fun row(season: String, ep: String, done: Boolean, at: String) = WatchProgress(
        providerKey = "",
        title = "",
        itemUrl = "",
        seasonUrl = season,
        episodeLabel = ep,
        isCompleted = done,
        progressPercent = if (done) 100.0 else 40.0,
        lastWatchedAt = at,
    )

    private fun seasons() = listOf(
        SeasonRef(label = "S1", url = "https://x/s1"),
        SeasonRef(label = "S2", url = "https://x/s2"),
    )

    private fun eps() = listOf("EP1", "EP2", "EP3")

    @Test
    fun `favourite season does not override progress`() {
        val p = ResumeRules.progressMap(listOf(row("https://x/s2", "EP1", false, "2026-01-02T00:00:00.000Z")))
        val entry = EntryPoint(seasonUrl = "https://x/s1", episodeLabel = "EP1", fromHistory = false)
        assertEquals("https://x/s2", EntryRules.entrySeason(seasons(), p, entry)!!.url)
    }

    @Test
    fun `history pins its season and episode`() {
        val p = ResumeRules.progressMap(listOf(row("https://x/s2", "EP3", false, "2026-01-02T00:00:00.000Z")))
        val entry = EntryPoint(seasonUrl = "https://x/s1", episodeLabel = "EP2", fromHistory = true)
        assertEquals("https://x/s1", EntryRules.entrySeason(seasons(), p, entry)!!.url)
        assertEquals(
            EpisodePick.Episode("EP2"),
            EntryRules.entryEpisode(eps(), "https://x/s1", p, entry),
        )
    }

    @Test
    fun `favourite episode only on unwatched season`() {
        val entry = EntryPoint(seasonUrl = "", episodeLabel = "EP2", fromHistory = false)
        val none = emptyMap<String, WatchProgress>()
        assertEquals(EpisodePick.Episode("EP2"), EntryRules.entryEpisode(eps(), null, none, entry))
        val p = ResumeRules.progressMap(listOf(row("", "EP3", false, "2026-01-02T00:00:00.000Z")))
        assertEquals(EpisodePick.Episode("EP3"), EntryRules.entryEpisode(eps(), null, p, entry))
    }

    @Test
    fun `finished season is reported`() {
        val p = ResumeRules.progressMap(listOf(row("", "EP3", true, "2026-01-02T00:00:00.000Z")))
        assertEquals(EpisodePick.SeasonFinished, EntryRules.entryEpisode(eps(), null, p, EntryPoint()))
        assertEquals(EpisodePick.Empty, EntryRules.entryEpisode(emptyList(), null, p, EntryPoint()))
    }

    @Test
    fun `next after completed`() {
        val p = ResumeRules.progressMap(listOf(row("", "EP1", true, "2026-01-02T00:00:00.000Z")))
        assertEquals(EpisodePick.Episode("EP2"), EntryRules.entryEpisode(eps(), null, p, EntryPoint()))
    }
}
