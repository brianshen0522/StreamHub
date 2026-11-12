package com.streamhub.tv.sfx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil

/**
 * The decisions of sfx.js `play(name, repeat)`, mirroring the checks the
 * Tizen QA ran on Chromium 85: no overlap, rapid taps spaced, a held key, one
 * edge per hold, off means silent, player up means silent.
 */
class SfxRulesTest {

    private var now = 100_000L
    private val rate = 48_000
    private fun rules() = SfxRules({ now }) { SfxSynth.durationMs(rate, it.synth) }
    private val run = SfxOutput.Running

    private fun SfxRules.accepts(s: Sound, repeat: Boolean = false) = offer(s, repeat, run) is SfxDecision.Play

    @Test
    fun aSoundPlaysAtMasterTimesItsGain() {
        val d = rules().offer(Sound.Select, false, run) as SfxDecision.Play
        assertEquals(0.5f * 0.17f, d.gain, 1e-6f)
        assertFalse(d.cut)
    }

    @Test
    fun noOverlapANewSoundCutsTheSoundingOne() {
        val r = rules()
        assertTrue(r.accepts(Sound.Move))
        now += 20 // the 45 ms tick is still sounding
        val d = r.offer(Sound.Select, false, run) as SfxDecision.Play
        assertTrue("one voice: the tick is faded out, not layered", d.cut)
        assertEquals(Sound.Select, r.sounding())
        assertEquals(1, r.cut)
    }

    @Test
    fun aSoundAfterTheVoiceEndedDoesNotCut() {
        val r = rules()
        r.accepts(Sound.Move)
        now += 46 // 45 ms buffer → ended
        val d = r.offer(Sound.Edge, false, run) as SfxDecision.Play
        assertFalse(d.cut)
    }

    @Test
    fun aLowerPriorityIsDroppedWhileAHigherOneSounds() {
        val r = rules()
        assertTrue(r.accepts(Sound.Select))
        now += 80 // select is 110 ms long
        val d = r.offer(Sound.Move, false, run)
        assertEquals(SfxDecision.Drop(SfxDecision.Reason.Priority, true), d)
        // Equal priority cuts (Back over Select).
        assertTrue(r.accepts(Sound.Back))
        // Edge (2) under Back (3): dropped.
        now += 10
        assertFalse(r.accepts(Sound.Edge))
        // Once Back has ended, a move plays again.
        now += 111
        assertTrue(r.accepts(Sound.Move))
    }

    @Test
    fun rapidTapsAreSpacedByTheGap() {
        val r = rules()
        assertTrue(r.accepts(Sound.Move))
        now += 50
        assertEquals(SfxDecision.Drop(SfxDecision.Reason.Gap, true), r.offer(Sound.Move, false, run))
        now += 20 // 70 ms after the accepted one
        assertTrue(r.accepts(Sound.Move))
        // Select: 140 ms.
        assertTrue(r.accepts(Sound.Select))
        now += 139
        assertFalse(r.accepts(Sound.Select))
        now += 1
        assertTrue(r.accepts(Sound.Select))
    }

    @Test
    fun aDroppedSoundDoesNotRestartItsGap() {
        val r = rules()
        r.accepts(Sound.Move)
        now += 60
        assertFalse(r.accepts(Sound.Move))
        now += 10
        assertTrue("70 ms after the accepted one, not the dropped one", r.accepts(Sound.Move))
    }

    @Test
    fun aHeldKeyIsSpaced120msAndPlaysAt60Percent() {
        val r = rules()
        val first = r.offer(Sound.Move, true, run) as SfxDecision.Play
        assertEquals(0.5f * 0.11f * 0.6f, first.gain, 1e-6f)
        // 80 ms repeats (the fastest rail pacing): only every 120 ms sounds.
        var accepted = 1
        repeat(10) {
            now += 80
            if (r.accepts(Sound.Move, repeat = true)) accepted++
        }
        // 800 ms of holding: sounds at 0, 160, 320, 480, 640, 800 → 6.
        assertEquals(6, accepted)
        // A non-repeat move uses the normal 70 ms gap and full gain.
        now += 70
        val tap = r.offer(Sound.Move, false, run) as SfxDecision.Play
        assertEquals(0.5f * 0.11f, tap.gain, 1e-6f)
    }

