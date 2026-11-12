package com.streamhub.mobile.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteTimelineTest {

    private val hour = 3_600_000L

    private fun playingAt(positionMs: Long, now: Long) =
        RemoteTimeline().report(positionMs, hour, playing = true, now = now)

    @Test
    fun `advances between reports only while playing`() {
        val playing = playingAt(60_000, now = 1_000)
        assertEquals(60_700, playing.positionAt(1_700))

        val paused = RemoteTimeline().report(60_000, hour, playing = false, now = 1_000)
        assertEquals("a paused position that crept forward would be a lie", 60_000, paused.positionAt(9_000))
    }

    @Test
    fun `never runs past the end`() {
        val t = RemoteTimeline().report(hour - 500, hour, playing = true, now = 0)
        assertEquals(hour, t.positionAt(10_000))
    }

    @Test
    fun `the same report re-sent does not drag the bar back`() {
        // The receiver list is re-sent whenever any device on the account
        // changes; the television's position in it is as old as it was.
        val t = playingAt(60_000, now = 0).report(60_000, hour, playing = true, now = 800)
        assertEquals(61_000, t.positionAt(1_000))
    }

    @Test
    fun `a report slightly behind is latency, not a rewind`() {
        val t = playingAt(60_000, now = 0).report(60_700, hour, playing = true, now = 1_000)
        assertEquals("kept at the local 61 s rather than stepping back to 60.7", 61_000, t.positionAt(1_000))

        val far = playingAt(60_000, now = 0).report(30_000, hour, playing = true, now = 1_000)
        assertEquals("a real jump is followed", 30_000, far.positionAt(1_000))
    }

    @Test
    fun `an optimistic pause freezes the bar where it is`() {
        val t = playingAt(60_000, now = 0).withPlaying(false, now = 1_500)
        assertEquals(61_500, t.positionAt(5_000))
    }

    @Test
    fun `a seek is held until a report lands near it`() {
        var t = playingAt(60_000, now = 0).copy(playing = false)
        t = t.seekTo(600_000, now = 1_000)
        // A stale report from before the seek arrives: still the target.
        t = t.report(60_000, hour, playing = false, now = 1_200)
        assertEquals(600_000, t.shownAt(1_300))

        t = t.report(600_000, hour, playing = false, now = 1_900)
        assertNull("confirmed", t.pendingSeekMs)
        assertEquals(600_000, t.shownAt(2_000))
    }

    @Test
    fun `a seek the television ignored is let go after four seconds`() {
        // Reports keep arriving every second without reaching the target.
        // Each used to restart the timer, so the hold never ended.
        var t = RemoteTimeline().report(60_000, hour, playing = false, now = 0).seekTo(600_000, now = 0)
        for (now in 1_000L..5_000L step 1_000) t = t.report(60_000, hour, playing = false, now = now)
        assertNull(t.pendingSeekMs)
        assertEquals(60_000, t.shownAt(5_000))
    }

    @Test
    fun `repeated skips build on the held target`() {
        var t = RemoteTimeline().report(60_000, hour, playing = false, now = 0)
        val asked = mutableListOf<Long>()
        repeat(3) {
            t = t.seekBy(10_000, now = 100L * it)
            asked += t.pendingSeekMs!!
        }
        assertEquals("three taps are thirty seconds, not ten three times", listOf(70_000L, 80_000L, 90_000L), asked)

        t = t.seekBy(-200_000, now = 400)
        assertEquals("and never before the start", 0L, t.pendingSeekMs)
    }

    @Test
    fun `a held target advances while the television plays`() {
        val t = playingAt(60_000, now = 0).seekTo(300_000, now = 0)
        assertEquals(301_000, t.shownAt(1_000))
        // The television reports where it got to a second later: confirmed.
        assertNull(t.report(301_000, hour, playing = true, now = 1_000).pendingSeekMs)
    }

    @Test
    fun `seeks stop short of the end`() {
        val t = RemoteTimeline().report(0, 120_000, playing = false, now = 0)
        assertEquals(119_000L, t.seekBy(500_000, now = 0).pendingSeekMs)
    }
}
