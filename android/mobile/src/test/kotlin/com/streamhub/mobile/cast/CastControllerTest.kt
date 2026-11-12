package com.streamhub.mobile.cast

import com.streamhub.core.model.CastCommand
import com.streamhub.core.model.CastPlaybackState
import com.streamhub.core.model.CastReceiver
import com.streamhub.core.model.DeviceSession
import com.streamhub.core.net.RealtimeEvent
import com.streamhub.mobile.PlaybackRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's controller against a fake socket: what is offered, what is sent, what is shown. */
@OptIn(ExperimentalCoroutinesApi::class)
class CastControllerTest {

    private class Rig(scope: TestScope) {
        val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 16)
        val linkUp = MutableStateFlow(true)
        val ownId = MutableStateFlow<String?>("phone")
        val sent = mutableListOf<Pair<String, CastCommand>>()
        var wire = true
        val cast = CastController(
            events = events,
            linkUp = linkUp,
            ownSessionId = ownId,
            sendCommand = { to, command -> if (wire) sent.add(to to command); wire },
            scope = scope.backgroundScope,
            linkGraceMs = 4_000,
        )

        fun receivers(vararg list: CastReceiver) {
            events.tryEmit(RealtimeEvent.Receivers(list.toList()))
        }
    }

    private fun tv(state: CastPlaybackState? = null, id: String = "tv", name: String = "Living room") =
        CastReceiver(sessionId = id, deviceName = name, clientKind = "tv", state = state)

    private fun playing(
        position: Long = 60_000,
        paused: Boolean = false,
        itemUrl: String = "https://p/item",
        episode: String? = "01",
    ) = CastPlaybackState(
        provider = "dramasq", itemUrl = itemUrl, title = "Show", episodeLabel = episode,
        positionMs = position, durationMs = 2_700_000, paused = paused,
    )

    private fun request(
        directUrl: String = "https://cdn/ep1.m3u8",
        episode: String? = "01",
        resumeAt: Int = 0,
    ) = PlaybackRequest(
        providerKey = "dramasq", mediaType = "tv", title = "Show", posterUrl = null,
        itemUrl = "https://p/item", seasonUrl = null, seasonLabel = null, episodeLabel = episode,
        sourceLabel = "Line 1", directUrl = directUrl, durationSeconds = null, resumeAtSeconds = resumeAt,
    )

    @Test
    fun `this phone is never offered as a target`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.receivers(tv(), tv(id = "phone", name = "Pixel"))
        runCurrent()
        assertEquals(listOf("tv"), rig.cast.receivers.value.map { it.sessionId })
    }

    @Test
    fun `a device that drops stays the target, marked lost, and reattaches`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.receivers(tv(playing()))
        runCurrent()
        rig.cast.connect("tv")
        runCurrent()

        rig.receivers()
        runCurrent()
        // It used to go null, which closed the remote screen on any blip and
        // sent the next Play quietly to the phone.
        assertEquals("tv", rig.cast.target.value?.sessionId)
        assertEquals("Show", rig.cast.target.value?.state?.title)
        assertTrue(rig.cast.lost.value)

        rig.receivers(tv(playing(position = 90_000)))
        runCurrent()
        assertFalse(rig.cast.lost.value)
        assertEquals(90_000L, rig.cast.target.value?.state?.positionMs)
    }

    @Test
    fun `a Play while the device is gone is refused, not played here`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.receivers(tv())
        runCurrent()
        rig.cast.connect("tv")
        rig.receivers()
        runCurrent()

        assertEquals(CastController.PlayOutcome.UNREACHABLE, rig.cast.play(request()))
        assertTrue(rig.sent.isEmpty())
    }

    @Test
    fun `no device chosen means play here, and a download never leaves the phone`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.receivers(tv())
        runCurrent()
        assertEquals(CastController.PlayOutcome.LOCAL, rig.cast.play(request()))

        rig.cast.connect("tv")
        assertEquals(
            CastController.PlayOutcome.LOCAL_ONLY,
            rig.cast.play(request(directUrl = "file:///data/ep1.mp4")),
        )
        assertTrue(rig.sent.isEmpty())
    }

    @Test
    fun `a play carries the position and is shown as starting until the device reports it`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.receivers(tv(playing(itemUrl = "https://p/other")))
        runCurrent()
        rig.cast.connect("tv")

        assertEquals(CastController.PlayOutcome.SENT, rig.cast.play(request(resumeAt = 754)))
        val play = rig.sent.single().second as CastCommand.Play
        assertEquals(754_000L, play.playback.positionMs)
        assertNotNull(rig.cast.starting.value)

        // Still the old title: still starting.
        rig.receivers(tv(playing(itemUrl = "https://p/other")))
        runCurrent()
        assertNotNull(rig.cast.starting.value)

        rig.receivers(tv(playing(position = 754_000)))
        runCurrent()
        assertNull(rig.cast.starting.value)
        assertNull(rig.cast.notice.value)
    }

    @Test
    fun `a play the device never takes up is reported`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.receivers(tv())
        runCurrent()
        rig.cast.connect("tv")
        rig.cast.play(request())

        advanceTimeBy(CastController.START_TIMEOUT_MS + 1)
        runCurrent()
        assertNull(rig.cast.starting.value)
        assertEquals("Living room did not start Show.", rig.cast.notice.value)
    }

    @Test
    fun `play-pause shows the press at once and holds it against stale reports`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.receivers(tv(playing()))
        runCurrent()
        rig.cast.connect("tv")
        runCurrent()
        assertFalse(rig.cast.paused.value)

        assertTrue(rig.cast.togglePause())
        runCurrent()
        assertEquals(CastCommand.Pause, rig.sent.last().second)
        assertTrue("optimistic", rig.cast.paused.value)

        // The heartbeat sent before the television saw the pause.
        rig.receivers(tv(playing(position = 61_000)))
        runCurrent()
        assertTrue("a stale report does not flip it back", rig.cast.paused.value)

        // A second tap before confirmation is a resume, not another pause.
        assertTrue(rig.cast.togglePause())
        runCurrent()
        assertEquals(CastCommand.Resume, rig.sent.last().second)
        assertFalse(rig.cast.paused.value)
    }

    @Test
    fun `an unconfirmed press falls back to what the device reports`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.receivers(tv(playing()))
        runCurrent()
        rig.cast.connect("tv")
        runCurrent()

        rig.cast.togglePause()
        runCurrent()
        assertTrue(rig.cast.paused.value)
        advanceTimeBy(CastController.ASSUME_MS + 1)
        runCurrent()
        assertFalse("the television never paused; say so", rig.cast.paused.value)
    }

    @Test
    fun `a command that cannot be sent says so and changes nothing`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.receivers(tv(playing()))
        runCurrent()
        rig.cast.connect("tv")
        runCurrent()
        rig.wire = false

        assertFalse(rig.cast.togglePause())
        runCurrent()
        assertFalse("no optimistic state for a press that went nowhere", rig.cast.paused.value)
        assertEquals("Living room is not reachable right now.", rig.cast.notice.value)

        rig.wire = true
        assertTrue(rig.cast.seekTo(10_000))
        assertNull("a later success clears it", rig.cast.notice.value)
    }

    @Test
    fun `this phone's socket down past the grace makes the list stale`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.receivers(tv(playing()))
        runCurrent()
        rig.cast.connect("tv")
        runCurrent()

        rig.linkUp.value = false
        advanceTimeBy(2_000)
        runCurrent()
        assertFalse("a quick reconnect (token renewal) does not flash disconnected", rig.cast.lost.value)

        advanceTimeBy(2_500)
        runCurrent()
        assertTrue(rig.cast.receivers.value.isEmpty())
        assertTrue(rig.cast.lost.value)
        assertFalse("nothing is sent into a dead socket", rig.cast.next())

        // The ready after the reconnect carries the list.
        rig.linkUp.value = true
        rig.receivers(tv(playing()))
        runCurrent()
        assertFalse(rig.cast.lost.value)
    }

    @Test
    fun `stop sends stop, disconnect does not`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.receivers(tv(playing()))
        runCurrent()
        rig.cast.connect("tv")
        rig.cast.disconnect()
        runCurrent()
        assertTrue(rig.sent.isEmpty())
        assertNull(rig.cast.target.value)

        rig.cast.connect("tv")
        rig.cast.stopAndDisconnect()
        runCurrent()
        assertEquals(listOf("tv" to CastCommand.Stop), rig.sent)
        assertNull(rig.cast.target.value)
    }

    @Test
    fun `play here replays the phone's request only while the device is still on it`() {
        val last = request(resumeAt = 10)
        val same = CastController.handBack(playing(position = 125_500), last, positionMs = 126_400)
        assertEquals(126, (same as CastController.HandBack.Play).request.resumeAtSeconds)

        // The television moved to the next episode on its own.
        val moved = CastController.handBack(playing(episode = "02"), last, positionMs = 0)
        val open = moved as CastController.HandBack.Open
        assertEquals("https://p/item", open.selection.itemUrl)
        assertEquals("tv", open.selection.mediaType)

        // Idle: nothing to hand back, and certainly not the stale request.
        assertEquals(CastController.HandBack.Nothing, CastController.handBack(null, last, 0))
        assertEquals(CastController.HandBack.Nothing, CastController.handBack(CastPlaybackState(), last, 0))
    }

    @Test
    fun `a movie's empty episode matches the phone's null`() {
        val state = playing(episode = "")
        val last = request(episode = null)
        assertTrue(CastController.handBack(state, last, 0) is CastController.HandBack.Play)
        assertTrue(CastController.isShowing(state, CastController.playRequest(last)))
    }

    @Test
    fun `not connected lists each television once, and not one already listed`() {
        val sessions = listOf(
            DeviceSession(id = "s1", deviceName = "Living room", clientKind = "tv", lastSeenAt = "2026-11-01"),
            DeviceSession(id = "s2", deviceName = "Bedroom", clientKind = "tv", lastSeenAt = "2026-11-02"),
            DeviceSession(id = "s3", deviceName = "Bedroom", clientKind = "tv", lastSeenAt = "2026-11-03"),
            DeviceSession(id = "s4", deviceName = "Pixel", clientKind = "android"),
        )
        val shown = notConnectedDevices(sessions, receivers = listOf(tv(id = "tv", name = "Living room")), lostTarget = null)
        assertEquals(listOf("s3"), shown.map { it.id })

        val gone = notConnectedDevices(sessions, receivers = emptyList(), lostTarget = tv(id = "s3", name = "Bedroom"))
        assertEquals(listOf("s1"), gone.map { it.id })
    }

    @Test
    fun `a receiver row says whether it is playing or paused`() {
        assertEquals("Ready", receiverDetail(tv()))
        assertEquals("Playing Show", receiverDetail(tv(playing())))
        assertEquals("Paused · Show", receiverDetail(tv(playing(paused = true))))
    }
}
