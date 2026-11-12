package com.streamhub.tv.screens

import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import com.streamhub.core.model.ItemDetail
import com.streamhub.core.model.SeasonRef
import com.streamhub.core.model.WatchHistoryEntry
import com.streamhub.tv.focus.TvFocusEngine
import com.streamhub.tv.nav.DetailTarget
import com.streamhub.tv.nav.TvAutoplay
import com.streamhub.tv.nav.TvNav
import com.streamhub.tv.nav.TvRoute
import com.streamhub.tv.screens.search.SearchSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Search and Detail remote behaviour against real layout, at minSdk and a
 * recent API: Down from the field reaches every provider rail, Up from the
 * first rail returns to the field, the Back chains, and focus restored on the
 * way back from Detail and from the player.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], qualifiers = "w960dp-h540dp-land-xhdpi")
class SearchDetailFocusTest {

    @get:Rule
    val compose = createComposeRule()

    private val nav = TvNav()
    private val catalog = FakeCatalog()
    private val recents = MemoryRecents()
    private lateinit var engine: TvFocusEngine
    private lateinit var remote: Remote

    @Before
    fun setUp() = SearchSession.reset()

    @After
    fun tearDown() = SearchSession.reset()

    private fun start(vararg route: TvRoute) {
        route.forEach { nav.push(it) }
        engine = TvFocusEngine(nav, clock = { compose.mainClock.currentTime })
        remote = Remote(compose, engine)
        compose.mainClock.autoAdvance = false
        compose.setContent { ScreensHost(engine, nav, catalog, recents) }
        remote.settle(800)
    }

    /** Search, with movieffm (45), 777tv (12), dramasq failed, chinaq (5). */
    private fun searchWithResults() {
        start(TvRoute.Search)
        assertEquals("search-input", remote.focusedId)
        compose.runOnIdle { SearchSession.query = TextFieldValue("rick") }
        remote.ok()
        compose.runOnIdle {
            catalog.answer("movieffm", searchItems("movieffm", 45))
            catalog.answer("777tv", searchItems("777tv", 12))
            catalog.fail("dramasq", "boom")
            catalog.answer("chinaq", searchItems("chinaq", 5))
        }
        remote.settle(600)
    }

    @Test
    fun downFromTheFieldReachesEveryProviderRail() {
        searchWithResults()
        remote.down()
        assertEquals("s:movieffm:https://movieffm.example/v/1", remote.focusedId)
        remote.down()
        assertTrue(remote.focusedId!!.startsWith("s:777tv:"))
        // dramasq failed: a collapsed row with nothing to focus, skipped.
        remote.down()
        assertTrue(remote.focusedId!!.startsWith("s:chinaq:"))
        remote.up()
        assertTrue(remote.focusedId!!.startsWith("s:777tv:"))
        remote.up()
        assertTrue(remote.focusedId!!.startsWith("s:movieffm:"))
        // Up from the first rail lands on the field, even from deep in the rail
        // where 搜尋 is nearer.
        repeat(9) { remote.right() }
        assertEquals("s:movieffm:https://movieffm.example/v/10", remote.focusedId)
        remote.up()
        assertEquals("search-input", remote.focusedId)
    }

    @Test
    fun backFromTheResultsGoesToTheFieldThenLeaves() {
        start(TvRoute.Search)
        compose.runOnIdle { nav.reset() }
        remote.settle()
        compose.runOnIdle { nav.push(TvRoute.Search) }
        remote.settle(800)
        compose.runOnIdle { SearchSession.query = TextFieldValue("rick") }
        remote.ok()
        compose.runOnIdle {
            catalog.providerList.forEach { catalog.answer(it.key, searchItems(it.key, 12)) }
        }
        remote.settle(600)
        remote.down()
        remote.down()
        repeat(4) { remote.right() }
        assertTrue(remote.focusedId!!.startsWith("s:777tv:"))
        remote.back()
        assertEquals("search-input", remote.focusedId)
        assertEquals(2, compose.runOnIdle { nav.depth })
        remote.back()
        assertEquals(1, compose.runOnIdle { nav.depth })
        // Search left the stack for good: its state is dropped.
        assertTrue(compose.runOnIdle { SearchSession.groups.isEmpty() && SearchSession.query.text.isEmpty() })
    }

    @Test
    fun backFromDetailRestoresTheSameCardPastTheFirstPage() {
        searchWithResults()
        catalog.items["https://movieffm.example/v/36"] = ItemDetail.Movie("movieffm", "x", null, emptyList())
        remote.down()
        // 35 to the right: past the first 30-card page, which grows ahead of focus.
        repeat(35) { remote.right() }
        assertEquals("s:movieffm:https://movieffm.example/v/36", remote.focusedId)
        remote.ok()
        assertTrue(compose.runOnIdle { nav.current is TvRoute.Detail })
        remote.settle(800)
        assertEquals("detail-play", remote.focusedId)
        remote.back()
        remote.settle(600)
        assertTrue(compose.runOnIdle { nav.current is TvRoute.Search })
        assertEquals("s:movieffm:https://movieffm.example/v/36", remote.focusedId)
        // The results survived the round trip.
        assertEquals(4, compose.runOnIdle { SearchSession.groups.size })
    }