    @Test
    fun anEdgeIsSpaced260ms() {
        val r = rules()
        assertTrue(r.accepts(Sound.Edge))
        now += 259
        assertFalse(r.accepts(Sound.Edge))
        now += 1
        assertTrue(r.accepts(Sound.Edge))
        // Edge has no hold gap: a repeat uses 260 too.
        now += 200
        assertFalse(r.accepts(Sound.Edge, repeat = true))
    }

    @Test
    fun offMeansSilent() {
        val r = rules()
        r.enabled = false
        for (s in Sound.entries) assertEquals(SfxDecision.Drop(SfxDecision.Reason.Disabled, false), r.offer(s, false, run))
        assertEquals(0, r.dropped)
        r.enabled = true
        assertTrue(r.accepts(Sound.Move))
    }

    @Test
    fun playerUpMeansSilent() {
        val r = rules()
        r.playerUp = true
        for (s in Sound.entries) assertEquals(SfxDecision.Drop(SfxDecision.Reason.PlayerUp, false), r.offer(s, false, run))
        r.playerUp = false
        assertTrue(r.accepts(Sound.Back))
    }

    @Test
    fun hiddenMeansSilent() {
        val r = rules()
        r.hidden = true
        assertEquals(SfxDecision.Drop(SfxDecision.Reason.Hidden, false), r.offer(Sound.Select, false, run))
    }

    @Test
    fun aSuspendedOutputDropsTheSoundRatherThanPlayItLate() {
        val r = rules()
        assertEquals(SfxDecision.Drop(SfxDecision.Reason.Suspended, true), r.offer(Sound.Select, false, SfxOutput.Suspended))
        // Not accepted, so not recorded: the next one is not gapped.
        assertTrue(r.accepts(Sound.Select))
        assertEquals(SfxDecision.Drop(SfxDecision.Reason.NoOutput, false), rules().offer(Sound.Move, false, SfxOutput.None))
    }

    @Test
    fun silenceForgetsTheVoice() {
        val r = rules()
        r.accepts(Sound.Select)
        r.silence()
        now += 10
        // No higher-priority voice any more: a move plays, and does not cut.
        val d = r.offer(Sound.Move, false, run) as SfxDecision.Play
        assertFalse(d.cut)
    }

    @Test
    fun theOkThatOpensThePlayerFinishesItsPop() {
        val r = rules()
        r.accepts(Sound.Select)
        // setPlayer(true) waits for the voice to end (+30 ms) before suspending.
        assertEquals(ceil(rate * 0.11) / rate * 1000.0, r.remainingMs().toDouble(), 1.0)
        now += 200
        assertEquals(0L, r.remainingMs())
    }

    // ── synthesis ──

    @Test
    fun buffersAreCeilRateTimesDuration() {
        assertEquals(ceil(48_000 * 0.045).toInt(), SfxSynth.length(48_000, Sound.Move.synth))
        assertEquals(ceil(44_100 * 0.11).toInt(), SfxSynth.length(44_100, Sound.Select.synth))
        assertEquals(110.0, SfxSynth.durationMs(48_000, Sound.Back.synth), 0.05)
    }

    @Test
    fun buffersAreNormalisedStartSilentAndEndSilent() {
        for (s in Sound.entries) {
            val d = SfxSynth.render(rate, s.synth)
            assertEquals(1f, d.maxOf { abs(it) }, 1e-6f)
            assertEquals("${s.key} starts at 0 (attack from i = 0)", 0f, d[0], 1e-6f)
            assertTrue("${s.key} ends on a ramp to 0", abs(d.last()) < 0.01f)
        }
    }

    @Test
    fun theTickIsHighAndTheThudIsLow() {
        // Zero crossings over the buffer ~ 2 · f · dur.
        fun crossings(d: FloatArray) = (1 until d.size).count { (d[it - 1] < 0f) != (d[it] < 0f) }
        val move = crossings(SfxSynth.render(rate, Sound.Move.synth)) / 2.0 / 0.045
        val edge = crossings(SfxSynth.render(rate, Sound.Edge.synth)) / 2.0 / 0.09
        assertTrue("move ≈ 1330 Hz, was $move", move in 1200.0..1500.0)
        assertTrue("edge ≈ 150 Hz, was $edge", edge in 120.0..200.0)
    }
}
