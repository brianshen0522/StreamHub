package com.streamhub.tv.focus

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.streamhub.tv.RecordingSounds
import com.streamhub.tv.TvShell
import com.streamhub.tv.nav.TvNav
import com.streamhub.tv.sfx.SfxControl
import com.streamhub.tv.sfx.Sound
import com.streamhub.tv.ui.components.TvButton
import com.streamhub.tv.ui.components.TvTextField
import com.streamhub.tv.ui.sample.SampleData
import com.streamhub.tv.ui.sample.SampleHome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The engine against real Compose layout, at API 24 (minSdk) and a recent API:
 * focus, scoring with transforms, groups, reveal, bump, the Back chain, the exit
 * prompt's scope, long press and the text-field caret rules.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], qualifiers = "w960dp-h540dp-land-xhdpi")
class FocusEngineTest {

    @get:Rule
    val compose = createComposeRule()

    private val sounds = RecordingSounds()
    private val nav = TvNav()
    private lateinit var engine: TvFocusEngine

    private fun newEngine() = TvFocusEngine(nav, sounds, clock = { compose.mainClock.currentTime }).also { engine = it }

    private fun settle(ms: Long = 500) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun press(code: Int, holdMs: Long = 0) {
        compose.runOnIdle { engine.keyDown(code, false) }
        if (holdMs > 0) compose.mainClock.advanceTimeBy(holdMs)
        compose.runOnIdle { engine.keyUp(code) }
        settle()
    }

    private val focusedId: String? get() = compose.runOnIdle { engine.focused?.focusId ?: engine.focused?.action }

    private fun sample(opened: MutableList<String> = ArrayList()) {
        newEngine()
        // Without the skeleton rail: its infinite shimmer never lets the clock idle.
        val rails = SampleData.rails.filter { !it.loading }
        compose.setContent {
            TvShell(engine, nav, SfxControl.Silent()) {
                SampleHome(rails, onOpen = { opened.add(it.id) })
            }
        }
        settle()
    }

    @Test
    fun theWatchdogFocusesTheAutofocusCard() {
        sample()
        assertEquals("c:1", focusedId)
        // A watchdog landing is not a choice: nothing is remembered.
        assertTrue(engine.routeMemory().isEmpty())
    }

    @Test
    fun arrowsMoveThroughRailsAndRowsAndSound() {
        sample()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("c:2", focusedId)
        assertEquals(Sound.Move, sounds.last())
        assertEquals(mapOf("1|home" to "c:2"), engine.routeMemory())

        // The rail slid so c:2 sits at the 48 dp start.
        val track = compose.runOnIdle { engine.focused!!.track()!! }
        assertEquals(114f, track.shift)

        // c:2 now sits at x = 48 dp, over f:1.
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("f:1", focusedId)
        val row = compose.runOnIdle { engine.focused!!.row()!! }
        assertTrue(row.isCurrent)

        // At the rail's start: a bump, one thud, nothing wraps.
        sounds.clear()
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("f:1", focusedId)
        assertEquals(Dir.Left, compose.runOnIdle { engine.focused!!.bump })
        assertEquals(listOf(Sound.Edge to false), sounds.played)

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("f:2", focusedId)

        // Back up into the continue rail: the card last focused there.
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("c:2", focusedId)
    }

    @Test
    fun theEndOfARailIsAnEdgeNotADiagonalJump() {
        sample()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("f:1", focusedId)
        repeat(4) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertEquals("f:5", focusedId)
        // The continue rail goes on further right, but on another line.
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("f:5", focusedId)
        assertEquals(Dir.Right, compose.runOnIdle { engine.focused!!.bump })
    }

    @Test
    fun upFromTheRowsReachesTheTopBar() {
        sample()
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("top:search", focusedId)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("top:sfx", focusedId)
        // Down re-enters the continue rail at its remembered card.
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("c:1", focusedId)
    }

    @Test
    fun okClicksOnThePressAndSoundsSelect() {
        val opened = ArrayList<String>()
        sample(opened)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        sounds.clear()
        compose.runOnIdle { engine.keyDown(KeyEvent.KEYCODE_DPAD_CENTER, false) }
        assertEquals(listOf("c:2"), opened)
        assertEquals(Sound.Select, sounds.last())
        compose.runOnIdle { engine.keyUp(KeyEvent.KEYCODE_DPAD_CENTER) }
        assertEquals("no second click on the release", listOf("c:2"), opened)
        // The Play key on a playable card opens it with the Play intent.
        press(KeyEvent.KEYCODE_MEDIA_PLAY)
        assertEquals(listOf("c:2", "c:2"), opened)
    }