    @Test
    fun recentSearchesShowAsChipsWhenThereAreNoResults() {
        recents.raw = """["想見你","瑞克和莫蒂"]"""
        start(TvRoute.Search)
        assertEquals("search-input", remote.focusedId)
        remote.down()
        assertEquals("recent:想見你", remote.focusedId)
        remote.right()
        assertEquals("recent:瑞克和莫蒂", remote.focusedId)
        remote.up()
        assertEquals("search-input", remote.focusedId)
        // Back into the rail: the chip last focused there (group memory).
        remote.down()
        assertEquals("recent:瑞克和莫蒂", remote.focusedId)
        remote.ok()
        assertEquals("瑞克和莫蒂", compose.runOnIdle { SearchSession.query.text })
        // Searched again: moved to the front, not duplicated.
        assertEquals("""["瑞克和莫蒂","想見你"]""", recents.raw)
    }

    private fun series(): DetailTarget {
        catalog.items["rm"] = ItemDetail.Seasons(
            "movieffm", "瑞克和莫蒂", null,
            listOf(SeasonRef("Season 1", "s1"), SeasonRef("Season 2", "s2")),
        )
        catalog.seasonEpisodes["s1"] = (1..6).map { "$it" }
        catalog.seasonEpisodes["s2"] = (1..4).map { "$it" }
        catalog.progressRows = listOf(progress("rm", "s1", "3", completed = false, pos = 400, dur = 1300, pct = 30.0))
        return DetailTarget("movieffm", "rm", "瑞克和莫蒂")
    }

    @Test
    fun detailEntersAtTheResumeEpisodeAndRestoresFocusFromThePlayer() {
        start(TvRoute.Detail(series()))
        assertEquals("detail-play", remote.focusedId)
        remote.down()
        assertEquals("season:s1", remote.focusedId)
        remote.down()
        // The current (is-selected) episode, the first time the rail is entered.
        assertEquals("ep:3", remote.focusedId)
        remote.right()
        assertEquals("ep:4", remote.focusedId)
        remote.ok()
        val play = compose.runOnIdle { (nav.current as TvRoute.Player).target }
        assertEquals("ep:4 plays from 0", 0.0, play.startSeconds!!, 0.0)
        assertEquals("4", play.episodeLabel)
        assertEquals("s1", play.seasonUrl)
        assertEquals("Season 1", play.seasonLabel)
        assertEquals("tv", play.mediaType)
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), play.episodes)
        assertEquals("rm", play.itemUrl)
        // Back from the player: Detail remounts, reloads, and focus returns to the episode.
        val loadsBefore = catalog.itemCalls.size
        remote.back()
        remote.settle(800)
        assertEquals(loadsBefore + 1, catalog.itemCalls.size)
        assertEquals("ep:4", remote.focusedId)
    }

    @Test
    fun detailBackChainAndPlayKey() {
        start(TvRoute.Search, TvRoute.Detail(series()))
        remote.down()
        remote.down()
        remote.right()
        remote.back()
        assertEquals("detail-play", remote.focusedId)
        // Play anywhere plays with the resume rules: episode 3 at 400 − 30 s.
        remote.down()
        remote.press(KeyEvent.KEYCODE_MEDIA_PLAY)
        val play = compose.runOnIdle { (nav.current as TvRoute.Player).target }
        assertEquals("3", play.episodeLabel)
        assertEquals(370.0, play.startSeconds!!, 0.0)
        remote.back()
        remote.settle(800)
        assertEquals("season:s1", remote.focusedId)
        remote.back()
        assertEquals("detail-play", remote.focusedId)
        remote.back()
        assertTrue(compose.runOnIdle { nav.current is TvRoute.Search })
    }

    @Test
    fun historyEntryOpensItsOwnEpisodeAndSeasonPickResumesWithin() {
        val t = series().copy(
            seasonUrl = "s2", episodeLabel = "2", fromHistory = true,
            row = WatchHistoryEntry(id = "h", providerKey = "movieffm", title = "瑞克和莫蒂", itemUrl = "rm"),
        )
        start(TvRoute.Detail(t))
        assertEquals("detail-play", remote.focusedId)
        remote.down()
        assertEquals("season:s2", remote.focusedId)
        remote.left()
        assertEquals("season:s1", remote.focusedId)
        remote.ok()
        remote.settle(600)
        remote.down()
        // Season 1 picked by hand resumes within itself: episode 3.
        assertEquals("ep:3", remote.focusedId)
    }

    @Test
    fun playKeyOnASearchCardAutoplaysAndBackReturnsToSearch() {
        searchWithResults()
        catalog.items["https://chinaq.example/v/1"] = ItemDetail.Episodes("chinaq", "x", null, listOf("01", "02"))
        remote.down()
        remote.down()
        remote.down()
        assertEquals("s:chinaq:https://chinaq.example/v/1", remote.focusedId)
        remote.press(KeyEvent.KEYCODE_MEDIA_PLAY, 800)
        remote.settle(800)
        // Detail replaced itself with the player: Back returns to Search.
        val play = compose.runOnIdle { (nav.current as TvRoute.Player).target }
        assertEquals("01", play.episodeLabel)
        assertEquals(3, compose.runOnIdle { nav.depth })
        remote.back()
        remote.settle(800)
        assertTrue(compose.runOnIdle { nav.current is TvRoute.Search })
        assertEquals("s:chinaq:https://chinaq.example/v/1", remote.focusedId)
        assertEquals(false, TvAutoplay.take("chinaq", "https://chinaq.example/v/1"))
    }
}
