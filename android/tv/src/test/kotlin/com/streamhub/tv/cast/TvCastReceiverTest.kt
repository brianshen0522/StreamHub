package com.streamhub.tv.cast

import com.streamhub.core.model.CastCommand
import com.streamhub.core.model.CastPlayRequest
import com.streamhub.core.model.CastPlaybackState
import com.streamhub.core.net.RealtimeEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The receiver half of `realtime.rs`: announce, re-announce, library refetch, commands, driver, heartbeat. */
@OptIn(ExperimentalCoroutinesApi::class)
class TvCastReceiverTest {

    private class Rig(scope: TestScope) {
        val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 16)
        val connected = MutableStateFlow(false)
        val sent = ArrayList<CastPlaybackState?>()
        var token: String? = null
        var beats = 0
        val receiver = TvCastReceiver(
            events = events,
            connected = connected,
            send = { sent += it; connected.value },
            scope = scope.backgroundScope,
            main = StandardTestDispatcher(scope.testScheduler),
            clock = { scope.testScheduler.currentTime + 1_000_000 },
            sessionToken = { token },
            heartbeat = { beats += 1 },
        )
    }

    private val playing = CastPlaybackState(title = "T", positionMs = 5_000, durationMs = 60_000)

    @Test
    fun announcesIdleOnTheFirstReadyAndTheLastStateOnEveryReconnect() = runTest {
        val r = Rig(this)
        runCurrent()
        r.connected.value = true
        runCurrent()
        assertEquals(listOf<CastPlaybackState?>(null), r.sent)
        assertEquals(0L, r.receiver.libraryVersion.value)

        r.receiver.publish(playing)
        r.connected.value = false
        runCurrent()
        // Published while down: remembered, sent again on the next ready.
        val later = playing.copy(positionMs = 9_000)
        r.receiver.publish(later)
        r.connected.value = true
        runCurrent()
        assertEquals(later, r.sent.last())
        // A re-connect refetches the library: events sent meanwhile were never heard.
        assertEquals(1L, r.receiver.libraryVersion.value)
    }

    @Test
    fun libraryEventsBumpTheVersion() = runTest {
        val r = Rig(this)
        runCurrent()
        r.events.emit(RealtimeEvent.Favorites("add", "1"))
        r.events.emit(RealtimeEvent.Progress("update", false))
        r.events.emit(RealtimeEvent.Unknown("receivers"))
        runCurrent()
        assertEquals(2L, r.receiver.libraryVersion.value)
    }

    @Test
    fun transportGoesToTheRegisteredPlayerOnly() = runTest {
        val r = Rig(this)
        runCurrent()
        val got = ArrayList<CastCommand>()
        r.events.emit(RealtimeEvent.Command("s", "Pixel 7", CastCommand.Pause))
        runCurrent()
        assertTrue(got.isEmpty()) // nobody listening: dropped
        val id = r.receiver.setCommandHandler { got += it }
        r.events.emit(RealtimeEvent.Command("s", "Pixel 7", CastCommand.Seek(1_000)))
        runCurrent()
        assertEquals(listOf<CastCommand>(CastCommand.Seek(1_000)), got)
        // A replaced player cannot clear its successor's handler.
        val id2 = r.receiver.setCommandHandler { got += it }
        r.receiver.clearCommandHandler(id)
        r.events.emit(RealtimeEvent.Command("s", null, CastCommand.Resume))
        runCurrent()
        assertEquals(CastCommand.Resume, got.last())
        r.receiver.clearCommandHandler(id2)
        r.events.emit(RealtimeEvent.Command("s", null, CastCommand.Stop))
        runCurrent()
        assertEquals(CastCommand.Resume, got.last())
    }

    @Test
    fun theDriverIsFreshFor12s() = runTest {
        val r = Rig(this)
        runCurrent()
        r.events.emit(RealtimeEvent.Command("s", "StreamHub (android)", CastCommand.Pause))
        runCurrent()
        assertEquals("StreamHub (android)", r.receiver.controlledBy())
        advanceTimeBy(11_999)
        assertEquals("StreamHub (android)", r.receiver.controlledBy())
        advanceTimeBy(1)
        assertNull(r.receiver.controlledBy())
        // Fullscreen is ignored entirely — not even a driver.
        r.events.emit(RealtimeEvent.Command("s", "Other", CastCommand.Fullscreen))
        runCurrent()
        assertNull(r.receiver.controlledBy())
    }

    @Test
    fun playBecomesAPendingTarget() = runTest {
        val r = Rig(this)
        runCurrent()
        val req = CastPlayRequest(
            streamUrl = "https://cdn/x.m3u8", provider = "dramasq", itemUrl = "https://d/1", title = "T",
            episodeLabel = "02", prevEpisodeLabel = "01", nextEpisodeLabel = "03",
            episodeUrl = "https://d/1", subtitle = "線路1", positionMs = 61_500,
        )
        r.events.emit(RealtimeEvent.Command("s", "Pixel", CastCommand.Play(req)))
        runCurrent()
        val t = r.receiver.pendingPlay.value!!
        assertEquals(listOf("01", "02", "03"), t.episodes)
        assertEquals(61.0, t.startSeconds!!, 0.0)
        assertTrue(t.fromCast)
        assertEquals("https://cdn/x.m3u8", t.source!!.directUrl)
        assertEquals("線路1", t.source!!.sourceLabel)
        assertEquals("https://d/1", t.seasonUrl)
        assertEquals("", t.mediaType)
        r.receiver.consumePendingPlay()
        assertNull(r.receiver.pendingPlay.value)
    }

    @Test
    fun castTargetsNeedAStreamUrl() {
        assertNull(castPlayTarget(CastPlayRequest(streamUrl = "", title = "no url")))
        val movie = castPlayTarget(CastPlayRequest(streamUrl = "u", title = "M"))!!
        assertTrue(movie.episodes.isEmpty())
        assertEquals("", movie.episodeLabel)
    }

    @Test
    fun heartbeatWhenASessionAppearsThenEvery45s() = runTest {
        val r = Rig(this)
        runCurrent()
        advanceTimeBy(5_000)
        assertEquals(0, r.beats)
        r.token = "tok"
        advanceTimeBy(1_001)
        assertEquals(1, r.beats)
        // The first beat was at t=5 s; the next is due at t=50 s.
        advanceTimeBy(43_000)
        assertEquals(1, r.beats)
        advanceTimeBy(1_000)
        assertEquals(2, r.beats)
        r.token = null
        advanceTimeBy(100_000)
        assertEquals(2, r.beats)
    }

    @Test
    fun signingOutForgetsTheState() = runTest {
        val r = Rig(this)
        runCurrent()
        r.connected.value = true
        runCurrent()
        r.receiver.publish(playing)
        r.receiver.onSignedOut()
        r.connected.value = false
        runCurrent()
        r.connected.value = true
        runCurrent()
        assertNull(r.sent.last())
        assertEquals(0L, r.receiver.libraryVersion.value)
    }
}
