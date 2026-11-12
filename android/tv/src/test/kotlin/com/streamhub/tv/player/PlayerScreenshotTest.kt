package com.streamhub.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import com.streamhub.tv.TvShell
import com.streamhub.tv.focus.TvFocusEngine
import com.streamhub.tv.nav.PlayTarget
import com.streamhub.tv.nav.TvNav
import com.streamhub.tv.notoSansCjk
import com.streamhub.tv.sfx.SfxControl
import com.streamhub.tv.ui.theme.LocalTvFontFamily
import com.streamhub.tv.ui.theme.TvColors
import com.streamhub.tv.ui.theme.TvText
import com.streamhub.tv.ui.theme.TvType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every OSD state at 1920×1080, over a stand-in frame (Robolectric has no
 * video), to hold beside the Tizen captures in
 * `scratchpad/tizen-ref/m85-player-*.png` / `p0*.png` / `sfx-picker.png`.
 *
 * Run: ./build-env/gradle.sh :tv:testDebugUnitTest --tests '*PlayerScreenshotTest*'
 * PNGs: android/tv/build/outputs/roborazzi/player_*.png
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-xhdpi")
class PlayerScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val longTitle = "黑暗榮耀：一部名字特別特別長的影集，用來檢查播放器在電視上的截斷"
    private val longSource = "超長線路名稱測試・Ultra HD 1080P 高速穩定備用線路（香港節點）"

    private val target = PlayTarget(
        provider = "dramasq",
        itemUrl = "https://d/1",
        title = longTitle,
        mediaType = "tv",
        episodeLabel = "01",
        episodes = listOf("01", "02"),
    )

    /** The Tizen QA mock's backdrop: a dim radial glow and a caption. */
    @Composable
    private fun StandInFrame() {
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    0f to Color(0xFF22252D),
                    0.55f to Color(0xFF14161B),
                    1f to Color(0xFF07080A),
                ),
            ),
            contentAlignment = Alignment.Center,
        ) {
            TvText(
                "模擬播放器 · 實機會在這裡顯示影片",
                TvType.Body.copy(fontSize = 17.sp, letterSpacing = 0.15.em),
                color = TvColors.Text.copy(alpha = 0.32f),
            )
        }
    }

    private fun shot(name: String, t: PlayTarget = target, focus: PlayerFocusTarget? = null, setup: PlayerUi.() -> Unit) {
        val nav = TvNav()
        val engine = TvFocusEngine(nav, clock = { compose.mainClock.currentTime })
        val ui = PlayerUi(0).apply(setup)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalTvFontFamily provides notoSansCjk()) {
                TvShell(engine, nav, SfxControl.Silent()) {
                    Box(Modifier.fillMaxSize()) {
                        StandInFrame()
                        PlayerOsd(ui, t, PlayerActions.None)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        if (focus != null) {
            compose.runOnIdle { focusPlayer(engine, focus) }
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
        }
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/player_$name.png")
    }

    private fun PlayerUi.playingAt(pos: Long, dur: Long = 1500) {
        status = PlayerState.PLAYING
        buffering = false
        notice = null
        posS = pos
        durS = dur
        sourceLabel = longSource
        driver = "StreamHub (android)"
    }

    @Test
    fun loading() = shot("loading", target.copy(title = "黑暗榮耀")) {
        posS = 754
        sourceLabel = "優質線路"
    }

    @Test
    fun bar() = shot("bar") { playingAt(755) }

    @Test
    fun controls() = shot("controls", focus = PlayerFocusTarget.Toggle) {
        playingAt(757)
        controls = true
        hasNext = true
    }

    @Test
    fun seek() = shot("seek") {
        playingAt(764)
        seekPreview = 943
    }

    @Test
    fun episodes() = shot("episodes") {
        playingAt(755)
        osd = false
        strip = 0
    }

    @Test
    fun sources() = shot("sources", focus = PlayerFocusTarget.PickerCurrent) {
        playingAt(1432)
        osd = false
        picker = true
        currentSource = 0
        this.sources = listOf(SourceRow(longSource, 0, null), SourceRow("南亞線路", 0, null), SourceRow("矽谷線路", 0, null))
    }

    @Test
    fun sourcesAdFree() = shot("sources_adfree", target.copy(title = "黑暗榮耀"), focus = PlayerFocusTarget.PickerCurrent) {
        playingAt(19)
        sourceLabel = ""
        osd = false
        picker = true
        currentSource = 0
        this.sources = listOf(SourceRow("優質線路", 52, "47:07"), SourceRow("矽谷線路", 40, "47:18"), SourceRow("南亞線路", 31, "47:09"))
    }

    @Test
    fun upNext() = shot("up_next") {
        playingAt(1421)
        hasNext = true
        upNext = UpNextCard("下一集 第02集", 79)
    }

    @Test
    fun error() = shot("error", focus = PlayerFocusTarget.Retry) {
        playingAt(0)
        osd = false
        fatal = "PLAYBACK_ERROR"
    }
}
