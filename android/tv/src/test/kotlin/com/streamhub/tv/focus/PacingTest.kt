package com.streamhub.tv.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hold-to-scroll pacing, the hold edge flag, the bump gate, the key buffer, route memory, reveal math. */
class PacingTest {

    private var now = 10_000L
    private val clock = { now }

    // ── hold_steps (focus.rs:822-851) ──

    @Test
    fun aFirstPressIsOneStep() {
        val p = HoldPacer(clock)
        assertEquals(1, p.steps(TvKey.Right, repeat = false))
        assertEquals(TvKey.Right, p.holdKey)
    }

    @Test
    fun horizontalRepeatsAre160msThen80msThenThreeCardsAfter1500ms() {
        val p = HoldPacer(clock)
        assertEquals(1, p.steps(TvKey.Right, false))
        // Repeats every 50 ms (a typical key repeat) for 2 s.
        val steps = ArrayList<Pair<Long, Int>>()
        for (t in 50L..2000L step 50L) {
            now = 10_000L + t
            val s = p.steps(TvKey.Right, true)
            if (s > 0) steps.add(t to s)
        }
        // < 400 ms held: every 160 ms → 200 (≥160 after 0), 400 is the next band.
        assertEquals(200L to 1, steps[0])
        // 400–1500 ms: every 80 ms → 400, 500, 600 … (50 ms grid → every 100 ms)
        assertEquals(400L to 1, steps[1])
        assertEquals(500L to 1, steps[2])
        // ≥ 1500 ms: three per step.
        val late = steps.filter { it.first >= 1500L }
        assertTrue(late.isNotEmpty())
        assertTrue(late.all { it.second == 3 })
        val mid = steps.filter { it.first in 400L until 1500L }
        assertTrue(mid.all { it.second == 1 })
    }

    @Test
    fun aRepeatInsideTheIntervalIsDroppedNotQueued() {
        val p = HoldPacer(clock)
        p.steps(TvKey.Left, false)
        now += 159
        assertEquals(0, p.steps(TvKey.Left, true))
        now += 1
        assertEquals(1, p.steps(TvKey.Left, true))
        // The dropped one did not move last_move.
        now += 159
        assertEquals(0, p.steps(TvKey.Left, true))
    }

    @Test
    fun verticalRepeatsAre220msWhateverTheHold() {
        val p = HoldPacer(clock)
        p.steps(TvKey.Down, false)
        now += 219
        assertEquals(0, p.steps(TvKey.Down, true))
        now += 1
        assertEquals(1, p.steps(TvKey.Down, true))
        now += 3000
        assertEquals(1, p.steps(TvKey.Down, true))
    }

    @Test
    fun aDifferentKeyRestartsTheHold() {
        val p = HoldPacer(clock)
        p.steps(TvKey.Right, false)
        now += 2000
        p.steps(TvKey.Right, true)
        // A repeat flagged for another key counts as a fresh press.
        assertEquals(1, p.steps(TvKey.Left, true))
        now += 100
        assertEquals(0, p.steps(TvKey.Left, true))
    }

    @Test
    fun keyUpReleasesTheHold() {
        val p = HoldPacer(clock)
        p.steps(TvKey.Right, false)
        now += 2000
        p.release()
        assertNull(p.holdKey)
        // The next repeat (a stale one after keyup) starts a new hold: 1 step.
        assertEquals(1, p.steps(TvKey.Right, true))
    }

    @Test
    fun aHeldKeyThudsOncePerWall() {
        val p = HoldPacer(clock)
        p.steps(TvKey.Left, false)
        assertTrue("first press at a wall sounds", p.edged(repeat = false))
        assertFalse("held against it: silent", p.edged(repeat = true))
        assertFalse(p.edged(repeat = true))
        // Moving clears it: the next wall sounds again.
        p.moved()
        assertTrue(p.edged(repeat = true))
        // A new press always sounds.
        p.steps(TvKey.Left, false)
        assertTrue(p.edged(repeat = false))
    }

    @Test
    fun aNewPressClearsTheEdgedFlag() {
        val p = HoldPacer(clock)
        p.steps(TvKey.Left, false)
        p.edged(repeat = false)
        assertTrue(p.holdEdged)
        p.steps(TvKey.Left, false)
        assertFalse(p.holdEdged)
    }

