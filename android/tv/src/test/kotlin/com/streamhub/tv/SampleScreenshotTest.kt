package com.streamhub.tv

import android.view.KeyEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.streamhub.tv.focus.TvFocusEngine
import com.streamhub.tv.focus.TvSelect
import com.streamhub.tv.nav.TvNav
import com.streamhub.tv.sfx.SfxControl
import com.streamhub.tv.ui.sample.SampleData
import com.streamhub.tv.ui.sample.SampleHome
import com.streamhub.tv.ui.theme.LocalTvFontFamily
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sample screen at 1920×1080 (xhdpi, 960×540 dp), captured with native
 * graphics. Native graphics needs API 26+; the API 24 code path is covered by
 * FocusEngineTest, which runs at sdk 24 too.
 *
 * Run: ./build-env/gradle.sh :tv:testDebugUnitTest --tests '*SampleScreenshotTest*'
 * PNGs: android/tv/build/outputs/roborazzi/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-xhdpi")
class SampleScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun settle(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    @Test
    fun sampleHome() {
        val nav = TvNav()
        val engine = TvFocusEngine(nav, clock = { compose.mainClock.currentTime })
        // The skeleton shimmer is infinite: drive the clock by hand.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalTvFontFamily provides notoSansCjk()) {
                TvShell(engine, nav, SfxControl.Silent()) {
                    SampleHome(SampleData.rails, onOpen = {})
                }
            }
        }
        settle(1000)
        // A rail is still loading ([data-loading]), so the watchdog parks focus
        // nowhere; Home focuses once its data is in, as here.
        compose.runOnIdle { engine.focusFirst(TvSelect.autofocus) }
        settle(1000)
        assertEquals("c:1", compose.runOnIdle { engine.focused?.focusId })
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/sample_home_1080p.png")

        // Two cards right and one row down: the rail and column slide, the focused card lifts.
        for (code in listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN)) {
            compose.runOnIdle { engine.keyDown(code, false); engine.keyUp(code) }
            settle(600)
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/sample_home_moved_1080p.png")

        // Back, Back: rails at rest, then the exit prompt.
        repeat(2) {
            compose.runOnIdle { engine.keyDown(KeyEvent.KEYCODE_BACK, false); engine.keyUp(KeyEvent.KEYCODE_BACK) }
            settle(800)
        }
        assertEquals("exit-cancel", compose.runOnIdle { engine.focused?.action })
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/sample_exit_prompt_1080p.png")
    }
}
