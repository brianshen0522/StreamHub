package com.streamhub.mobile.cast

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.streamhub.core.model.CastPlayRequest
import com.streamhub.core.model.CastPlaybackState
import com.streamhub.core.model.CastReceiver
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The remote screen's taps, at minSdk and a recent API, in portrait and landscape. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class RemoteScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val seeks = mutableListOf<Long>()
    private val handedBack = mutableListOf<Long>()

    private fun tv(state: CastPlaybackState?) =
        CastReceiver(sessionId = "tv", deviceName = "Living room", clientKind = "tv", state = state)

    private val pausedAtOneMinute = CastPlaybackState(
        provider = "dramasq", itemUrl = "https://p/item", title = "Show", episodeLabel = "01",
        positionMs = 60_000, durationMs = 2_700_000, paused = true, hasNext = true,
    )

    private fun mount(
        state: CastPlaybackState?,
        starting: CastPlayRequest? = null,
        lost: Boolean = false,
    ) {
        compose.setContent {
            MaterialTheme {
                RemoteScreen(
                    target = tv(state),
                    lost = lost,
                    paused = state?.paused ?: true,
                    starting = starting,
                    notice = null,
                    ownDeviceName = "Pixel",
                    posterUrl = { null },
                    onTogglePause = { true },
                    onFullscreen = { true },
                    onSeek = { seeks += it; true },
                    onPrevious = { true },
                    onNext = { true },
                    onStop = {},
                    onPlayHere = { handedBack += it },
                    onClearNotice = {},
                    onBack = {},
                    clock = { compose.mainClock.currentTime },
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun repeatedSkipsAccumulate() {
        mount(pausedAtOneMinute)
        // Held still, so the four-second hold cannot lapse between taps.
        compose.mainClock.autoAdvance = false
        repeat(3) {
            compose.onNodeWithContentDescription("Forward 10 seconds").performClick()
            compose.mainClock.advanceTimeBy(100)
        }
        compose.onNodeWithContentDescription("Back 10 seconds").performClick()
        compose.mainClock.advanceTimeBy(100)
        assertEquals(listOf(70_000L, 80_000L, 90_000L, 80_000L), seeks)
        // The bar shows the held target, not the stale report...
        compose.onNodeWithText("1:20").assertExists()
        // ...until the television fails to confirm it.
        compose.mainClock.advanceTimeBy(RemoteTimeline.HOLD_MS + 500)
        compose.onNodeWithText("1:00").assertExists()
    }

    @Test
    fun theTransportWaitsForAPlayToArrive() {
        mount(
            state = null,
            starting = CastPlayRequest(streamUrl = "https://cdn/ep1.m3u8", title = "Show", episodeLabel = "01"),
        )
        compose.onNodeWithText("Show").assertExists()
        compose.onNodeWithText("Starting on").assertExists()
        compose.onNodeWithContentDescription("Forward 10 seconds").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Play").assertIsNotEnabled()
    }

    @Test
    fun aLostDeviceKeepsItsPictureButTakesNoCommands() {
        mount(pausedAtOneMinute, lost = true)
        compose.onNodeWithText("Show").assertExists()
        compose.onNodeWithText("Disconnected", substring = true).assertExists()
        compose.onNodeWithContentDescription("Next episode").assertIsNotEnabled()
        compose.onNodeWithText("Disconnect").assertIsEnabled()
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land")
    fun landscapeKeepsEveryControlReachable() {
        mount(pausedAtOneMinute)
        compose.onNodeWithContentDescription("Next episode").assertIsEnabled()
        compose.onNodeWithText("Play on this phone").performClick()
        assertEquals(listOf(60_000L), handedBack)
    }
}