    // ── bump (focus.rs:739-773) ──

    @Test
    fun bumpsAreDeDupedFor300msGlobally() {
        val b = BumpGate(clock)
        assertTrue(b.tryBump())
        now += 299
        assertFalse(b.tryBump())
        now += 1
        assertTrue(b.tryBump())
    }

    @Test
    fun aSuppressedBumpDoesNotExtendTheWindow() {
        val b = BumpGate(clock)
        assertTrue(b.tryBump())
        now += 200
        assertFalse(b.tryBump())
        now += 100
        assertTrue("300 ms after the shown one, not the suppressed one", b.tryBump())
    }

    // ── key buffer (focus.rs:729-737, 473-501) ──

    @Test
    fun theBufferHoldsThreeKeys() {
        val k = KeyBuffer(clock)
        k.push(Dir.Down); k.push(Dir.Right); k.push(Dir.Right); k.push(Dir.Up)
        assertEquals(listOf(Dir.Down, Dir.Right, Dir.Right), k.drain())
        assertTrue(k.isEmpty())
    }

    @Test
    fun bufferedKeysOlderThan2sAreDropped() {
        val k = KeyBuffer(clock)
        k.push(Dir.Down)
        now += 1500
        k.push(Dir.Right)
        now += 501
        assertEquals(listOf(Dir.Right), k.drain())
        k.push(Dir.Left)
        now += 2000
        assertEquals("exactly 2 s is still fresh", listOf(Dir.Left), k.drain())
    }

    // ── route memory ──

    @Test
    fun routeMemoryPrunesDeeperEntries() {
        val m = RouteMemory()
        m.record(1, "home", "card:a")
        m.record(2, "detail:x:u", "play")
        m.record(3, "player:u", "pill")
        assertEquals("play", m.restore(2, "detail:x:u"))
        // Restoring at depth 2 pruned depth 3.
        assertNull(m.snapshot()["3|player:u"])
        // Back at Home: the detail entry goes, Home's stays.
        assertEquals("card:a", m.restore(1, "home"))
        assertEquals(setOf("1|home"), m.snapshot().keys)
    }

    @Test
    fun aDifferentEntryAtTheSameDepthHasItsOwnMemory() {
        val m = RouteMemory()
        m.record(2, "detail:a:1", "ep:3")
        assertNull(m.restore(2, "detail:b:2"))
        assertEquals("ep:3", m.restore(2, "detail:a:1"))
    }

    // ── reveal (focus.rs:311-354) ──

    @Test
    fun aRailKeepsFocusAt48dpUntilItsEnd() {
        // Home: 10 cards, pitch 114, track = 48 + 10·100 + 9·14 + 48 = 1222 dp, viewport 960.
        val width = 48f + 10 * 100f + 9 * 14f + 48f
        assertEquals(0f, RevealMath.trackShift(48f, 48f, width, 960f))
        assertEquals(114f, RevealMath.trackShift(48f + 114f, 48f, width, 960f))
        assertEquals(228f, RevealMath.trackShift(48f + 228f, 48f, width, 960f))
        // Clamped: the last card stays fully visible at the right edge.
        assertEquals(width - 960f, RevealMath.trackShift(48f + 9 * 114f, 48f, width, 960f))
    }

    @Test
    fun aShortRailNeverMoves() {
        assertEquals(0f, RevealMath.trackShift(48f + 3 * 114f, 48f, 960f, 960f))
    }

    @Test
    fun shiftsRoundOnTheCssPixelGrid() {
        assertEquals(10.5f, RevealMath.trackShift(58.3f, 48f, 2000f, 960f))
        assertEquals(10f, RevealMath.trackShift(58.2f, 48f, 2000f, 960f))
        // f64::round is half away from zero.
        assertEquals(-1.5f, roundCssPx(-1.25f))
        assertEquals(1.5f, roundCssPx(1.25f))
    }

    @Test
    fun aColumnOnlyEverMovesUp() {
        assertEquals(0f, RevealMath.columnShift(0f, 0f))
        assertEquals(-200f, RevealMath.columnShift(0f, 200f))
        // Detail anchors at 360 dp: rows above it never move down.
        assertEquals(0f, RevealMath.columnShift(360f, 100f))
        assertEquals(-140f, RevealMath.columnShift(360f, 500f))
    }
}