    @Test
    fun theBackChainEndsInTheExitPrompt() {
        sample()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        sounds.clear()

        // Back from deep in the rails: the first card, every rail at rest.
        press(KeyEvent.KEYCODE_BACK)
        assertEquals("c:1", focusedId)
        assertEquals(Sound.Back, sounds.last())
        assertFalse(engine.exitPromptOpen)

        // Back again: the exit prompt, focused on 取消.
        press(KeyEvent.KEYCODE_BACK)
        assertTrue(engine.exitPromptOpen)
        assertEquals("exit-cancel", focusedId)
        // Its focus does not touch the screen's route memory.
        assertEquals("c:1", engine.routeMemory()["1|home"])

        // Focus is confined to the modal.
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("exit-cancel", focusedId)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("exit-confirm", focusedId)

        // Back closes it and focus returns where it was.
        press(KeyEvent.KEYCODE_BACK)
        assertFalse(engine.exitPromptOpen)
        assertEquals("c:1", focusedId)
    }

    @Test
    fun heldBackIsIgnored() {
        sample()
        press(KeyEvent.KEYCODE_BACK)
        assertTrue(engine.exitPromptOpen)
        // Repeats of Back are swallowed: the prompt stays open.
        compose.runOnIdle { assertTrue(engine.keyDown(KeyEvent.KEYCODE_BACK, true)) }
        settle()
        assertTrue(engine.exitPromptOpen)
    }

    @Test
    fun confirmingTheExitPromptLeaves() {
        sample()
        var left = false
        engine.onExitApp = { left = true }
        press(KeyEvent.KEYCODE_BACK)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_ENTER)
        assertTrue(left)
    }

    @Test
    fun longPressFiresAfter700msAndTheReleaseDoesNotClick() {
        newEngine()
        val intents = ArrayList<TvIntent>()
        compose.setContent {
            TvShell(engine, nav, SfxControl.Silent()) {
                TvButton("A", onClick = { intents.add(it) }, longPress = true, autofocus = true, modifier = Modifier.padding(40.dp))
            }
        }
        settle()
        assertEquals("A", compose.runOnIdle { engine.focused?.let { "A" } })

        press(KeyEvent.KEYCODE_DPAD_CENTER, holdMs = 300)
        assertEquals(listOf(TvIntent.Open), intents)

        press(KeyEvent.KEYCODE_DPAD_CENTER, holdMs = 800)
        assertEquals(listOf(TvIntent.Open, TvIntent.LongPress), intents)
    }

    @Test
    fun aTextFieldLeavesOnlyFromTheEdgeTheCaretIsAt() {
        newEngine()
        var value by mutableStateOf(TextFieldValue("abc", TextRange(1)))
        var submitted = 0
        compose.setContent {
            TvShell(engine, nav, SfxControl.Silent()) {
                Row(Modifier.padding(40.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    TvTextField(value, { value = it }, onSubmit = { submitted++ }, modifier = Modifier.width(300.dp), autofocus = true, action = "q")
                    TvButton("Go", onClick = {}, action = "go")
                }
            }
        }
        settle()
        assertEquals("q", focusedId)
        assertNotNull(compose.runOnIdle { engine.focused?.textField })

        // Caret in the middle: the field keeps Left and Right.
        compose.runOnIdle { assertFalse(engine.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, false)) }
        compose.runOnIdle { assertFalse(engine.keyDown(KeyEvent.KEYCODE_DPAD_LEFT, false)) }
        // Backspace edits; it is not Back.
        compose.runOnIdle { assertFalse(engine.keyDown(KeyEvent.KEYCODE_DEL, false)) }
        assertFalse(engine.exitPromptOpen)
        // OK submits.
        press(KeyEvent.KEYCODE_ENTER)
        assertEquals(1, submitted)

        // Caret at the end: Right leaves.
        compose.runOnIdle { value = value.copy(selection = TextRange(3)) }
        settle()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("go", focusedId)
    }
}
