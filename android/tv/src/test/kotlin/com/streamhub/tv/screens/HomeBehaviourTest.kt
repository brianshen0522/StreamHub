package com.streamhub.tv.screens

import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.streamhub.tv.FakeServer
import com.streamhub.tv.Library
import com.streamhub.tv.Library.library
import com.streamhub.tv.ScreenRig
import com.streamhub.tv.focus.TvSelect
import com.streamhub.tv.nav.TvAutoplay
import com.streamhub.tv.nav.TvRoute
import com.streamhub.tv.screens.home.HomeScreen
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch

/** Home's remote behaviour (PARITY §3.2, checklist 10-12, 17, 24-35) at minSdk and a recent API. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], qualifiers = "w960dp-h540dp-land-xhdpi")
class HomeBehaviourTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var rig: ScreenRig
    private var signedOut = false
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    @Before
    fun setUp() {
        rig = ScreenRig(compose)
        ScreenRig.installFakePosters()
    }

    private fun mount() {
        rig.setContent {
            HomeScreen(rig.server.api, userName = "Tizen QA", onSignedOut = { signedOut = true }, libraryChanges = changes)
        }
    }

    private fun loaded() {
        rig.server.library()
        mount()
        rig.settleUntil { rig.focused == "cw:p1" }
        rig.settle(500)
    }

    private fun hasText(t: String) = compose.onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aFreshHomeLandsOnTheFirstCardWithItsHero() {
        loaded()
        assertTrue(hasText("按 OK 繼續播放"))
        assertTrue(hasText("777TV")) // the provider's display name, not its key
    }

    @Test
    fun nothingIsFocusedWhileLoadingAndALateLoadDoesNotStealFocus() {
        val gate = CountDownLatch(1)
        rig.server.library()
        rig.server.on("GET", "/me/continue-watching") {
            gate.await()
            FakeServer.Reply(body = Library.cont())
        }
        mount()
        rig.settle(1_000)
        // [data-loading]: the watchdog does not park focus on 搜尋.
        assertNull(rig.focused)
        // Right wakes 搜尋 and is replayed: 音效.
        rig.right()
        assertEquals("home-sfx", rig.focused)
        gate.countDown()
        rig.settleUntil { hasText("黑暗榮耀") }
        rig.settle(1_000)
        assertEquals("home-sfx", rig.focused)
    }

    @Test
    fun backReturnsToTheFirstCardThenOpensTheExitPrompt() {
        loaded()
        rig.right()
        rig.right()
        rig.down()
        assertEquals("fav:f3", rig.focused)
        rig.back()
        assertEquals("cw:p1", rig.focused)
        rig.back()
        assertTrue(rig.engine.exitPromptOpen)
        assertEquals("exit-cancel", compose.runOnIdle { rig.engine.focused?.action })
        rig.back()
        assertFalse(rig.engine.exitPromptOpen)
        rig.settle(500)
        assertEquals("cw:p1", rig.focused)
    }

    @Test
    fun longPressOpensTheMenuAndRemovesFromContinue() {
        loaded()
        rig.press(KeyEvent.KEYCODE_DPAD_CENTER, holdMs = 800)
        assertEquals("menu-remove", compose.runOnIdle { rig.engine.focused?.action })
        // The release did not open Detail.
        assertEquals(TvRoute.Home, rig.nav.current)
        rig.ok()
        rig.settleUntil { rig.server.count("DELETE", "/me/progress") == 1 }
        val body = rig.server.requests.first { it.first == "DELETE /me/progress" }.second
        assertTrue(body, body.contains("\"scope\":\"title\"") && body.contains("\"title\":\"黑暗榮耀\""))
        rig.settleUntil { hasText("已從繼續觀看移除：黑暗榮耀") }
        rig.settle(500)
        assertTrue(rig.focused != null)
    }

    @Test
    fun backClosesTheMenuAndFocusReturnsToTheCard() {
        loaded()
        rig.right()
        rig.press(KeyEvent.KEYCODE_DPAD_CENTER, holdMs = 800)
        assertEquals("menu-remove", compose.runOnIdle { rig.engine.focused?.action })
        rig.back()
        assertEquals("cw:p2", rig.focused)
    }

    @Test
    fun historyOpensOnOkDownAndPinsItsEpisode() {
        loaded()
        rig.down()
        rig.down()
        assertEquals("his:h1", rig.focused)
        compose.runOnIdle { rig.engine.keyDown(KeyEvent.KEYCODE_DPAD_CENTER, false) }
        val route = compose.runOnIdle { rig.nav.current }
        assertTrue(route is TvRoute.Detail && route.target.fromHistory && route.target.episodeLabel == "1")
    }

    @Test
    fun thePlayKeyRequestsAutoplayAndOpensDetail() {
        loaded()
        rig.press(KeyEvent.KEYCODE_MEDIA_PLAY)
        val route = compose.runOnIdle { rig.nav.current }
        assertTrue(route is TvRoute.Detail && !route.target.fromHistory)
        assertTrue(TvAutoplay.take("777tv", "https://777tv.test/glory"))
    }

    @Test
    fun theRedKeyRemovesAFavouriteAndTogglesOthers() {
        loaded()
        rig.down()
        assertEquals("fav:f1", rig.focused)
        rig.press(KeyEvent.KEYCODE_PROG_RED)
        rig.settleUntil { rig.server.count("DELETE", "/me/favorites/f1") == 1 }
        rig.settleUntil { hasText("已取消收藏：俗女養成記") }

        // A continue card: not a favourite yet, so it is added.
        rig.up()
        assertEquals("cw:p1", rig.focused)
        rig.press(KeyEvent.KEYCODE_PROG_RED)
        rig.settleUntil { rig.server.count("POST", "/me/favorites") == 1 }
        val body = rig.server.requests.first { it.first == "POST /me/favorites" }.second
        assertTrue(body, body.contains("\"mediaType\":\"tv\"") && body.contains("\"episodeLabel\":\"01\""))
    }

    @Test
    fun signOutConfirmBackAndConfirm() {
        loaded()
        compose.runOnIdle { rig.engine.focusFirst(TvSelect.action("signout")) }
        rig.ok()
        assertEquals("signout-cancel", compose.runOnIdle { rig.engine.focused?.action })
        rig.back()
        assertEquals("home-signout", rig.focused)
        rig.ok()
        rig.right()
        assertEquals("signout-confirm", compose.runOnIdle { rig.engine.focused?.action })
        rig.ok()
        rig.settleUntil { signedOut }
    }

    @Test
    fun theSoundToggleFlipsItsLabel() {
        loaded()
        compose.runOnIdle { rig.engine.focusFirst(TvSelect.action("sfx")) }
        assertTrue(hasText("音效 開"))
        rig.ok()
        assertFalse(rig.sfx.enabled)
        assertTrue(hasText("音效 關"))
        rig.ok()
        assertTrue(rig.sfx.enabled)
    }

    @Test
    fun aLibraryEventReloadsAfterASecondOfQuiet() {
        loaded()
        val before = rig.server.count("GET", "/me/continue-watching")
        compose.runOnIdle { changes.tryEmit(Unit) }
        rig.settle(500)
        compose.runOnIdle { changes.tryEmit(Unit) }
        rig.settle(800)
        assertEquals(before, rig.server.count("GET", "/me/continue-watching"))
        rig.settleUntil(3_000) { rig.server.count("GET", "/me/continue-watching") == before + 1 }
        rig.settle(500)
        // A reload that changes nothing moves nothing.
        assertEquals("cw:p1", rig.focused)
    }

    @Test
    fun aFailedRailShowsItsErrorAndRetries() {
        rig.server.library()
        rig.server.down("GET", "/me/favorites")
        mount()
        rig.settleUntil { hasText("無法連線到伺服器") }
        assertEquals(1, rig.server.count("GET", "/me/favorites"))
        rig.settleUntil(2_500) { rig.server.count("GET", "/me/favorites") == 2 }
        rig.server.json("GET", "/me/favorites", Library.favs())
        rig.settleUntil(4_500) { rig.server.count("GET", "/me/favorites") == 3 }
        rig.settleUntil { hasText("俗女養成記") }
    }

    @Test
    fun everythingEmptyShowsTheEmptyLineAndLandsOnSearch() {
        rig.server.library(cont = """{"items":[]}""", favs = """{"favorites":[]}""", hist = """{"history":[]}""")
        mount()
        rig.settleUntil { rig.focused == "home-search" }
        assertTrue(hasText("還沒有內容。按「搜尋」找點東西來看吧。"))
        assertTrue(hasText("歡迎回來"))
    }
}
