package com.streamhub.tv.player

import com.streamhub.core.model.CastCommand
import com.streamhub.core.model.SeasonRef
import com.streamhub.core.model.WatchProgress
import com.streamhub.tv.focus.TvKey
import com.streamhub.tv.nav.PlayTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.io.IOException
import kotlin.math.min

/**
 * The controller against `player/mod.rs`: sources and preference, the ladder,
 * outages, seek and scrub pacing, OK, the strip, up-next, progress cadence
 * and body, and the cast state. Virtual time throughout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerControllerTest {

    private fun TestScope.step(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    // ── sources ──────────────────────────────────────────────────────────

    @Test
    fun theFirstSourceStartsAtOnceWithoutAPreference() = runTest {
        val h = Harness(this).mount()
        assertEquals("尋找片源中…", h.ui.notice)
        assertTrue(h.ui.osd)
        assertNotNull(h.cast.published.last())
        h.data.line("https://cdn/a.m3u8", "A")
        runCurrent()
        assertEquals(listOf("M:https://cdn/a.m3u8" to 60_000.0), h.engine.opened)
        assertEquals(0, h.ui.currentSource)
        assertEquals("A", h.ui.sourceLabel)
    }

    @Test
    fun theSavedSourceIsWaitedForUpTo2500ms() = runTest {
        val h = Harness(this)
        h.data.preferred = "B"
        h.mount()
        h.data.line("a", "A")
        step(2_000)
        assertTrue(h.engine.opened.isEmpty())
        h.data.line("b", "B")
        runCurrent()
        assertEquals("M:b", h.engine.opened.single().first)
        assertEquals(1, h.ui.currentSource)
    }

    @Test
    fun withoutTheSavedSourceTheFirstStartsAfter2500ms() = runTest {
        val h = Harness(this)
        h.data.preferred = "B"
        h.mount()
        h.data.line("a", "A")
        step(2_499)
        assertTrue(h.engine.opened.isEmpty())
        step(1)
        assertEquals("M:a", h.engine.opened.single().first)
    }

    @Test
    fun aFinishedStreamStartsThePreferredElseTheFirst() = runTest {
        val h = Harness(this)
        h.data.preferred = "Z"
        h.mount()
        h.data.line("a", "A")
        h.data.lines.close()
        runCurrent()
        assertEquals("M:a", h.engine.opened.single().first)
    }

    @Test
    fun noSourcesGivesUpAndFocusesRetry() = runTest {
        val h = Harness(this).mount()
        h.data.lines.close()
        runCurrent()
        assertEquals("找不到可播放的片源", h.ui.fatal)
        assertNull(h.ui.notice)
        assertFalse(h.ui.osd)
        assertTrue(h.focus.isEmpty())
        step(30)
        assertEquals(listOf(PlayerFocusTarget.Retry), h.focus)
        // Back on the panel leaves; other keys are the panel buttons'.
        assertFalse(h.key(TvKey.Left))
        assertTrue(h.key(TvKey.Back))
        assertEquals(1, h.nav.backs)
    }

    @Test
    fun anOfflineSourceListWaitsForTheNetwork() = runTest {
        val h = Harness(this).mount()
        h.data.reachable = false
        h.data.lines.close(IOException("down"))
        runCurrent()
        assertEquals("網路連線中斷，恢復後自動繼續…", h.ui.notice)
        assertNull(h.ui.fatal)
    }

    @Test
    fun aKnownSourceIsFilledInByItsProbeLine() = runTest {
        val h = Harness(this, Harness.TV.copy(source = SourceLine(directUrl = "a", sourceLabel = ""))).mount()
        assertEquals("M:a", h.engine.opened.single().first)
        assertEquals("片源 1", h.ui.sources.single().label)
        h.data.line("a", "線路1", ads = 31.6, dur = 2710.0)
        runCurrent()
        assertEquals(SourceRow("線路1", 32, "45:10"), h.ui.sources.single())
        assertEquals(1, h.engine.opened.size)
    }

    // ── the ladder ───────────────────────────────────────────────────────

    @Test
    fun faultsClimbRetryRelayRetryThenWaitThenTheNextSource() = runTest {
        val h = Harness(this).playing(posMs = 120_000.0)
        val l = h.engine.listener!!
        l.onError("E1")
        runCurrent()
        assertEquals("M:https://cdn/a.m3u8" to 120_000.0, h.engine.opened.last())
        l.onError("E2")
        runCurrent()
        assertEquals("改用伺服器轉送…", h.ui.notice)
        assertEquals("R:https://cdn/a.m3u8", h.engine.opened.last().first)
        l.onError("E3")
        runCurrent()
        assertEquals("R:https://cdn/a.m3u8", h.engine.opened.last().first)
        l.onError("E4")
        runCurrent()
        // Exhausted while sources are still streaming in: wait for one.
        assertEquals("尋找其他片源…", h.ui.notice)
        assertTrue(h.ctl.waitingMore)
        h.data.line("b", "B")
        runCurrent()
        assertEquals("改用其他片源…", h.ui.notice)
        assertEquals("M:b" to 120_000.0, h.engine.opened.last())
        assertEquals(5, h.engine.opened.size)
        assertEquals(4, h.data.health)
    }

    @Test
    fun exhaustedWithTheStreamFinishedGivesUp() = runTest {
        val h = Harness(this).playing()
        h.data.lines.close()
        runCurrent()
        repeat(4) {
            h.engine.listener!!.onError("E")
            runCurrent()
        }
        assertEquals("E", h.ui.fatal)
        assertFalse(h.ui.controls)
        // 重試: back to source 0, manifest, last good position.
        val opens = h.engine.opened.size
        h.ctl.retry()
        assertNull(h.ui.fatal)
        assertEquals("重新連線中…", h.ui.notice)
        assertEquals("M:https://cdn/a.m3u8" to 60_000.0, h.engine.opened.last())
        assertEquals(opens + 1, h.engine.opened.size)
    }

    @Test
    fun anOutageNeverClimbsAndResumesTheSameRung() = runTest {
        val h = Harness(this).playing(posMs = 300_000.0)
        h.data.reachable = false
        h.engine.listener!!.onError("NET")
        runCurrent()
        assertEquals("網路連線中斷，恢復後自動繼續…", h.ui.notice)
        assertTrue(h.ctl.offline)
        // Further faults while offline are ignored.
        h.engine.listener!!.onError("NET2")
        runCurrent()
        val opens = h.engine.opened.size
        step(2_000) // probe 1 after 2 s: still down
        step(4_000) // probe 2 after 4 more
        assertEquals(opens, h.engine.opened.size)
        h.data.reachable = true
        step(8_000) // probe 3 after 8 more: back
        assertFalse(h.ctl.offline)
        assertEquals("重新連線中…", h.ui.notice)
        assertEquals("M:https://cdn/a.m3u8" to 300_000.0, h.engine.opened.last())
        // The ladder did not move: the next real fault is a plain retry.
        assertEquals(Tier.Manifest, h.ctl.ladder!!.tier)
    }

    @Test
    fun aStallOf45sIsAFault() = runTest {
        val h = Harness(this).playing()
        h.engine.listener!!.onBuffering(true)
        val opens = h.engine.opened.size
        step(45_000)
        assertEquals(opens, h.engine.opened.size)
        step(1_000)
        assertEquals(opens + 1, h.engine.opened.size)
    }

    // ── seeking ──────────────────────────────────────────────────────────

    @Test
    fun tapsAccumulateIntoOneSeek400msAfterTheLast() = runTest {
        val h = Harness(this).playing(posMs = 60_000.0)
        h.key(TvKey.Right); h.up(TvKey.Right)
        step(300)
        h.key(TvKey.Right); h.up(TvKey.Right)
        step(300)
        h.key(TvKey.Right); h.up(TvKey.Right)
        assertEquals(90L, h.ui.seekPreview)
        assertTrue(h.ui.osd)
        step(399)
        assertTrue(h.engine.seeks.isEmpty())
        step(1)
        assertEquals(listOf(90_000L), h.engine.seeks)
        assertNull(h.ui.seekPreview)
        assertEquals(90L, h.ui.posS)
    }

    @Test
    fun tapsClampToOneSecondBeforeTheEndAndToZero() = runTest {
        val h = Harness(this).playing(posMs = 5_000.0, durMs = 600_000.0)
        h.key(TvKey.Left)
        assertEquals(0L, h.ui.seekPreview)
        step(400)
        h.key(TvKey.FastForward)
        repeat(30) { h.key(TvKey.FastForward) }
        step(400)
        assertEquals(listOf(0L, 599_000L), h.engine.seeks)
    }

    @Test
    fun holdingScrubsWithAccelerationAndReleaseSeeksOnce() = runTest {
        val h = Harness(this).playing(posMs = 60_000.0, durMs = 7_200_000.0)
        h.key(TvKey.Right) // t=0: a tap, +10 s
        var t = 100L
        step(100)
        h.key(TvKey.Right, repeat = true) // the first repeat enters scrub mode
        assertTrue(h.ui.scrub)
        val end = 3_600L
        while (t < end) {
            step(40)
            t += 40
            h.key(TvKey.Right, repeat = true)
        }
        // Ticks every 120 ms from the first repeat; steps grow with the hold since the key-down.
        var expected = 70_000L
        var tick = 100L + 120
        while (tick <= end) {
            expected += PlayerRules.scrubStepMs(tick)
            tick += 120
        }
        expected = min(expected, 7_199_000L)
        assertEquals(expected / 1000, h.ui.seekPreview)
        assertTrue(h.engine.seeks.isEmpty())
        h.up(TvKey.Right)
        assertEquals(listOf(expected), h.engine.seeks)
        assertFalse(h.ui.scrub)
        step(1_000)
        assertEquals(1, h.engine.seeks.size)
    }

    @Test
    fun aLostReleaseCommitsAfterTheGrace() = runTest {
        val h = Harness(this).playing(posMs = 60_000.0)
        h.key(TvKey.Left)
        step(100)
        h.key(TvKey.Left, repeat = true)
        // No more repeats and no keyup: ticks at 220 and 340 step, 460 is past 550? no — 550 is the grace end.
        step(1_000)
        assertFalse(h.ui.scrub)
        assertEquals(1, h.engine.seeks.size)
        // 60 − 10 (tap) − 10 − 10 − 10 (ticks at 220, 340, 460) = 20 s.
        assertEquals(20_000L, h.engine.seeks.single())
    }

    @Test
    fun backMidScrubCancelsWithoutSeeking() = runTest {
        val h = Harness(this).playing()
        h.key(TvKey.Right)
        step(100)
        h.key(TvKey.Right, repeat = true)
        step(200)
        assertTrue(h.key(TvKey.Back))
        assertNull(h.ui.seekPreview)
        assertFalse(h.ui.scrub)
        step(2_000)
        assertTrue(h.engine.seeks.isEmpty())
        assertEquals(0, h.nav.backs)
    }

    @Test
    fun aSeekWhileLoadingLandsOncePlaying() = runTest {
        val h = Harness(this).mount()
        h.data.line("a", "A")
        runCurrent()
        h.key(TvKey.Right)
        step(400)
        assertTrue(h.engine.seeks.isEmpty())
        assertEquals(70_000.0, h.ctl.deferredSeek!!, 0.0)
        h.engine.listener!!.onState(PlayerState.PLAYING)
        assertEquals(listOf(70_000L), h.engine.seeks)
    }

    @Test
    fun aPauseWhileLoadingIsAppliedOncePlaying() = runTest {
        val h = Harness(this).mount()
        h.data.line("a", "A")
        runCurrent()
        h.engine.listener!!.onState(PlayerState.LOADING)
        h.key(TvKey.PlayPause)
        assertFalse(h.ctl.intentPlay)
        assertEquals(Flash(false, 1), h.ui.flash)
        h.engine.calls.clear()
        h.engine.listener!!.onState(PlayerState.PLAYING)
        assertEquals(listOf("pause"), h.engine.calls)
    }

    // ── OK ───────────────────────────────────────────────────────────────

    @Test
    fun okTapTogglesWithTheGlyph() = runTest {
        val h = Harness(this).playing()
        assertTrue(h.key(TvKey.Enter))
        assertNull(h.ui.flash)
        h.up(TvKey.Enter)
        assertEquals("pause", h.engine.calls.last())
        assertEquals(Flash(false, 1), h.ui.flash)
        step(649)
        assertNotNull(h.ui.flash)
        step(1)
        assertNull(h.ui.flash)
    }

    @Test
    fun okHeldOpensThePickerAndTheReleaseDoesNothing() = runTest {
        val h = Harness(this).playing()
        h.key(TvKey.Enter)
        step(50)
        h.key(TvKey.Enter, repeat = true)
        step(650)
        assertTrue(h.ui.picker)
        assertFalse(h.ui.controls)
        assertEquals(listOf(PlayerFocusTarget.PickerCurrent), h.focus)
        val calls = h.engine.calls.size
        h.up(TvKey.Enter)
        assertEquals(calls, h.engine.calls.size)
        // Back closes it into the controls, focused on the toggle.
        assertTrue(h.key(TvKey.Back))
        assertFalse(h.ui.picker)
        assertTrue(h.ui.controls)
        assertEquals(PlayerFocusTarget.Toggle, h.focus.last())
    }

    @Test
    fun okWithoutRepeatsOrReleaseIsATap() = runTest {
        val h = Harness(this).playing()
        h.key(TvKey.Enter)
        step(700)
        assertFalse(h.ui.picker)
        assertEquals("pause", h.engine.calls.last())
        h.up(TvKey.Enter)
        assertEquals("pause", h.engine.calls.last())
    }

    @Test
    fun choosingAnotherSourceSwitchesAtThePositionAndSavesIt() = runTest {
        val h = Harness(this).playing(posMs = 200_000.0)
        h.data.line("b", "B")
        runCurrent()
        h.ctl.openPicker()
        h.ctl.selectSource(1)
        runCurrent()
        assertFalse(h.ui.picker)
        assertEquals("切換片源…", h.ui.notice)
        assertEquals("M:b" to 200_000.0, h.engine.opened.last())
        assertEquals("switch", h.data.progress.last().event)
        assertEquals(listOf("B"), h.data.preferenceSaves)
        // The current one only closes it.
        h.ctl.openPicker()
        h.ctl.selectSource(1)
        assertTrue(h.ui.controls)
    }

    // ── chrome and Back ──────────────────────────────────────────────────

    @Test
    fun theBarHidesAfter4sButNeverWhilePaused() = runTest {
        val h = Harness(this).playing()
        h.key(TvKey.Up)
        assertTrue(h.ui.controls)
        step(0)
        assertEquals(listOf(PlayerFocusTarget.Toggle), h.focus)
        step(3_999)
        assertTrue(h.ui.controls)
        step(1)
        assertFalse(h.ui.controls)
        assertFalse(h.ui.osd)
        h.engine.listener!!.onState(PlayerState.PAUSED)
        step(10_000)
        assertTrue(h.ui.osd)
        assertTrue(h.ui.shown)
    }

    @Test
    fun backPeelsOneLayerAtATime() = runTest {
        val h = Harness(this).playing()
        h.key(TvKey.Up)
        // Arrows and OK with the pills up are the focus engine's.
        assertFalse(h.key(TvKey.Right))
        assertTrue(h.key(TvKey.Back))
        assertFalse(h.ui.controls)
        h.key(TvKey.Right)
        assertTrue(h.key(TvKey.Back))
        assertNull(h.ui.seekPreview)
        assertEquals(0, h.nav.backs)
        assertTrue(h.key(TvKey.Back))
        assertEquals(1, h.nav.backs)
    }

    @Test
    fun mediaKeys() = runTest {
        val h = Harness(this).playing(posMs = 100_000.0)
        h.key(TvKey.Rewind)
        step(400)
        assertEquals(listOf(70_000L), h.engine.seeks)
        h.key(TvKey.Next)
        assertEquals("03", h.nav.replaced.last().episodeLabel)
        assertEquals(0.0, h.nav.replaced.last().startSeconds!!, 0.0)
        h.key(TvKey.Previous)
        assertEquals("01", h.nav.replaced.last().episodeLabel)
        h.key(TvKey.Pause)
        assertEquals("pause", h.engine.calls.last())
        assertEquals(Flash(false, 1), h.ui.flash)
        // Repeats of the play keys are ignored.
        h.key(TvKey.PlayPause, repeat = true)
        assertEquals("pause", h.engine.calls.last())
        h.key(TvKey.Stop)
        assertEquals(1, h.nav.backs)
    }

    // ── the strip ────────────────────────────────────────────────────────

    @Test
    fun theStripPlaysAnotherEpisodeFromItsResumePoint() = runTest {
        val h = Harness(this)
        h.data.rows = listOf(
            WatchProgress(providerKey = "dramasq", title = "T", itemUrl = "https://d/1", episodeLabel = "01", isCompleted = true, progressPercent = 98.0),
            WatchProgress(providerKey = "dramasq", title = "T", itemUrl = "https://d/1", episodeLabel = "03", positionSeconds = 300, progressPercent = 20.0),
        )
        h.playing()
        h.key(TvKey.Down)
        assertEquals(1, h.ui.strip)
        assertFalse(h.ui.osd)
        assertEquals(listOf(StripInfo(0.98, true), StripInfo(0.0, false), StripInfo(0.2, false)), h.ui.stripInfo)
        h.key(TvKey.Left)
        h.key(TvKey.Left)
        assertEquals(0, h.ui.strip)
        h.key(TvKey.Right)
        h.key(TvKey.Right)
        h.key(TvKey.Right)
        assertEquals(2, h.ui.strip)
        assertTrue(h.key(TvKey.Down))
        h.key(TvKey.Enter)
        assertEquals("03", h.nav.replaced.single().episodeLabel)
        assertEquals(270.0, h.nav.replaced.single().startSeconds!!, 0.0)
    }

    @Test
    fun theStripOnTheCurrentEpisodeJustCloses() = runTest {
        val h = Harness(this).playing()
        h.key(TvKey.Down)
        h.key(TvKey.Enter)
        assertNull(h.ui.strip)
        assertTrue(h.ui.osd)
        assertTrue(h.nav.replaced.isEmpty())
    }

    // ── up next and the end ──────────────────────────────────────────────

    @Test
    fun upNextInTheLastTwoMinutesAndDismissal() = runTest {
        val h = Harness(this).playing(posMs = 1_400_000.0, durMs = 1_500_000.0)
        assertEquals(UpNextCard("下一集 第03集", 100), h.ui.upNext)
        // Left/Right do nothing while the card is up.
        assertTrue(h.key(TvKey.Right))
        assertNull(h.ui.seekPreview)
        h.key(TvKey.Down)
        assertNull(h.ui.upNext)
        h.engine.listener!!.onTime(1_410_000.0)
        assertNull(h.ui.upNext)
        // Dismissed: the end leaves instead of advancing.
        h.engine.listener!!.onEnded()
        runCurrent()
        assertEquals(1, h.nav.backs)
        assertTrue(h.nav.replaced.isEmpty())
        assertEquals("ended", h.data.progress.last().event)
        assertEquals(1500, h.data.progress.last().positionSeconds)
    }

    @Test
    fun theEndAdvancesToTheNextEpisode() = runTest {
        val h = Harness(this).playing(posMs = 1_450_000.0, durMs = 1_500_000.0)
        h.engine.listener!!.onEnded()
        assertEquals("03", h.nav.replaced.single().episodeLabel)
        assertEquals(0.0, h.nav.replaced.single().startSeconds!!, 0.0)
    }

    @Test
    fun okOnUpNextPlaysNow() = runTest {
        val h = Harness(this).playing(posMs = 1_450_000.0, durMs = 1_500_000.0)
        h.key(TvKey.Enter)
        assertEquals("03", h.nav.replaced.single().episodeLabel)
    }

    @Test
    fun theLastEpisodeRollsOverToTheNextSeason() = runTest {
        val t = Harness.TV.copy(seasonUrl = "https://m/s1", seasonLabel = "S1", episodeLabel = "02", episodes = listOf("01", "02"))
        val h = Harness(this, t)
        h.data.seasonList = listOf(SeasonRef("S1", "https://m/s1"), SeasonRef("S2", "https://m/s2"))
        h.data.episodeLists = mapOf("https://m/s2" to listOf("e1", "e2"))
        h.mount()
        assertTrue(h.ui.hasNext)
        val n = h.ctl.next!!
        assertEquals("e1", n.episodeLabel)
        assertEquals("https://m/s2", n.seasonUrl)
        assertEquals("S2", n.seasonLabel)
        assertEquals(listOf("e1", "e2"), n.episodes)
    }

    // ── progress ─────────────────────────────────────────────────────────

    @Test
    fun progressEvery15sWhilePlayingAndOnPause() = runTest {
        val h = Harness(this).playing(posMs = 60_000.0, durMs = 1_500_000.0)
        h.engine.position = 61_000.0
        step(14_000)
        assertTrue(h.data.progress.isEmpty())
        step(1_000)
        val p = h.data.progress.single()
        assertEquals("progress", p.event)
        assertEquals(61, p.positionSeconds)
        assertEquals(1500, p.durationSeconds)
        assertEquals("A", p.sourceLabel)
        assertEquals("tv", p.mediaType)
        assertEquals("https://img/p.jpg", p.posterUrl)
        h.engine.listener!!.onState(PlayerState.PAUSED)
        runCurrent()
        assertEquals("pause", h.data.progress.last().event)
        // A report of any kind resets the clock; paused sends no "progress".
        step(30_000)
        assertEquals(2, h.data.progress.size)
    }

    @Test
    fun nothingIsReportedBeforeADuration() = runTest {
        val h = Harness(this).mount()
        h.data.line("a", "A")
        runCurrent()
        h.engine.listener!!.onState(PlayerState.PLAYING)
        step(16_000)
        h.ctl.teardown()
        runCurrent()
        assertTrue(h.data.progress.isEmpty())
    }

    @Test
    fun teardownReportsAPauseAndGoesIdle() = runTest {
        val h = Harness(this).playing()
        h.ctl.teardown()
        runCurrent()
        assertEquals("pause", h.data.progress.single().event)
        assertNull(h.cast.published.last())
        assertNull(h.cast.handler)
        assertEquals("close", h.engine.calls.last())
    }

    @Test
    fun backgroundingReportsAndSuspends() = runTest {
        val h = Harness(this).playing()
        h.ctl.onHidden()
        runCurrent()
        assertEquals("pause", h.data.progress.single().event)
        assertEquals("suspend", h.engine.calls.last())
        h.ctl.onShown()
        assertEquals("restore", h.engine.calls.last())
    }

    // ── cast ─────────────────────────────────────────────────────────────

    @Test
    fun publishedStateIsIntentAndTarget() = runTest {
        val h = Harness(this).playing(posMs = 61_500.0, durMs = 1_500_000.0)
        // Time events do not publish; the 1 s tick does.
        step(1_000)
        val s = h.cast.published.last()!!
        assertEquals("dramasq", s.provider)
        assertEquals("https://d/1", s.itemUrl)
        assertEquals("黑暗榮耀", s.title)
        assertEquals("A", s.subtitle)
        assertEquals("02", s.episodeLabel)
        assertEquals(61_500L, s.positionMs)
        assertEquals(1_500_000L, s.durationMs)
        assertFalse(s.paused)
        assertTrue(s.hasNext)
        assertTrue(s.hasPrevious)
        h.key(TvKey.Right)
        assertEquals(71_500L, h.ctl.state().positionMs)
        h.key(TvKey.Pause)
        assertTrue(h.ctl.state().paused)
    }

    @Test
    fun commandsAreObeyedEchoedAndShowTheDriver() = runTest {
        val h = Harness(this).playing(posMs = 60_000.0, durMs = 600_000.0)
        h.cast.driver = "StreamHub (android)" to testScheduler.currentTime
        val before = h.cast.published.size
        h.cast.handler!!(CastCommand.Seek(9_999_999))
        assertEquals(listOf(599_000L), h.engine.seeks)
        assertEquals("StreamHub (android)", h.ui.driver)
        val afterSeek = h.cast.published.size
        assertTrue(afterSeek > before)
        step(60)
        assertEquals(afterSeek + 1, h.cast.published.size)
        h.cast.handler!!(CastCommand.Pause)
        assertEquals("pause", h.engine.calls.last())
        // The badge goes 12 s after the last command.
        step(12_000)
        assertNull(h.ui.driver)
        h.cast.handler!!(CastCommand.Stop)
        assertEquals(1, h.nav.backs)
    }

    @Test
    fun forgivenessAfterAMinuteOfHealthyPlayback() = runTest {
        val h = Harness(this).playing()
        h.engine.listener!!.onError("E")
        runCurrent()
        h.engine.listener!!.onError("E")
        runCurrent()
        assertEquals(Tier.Relay, h.ctl.ladder!!.tier)
        h.engine.playing()
        step(61_000)
        assertEquals(Tier.Manifest, h.ctl.ladder!!.tier)
    }

    @Test
    fun aMovieHasNoNeighboursAndNoStrip() = runTest {
        val h = Harness(this, PlayTarget("p", "https://m/1", title = "M", mediaType = "movie")).playing()
        assertFalse(h.ui.hasNext)
        h.key(TvKey.Down)
        assertNull(h.ui.strip)
        assertTrue(h.ui.controls)
    }
}
