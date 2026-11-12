package com.streamhub.tv.screens.home

import com.streamhub.tv.screens.login.mmss
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The card, hero and id rules of home.rs:60-194, and the login countdown text. */
class HomeCardsTest {
    private fun row(
        id: String = "",
        ep: String = "",
        pos: Double = 0.0,
        dur: Double = 0.0,
        pct: Double = 0.0,
        nextUp: Boolean = false,
        season: String = "",
        seasonUrl: String = "",
        watchedAt: String = "",
        unavailable: Boolean = false,
        reason: String = "",
        item: String = "https://p.test/a",
    ) = LibraryRow(
        id = id, providerKey = "777tv", itemUrl = item, title = "黑暗榮耀", seasonUrl = seasonUrl, seasonLabel = season,
        episodeLabel = ep, positionSeconds = pos, durationSeconds = dur, progressPercent = pct, nextUp = nextUp,
        watchedAt = watchedAt, isUnavailable = unavailable, unavailableReason = reason,
    )

    @Test
    fun continueSubtitlesAndIds() {
        val c = continueCard(row(id = "p1", ep = "01", pos = 1500.0, dur = 3000.0, pct = 50.0))
        assertEquals("cw:p1", c.id)
        assertEquals("第01集 · 剩 25 分鐘", c.subtitle)
        assertEquals(50.0, c.progress!!, 0.0)
        assertNull(c.chip)

        val n = continueCard(row(ep = "3", pos = 100.0, dur = 100.0, nextUp = true))
        assertEquals("cw:777tv:https://p.test/a::3", n.id)
        assertEquals("第3集 已看完 · 接著看下一集", n.subtitle)
        assertNull(n.progress)
        assertEquals("下一集", n.chip)

        // No time left and a non-numeric label: shown as given, no minutes part.
        assertEquals("預告片", continueCard(row(ep = "預告片", pos = 10.0, dur = 10.0)).subtitle)
        // 61 s left rounds up to 2 minutes.
        assertEquals("剩 2 分鐘", continueCard(row(pos = 0.0, dur = 61.0)).subtitle)
    }

    @Test
    fun favouriteAndHistorySubtitles() {
        assertEquals("第 2 季 · 第12集", favoriteCard(row(season = "第2季", ep = "12")).subtitle)
        assertEquals("", favoriteCard(row()).subtitle)
        assertEquals("777tv", favoriteCard(row()).provider)
        val h = historyCard(row(id = "h1", ep = "4", pos = 300.0, dur = 3000.0, watchedAt = "2026-11-03T10:00:00Z"))
        assertEquals("his:h1", h.id)
        assertEquals("第4集 · 2026/11/03", h.subtitle)
        assertEquals(10.0, h.progress!!, 1e-9)
        assertNull(historyCard(row()).progress)
    }

    @Test
    fun badges() {
        assertEquals("已下架", unavailableLabel(row(unavailable = true, reason = "not-found")))
        assertEquals("已無集數", unavailableLabel(row(unavailable = true, reason = "no-episodes")))
        assertNull(unavailableLabel(row(reason = "no-episodes")))
    }

    @Test
    fun historyIsDedupedAndCapped() {
        val rows = (0 until 100).map { row(id = "h$it", ep = (it % 50).toString()) }
        val d = dedupeHistory(rows)
        assertEquals(HISTORY_LIMIT, d.size)
        assertEquals("h0", d.first().id)
        assertEquals(listOf("h0", "h1"), dedupeHistory(listOf(row(id = "h0", ep = "1"), row(id = "h1", ep = "2"), row(id = "h2", ep = "1"))).map { it.id })
    }

    @Test
    fun heroAndTargets() {
        val r = row(id = "p1", ep = "01", pos = 1500.0, dur = 3000.0, pct = 50.0, seasonUrl = "s1")
        val hero = heroOf(continueCard(r), r, RailKind.Continue)
        assertEquals("按 OK 繼續播放", hero.hint)
        assertEquals("777tv", hero.meta)
        assertEquals("第01集 · 剩 25 分鐘", hero.line)
        assertEquals("按 OK 播放下一集", heroOf(continueCard(r.copy(nextUp = true)), r.copy(nextUp = true), RailKind.Continue).hint)
        assertNull(heroOf(historyCard(r), r, RailKind.History).hint)
        assertNull(heroOf(continueCard(r.copy(progressPercent = 0.0)), r, RailKind.Continue).progress)

        val t = targetFromRow(r, fromHistory = true)
        assertEquals("s1", t.seasonUrl)
        assertEquals("01", t.episodeLabel)
        assertEquals(true, t.fromHistory)
        assertNull(targetFromRow(row(), false).seasonUrl)
    }

    @Test
    fun initialHeroPrefersContinueThenFavourites() {
        val c = row(id = "c")
        val f = row(id = "f").copy(title = "做工的人")
        assertEquals("黑暗榮耀", initialHero(Load.Ready(listOf(c)), Load.Ready(listOf(f)))?.title)
        assertEquals("做工的人", initialHero(Load.Ready(emptyList()), Load.Ready(listOf(f)))?.title)
        assertNull(initialHero(Load.Failed("x"), Load.Loading))
    }

    @Test
    fun countdownText() {
        assertEquals("10:00", mmss(600_000))
        assertEquals("9:55", mmss(594_100))
        assertEquals("0:01", mmss(1))
        assertEquals("0:00", mmss(-5))
    }
}
