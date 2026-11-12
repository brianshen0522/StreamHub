package com.streamhub.tv.player

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.test.junit4.createComposeRule
import com.streamhub.tv.ScreenRig
import androidx.compose.foundation.layout.Row
import com.streamhub.tv.LocalTvNav
import com.streamhub.tv.nav.PlayTarget
import com.streamhub.tv.nav.TvRoute
import com.streamhub.tv.nav.playFromCast
import com.streamhub.tv.ui.components.TvButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The player as mounted by the app — [PlayerLayer] on the nav stack, keys
 * through the focus engine — with a fake engine and data, at sdk 24 and 35.
 * PlayerControllerTest pins the rules on virtual time; this pins the wiring:
 * the OSD's focus targets, the Back chain into the screen below with route
 * memory, the teardown report and a cast `play` over a playing player.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], qualifiers = "w960dp-h540dp-land-xhdpi")
class PlayerBehaviourTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var rig: ScreenRig
    private val engine = FakeEngine()
    private val data = FakeData()
    private val cast = FakeCast { compose.mainClock.currentTime }
    private val background = CoroutineScope(Dispatchers.Unconfined)

    @Before
    fun setUp() {
        rig = ScreenRig(compose)
    }

    /** A stand-in for the screen that opened the player: two buttons, the second one opens it. */
    @Composable
    private fun Opener(target: PlayTarget) {
        val nav = LocalTvNav.current
        Row {
            TvButton("A", onClick = {}, focusId = "a", action = "a", autofocus = true)
            TvButton("Play", onClick = { nav.push(TvRoute.Player(target)) }, focusId = "open", action = "open")
        }
    }

    private fun mount(target: PlayTarget = Harness.TV) {
        rig.setContent {
            key(rig.nav.top.id) {
                when (val route = rig.nav.current) {
                    is TvRoute.Player -> PlayerLayer(
                        target = route.target,
                        engine = engine,
                        data = data,
                        cast = cast,
                        background = background,
                        clock = { compose.mainClock.currentTime + Harness.CLOCK_ORIGIN },
                    ) {}
                    else -> Opener(target)
                }
            }
        }
        rig.settleUntil { rig.focused == "a" }
        rig.right()
        assertEquals("open", rig.focused)
        rig.ok()
        rig.settleUntil { rig.nav.current is TvRoute.Player }
        compose.runOnIdle { data.line("https://cdn/a.m3u8", "A") }
        rig.settleUntil { engine.opened.isNotEmpty() }
        compose.runOnIdle { engine.playing(60_000.0, 1_500_000.0) }
        rig.settle(300)
    }

    /** OK held as a remote holds it: a keydown, repeats every 50 ms, then the keyup. */
    private fun holdOk(ms: Long) {
        val code = KeyEvent.KEYCODE_DPAD_CENTER
        compose.runOnIdle { rig.engine.keyDown(code, false) }
        var held = 0L
        while (held < ms) {
            rig.settle(48)
            held += 48
            if (held >= 400) compose.runOnIdle { rig.engine.keyDown(code, true) }
        }
        compose.runOnIdle { rig.engine.keyUp(code) }
        rig.settle(500)
    }

    @Test
    fun upFocusesTheToggleAndBackPeelsLayersThenReturnsToTheOpener() {
        mount()
        assertEquals("M:https://cdn/a.m3u8", engine.opened.single().first)

        // Up: the pills, focus on 播放/暫停 (§10 item 56).
        rig.up()
        assertEquals("vp:toggle", rig.focused)
        // Back hides them; the player stays.
        rig.back()
        assertTrue(rig.nav.current is TvRoute.Player)

        // OK held 700 ms: the picker, focused on the playing source (item 55).
        compose.runOnIdle { data.line("https://cdn/b.m3u8", "B") }
        rig.settle(200)
        holdOk(800)
        assertEquals("vp:source:0", rig.focused)
        // Back closes the picker onto the controls (closePicker → showControls), Back again hides them.
        rig.back()
        assertEquals("vp:toggle", rig.focused)
        rig.back()
        assertTrue(rig.nav.current is TvRoute.Player)

        // Back leaves: the opener re-mounts and route memory puts focus back on Play.
        val before = data.progress.size
        rig.back()
        rig.settleUntil { rig.focused == "open" }
        assertTrue(rig.nav.current !is TvRoute.Player)
        assertTrue("the leave is reported", data.progress.size > before)
        assertTrue(engine.calls.contains("close"))
    }

    @Test
    fun aCastPlayReplacesAPlayingPlayerAndBackStillReturnsToTheOpener() {
        mount()
        val next = Harness.TV.copy(episodeLabel = "03", source = SourceLine(directUrl = "https://cdn/c.m3u8", sourceLabel = "C"), fromCast = true)
        compose.runOnIdle { rig.nav.playFromCast(next) }
        rig.settleUntil { engine.opened.any { it.first == "M:https://cdn/c.m3u8" } }
        assertEquals(2, rig.nav.depth)
        rig.back()
        rig.settleUntil { rig.focused == "open" }
        assertEquals(1, rig.nav.depth)
        // From any other screen a cast play pushes over it.
        compose.runOnIdle { rig.nav.playFromCast(next) }
        rig.settle(300)
        assertEquals(2, rig.nav.depth)
    }
}
