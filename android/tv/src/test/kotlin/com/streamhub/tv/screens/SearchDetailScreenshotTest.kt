package com.streamhub.tv.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.streamhub.core.model.Favorite
import com.streamhub.core.model.ItemDetail
import com.streamhub.core.model.RawStream
import com.streamhub.core.model.SeasonRef
import com.streamhub.core.model.WatchProgress
import com.streamhub.tv.focus.TvFocusEngine
import com.streamhub.tv.nav.DetailTarget
import com.streamhub.tv.nav.TvNav
import com.streamhub.tv.nav.TvRoute
import com.streamhub.tv.screens.search.SearchSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Search and Detail at 1920×1080 with fake data, for comparison with the
 * Tizen captures (scratchpad tizen-ref/07-10).
 *
 * PNGs: android/tv/build/outputs/roborazzi/{search,detail}_*.png
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-xhdpi")
class SearchDetailScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val nav = TvNav()
    private val catalog = FakeCatalog()
    private lateinit var engine: TvFocusEngine
    private lateinit var remote: Remote

    @Before
    fun setUp() {
        SearchSession.reset()
    }

    @After
    fun tearDown() = SearchSession.reset()

    private fun start(vararg route: TvRoute) {
        route.forEach { nav.push(it) }
        engine = TvFocusEngine(nav, clock = { compose.mainClock.currentTime })
        remote = Remote(compose, engine)
        compose.mainClock.autoAdvance = false
        compose.setContent { ScreensHost(engine, nav, catalog, MemoryRecents(), posters = true) }
        remote.settle(800)
    }

    private fun shot(name: String) {
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun searchFor(q: String) {
        compose.runOnIdle {
            SearchSession.query = androidx.compose.ui.text.input.TextFieldValue(q, androidx.compose.ui.text.TextRange(q.length))
        }
        remote.ok() // OK in the field submits
    }

    @Test
    fun searchSkeletons() {
        start(TvRoute.Search)
        assertEquals("search-input", remote.focusedId)
        searchFor("瑞克和莫蒂")
        remote.settle(600)
        shot("search_skeletons")
    }

    @Test
    fun searchResults() {
        start(TvRoute.Search)
        searchFor("瑞克和莫蒂")
        compose.runOnIdle {
            catalog.answer("movieffm", searchItems("movieffm", 240))
            catalog.answer("777tv", searchItems("777tv", 12))
            catalog.fail("dramasq", "Upstream request failed with status 503")
            catalog.answer("chinaq", emptyList())
        }
        remote.settle(600)
        remote.down()
        remote.right()
        remote.settle(600)
        assertEquals("s:movieffm:https://movieffm.example/v/2", remote.focusedId)
        shot("search_results")
    }

    @Test
    fun searchProviderErrors() {
        start(TvRoute.Search)
        searchFor("想見你")
        compose.runOnIdle {
            catalog.fail("movieffm", "fetch failed: getaddrinfo ENOTFOUND")
            catalog.fail("777tv", "Upstream request failed with status 503")
            catalog.answer("dramasq", searchItems("dramasq", 6, prefix = "想見你"))
            catalog.answer("chinaq", emptyList())
        }
        remote.settle(800)
        shot("search_provider_errors")
    }

    private fun rickAndMorty(): DetailTarget {
        val seasons = listOf(
            SeasonRef("Season 1 2013更新第11集", "s1"),
            SeasonRef("Season 2 201510集全", "s2"),
            SeasonRef("Season 3 2017更新第10集", "s3"),
            SeasonRef("Season 4 2019更新第10集", "s4"),
            SeasonRef("Season 5 2021更新第10集", "s5"),
        )
        catalog.items["rm"] = ItemDetail.Seasons("movieffm", "瑞克和莫蒂", "https://img.example/rm.jpg", seasons, mediaType = "tv")
        catalog.seasonEpisodes["s1"] = (1..11).map { "$it" }
        catalog.progressRows = listOf(
            progress("rm", "s1", "1", completed = true, pos = 1300, dur = 1320, pct = 98.0, at = "2026-11-01T10:00:00Z"),
            progress("rm", "s1", "2", completed = false, pos = 754, dur = 1320, pct = 57.0, at = "2026-11-02T10:00:00Z"),
        )
        return DetailTarget("movieffm", "rm", "瑞克和莫蒂", "https://img.example/rm.jpg", "tv")
    }

    @Test
    fun detailSeries() {
        start(TvRoute.Detail(rickAndMorty()))
        remote.settle(1500)
        assertEquals("detail-play", remote.focusedId)
        shot("detail_seasons")
        remote.down()
        remote.down()
        remote.settle(600)
        assertEquals("ep:2", remote.focusedId)
        shot("detail_episodes")
    }

    @Test
    fun detailMovie() {
        catalog.items["m"] = ItemDetail.Movie(
            "777tv", "天能", "https://img.example/t.jpg",
            listOf(RawStream("HD", null, "https://cdn.example/t.m3u8")),
            mediaType = "movie",
            description = "一名特務在一場跨越時間的任務中，必須學會逆轉時間的法則，以阻止第三次世界大戰的爆發。這部電影由克里斯多福·諾蘭執導，充滿了燒腦的時間倒轉情節。",
        )
        catalog.progressRows = listOf(progress("m", null, null, completed = false, pos = 784, dur = 9000, pct = 9.0))
        catalog.favoriteRows = mutableListOf(Favorite(id = "f1", providerKey = "777tv", title = "天能", itemUrl = "m"))
        start(TvRoute.Detail(DetailTarget("777tv", "m", "天能", "https://img.example/t.jpg", "movie")))
        remote.settle(1500)
        assertEquals("detail-play", remote.focusedId)
        shot("detail_movie")
    }

    @Test
    fun detailNoEpisodes() {
        catalog.items["x"] = ItemDetail.Episodes("dramasq", "不夠善良的我們", null, emptyList())
        start(
            TvRoute.Detail(
                DetailTarget(
                    "dramasq", "x", "不夠善良的我們",
                    row = Favorite(id = "f", providerKey = "dramasq", title = "不夠善良的我們", itemUrl = "x", isUnavailable = true, unavailableReason = "no-episodes"),
                ),
            ),
        )
        remote.settle(1500)
        shot("detail_no_episodes")
    }

    @Test
    fun detailGone() {
        start(TvRoute.Detail(DetailTarget("dramasq", "gone", "天橋上的魔術師")))
        remote.settle(1500)
        assertEquals("search-elsewhere", remote.focusedId)
        shot("detail_gone")
    }
}

fun progress(
    item: String,
    season: String?,
    episode: String?,
    completed: Boolean,
    pos: Int,
    dur: Int,
    pct: Double,
    at: String = "2026-11-01T10:00:00Z",
) = WatchProgress(
    providerKey = "p", title = "t", itemUrl = item, seasonUrl = season, episodeLabel = episode,
    positionSeconds = pos, durationSeconds = dur, progressPercent = pct, isCompleted = completed, lastWatchedAt = at,
)
