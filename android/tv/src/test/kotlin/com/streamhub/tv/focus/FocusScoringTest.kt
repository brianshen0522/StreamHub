package com.streamhub.tv.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `measure` / `score` / `find_target` (focus.rs:587-723), on layouts shaped
 * like the screens: Home rails (100 dp cards, 114 dp pitch), a top bar, a
 * search field above a results column, Detail's season/episode groups.
 */
class FocusScoringTest {

    private data class El(val name: String, val rect: FocusRect, val group: String? = null, val column: String? = null, val selected: Boolean = false)

    private fun card(name: String, i: Int, top: Float, group: String, column: String? = null) =
        El(name, FocusRect(48f + 114f * i, top, 148f + 114f * i, top + 150f), group, column)

    private fun find(
        cur: El,
        dir: Dir,
        all: List<El>,
        remembered: Map<String, El> = emptyMap(),
    ): El? = FocusScoring.findTarget(
        cur = cur,
        dir = dir,
        candidates = all,
        rect = { it.rect },
        group = { it.group },
        columnOf = { it.column },
        inColumn = { e, c -> e.column == c },
        remembered = { remembered[it] },
        isSelected = { it.selected },
    )

    @Test
    fun gapIsZeroWhenIntervalsOverlap() {
        assertEquals(0f, FocusScoring.gap(0f, 10f, 5f, 15f))
        assertEquals(5f, FocusScoring.gap(0f, 10f, 15f, 20f))
        assertEquals(5f, FocusScoring.gap(15f, 20f, 0f, 10f))
        assertEquals(0f, FocusScoring.gap(0f, 10f, 10f, 20f))
    }

    @Test
    fun measureRequiresTheCentreToClearAQuarterOfTheFocusedSize() {
        val cur = FocusRect(0f, 0f, 100f, 100f)
        // Centre only 20 dp to the right: inside the 25 dp slack.
        assertNull(FocusScoring.measure(cur, FocusRect(20f, 0f, 120f, 100f), Dir.Right))
        // 30 dp: clears it.
        val m = FocusScoring.measure(cur, FocusRect(30f, 0f, 130f, 100f), Dir.Right)
        assertNotNull(m)
        // Overlapping on the primary axis clamps to 0.
        assertEquals(0f, m!!.primary)
    }

    @Test
    fun measureRequiresTheLeadingEdgeToClearSlack() {
        val cur = FocusRect(0f, 0f, 10f, 10f)
        // slack_x = max(2.5, 1) = 2.5: centre clears, but l must be ≥ cur.l + 1.
        assertNull(FocusScoring.measure(cur, FocusRect(0.5f, 0f, 20f, 10f), Dir.Right))
        assertNotNull(FocusScoring.measure(cur, FocusRect(1f, 0f, 20f, 10f), Dir.Right))
    }

    @Test
    fun measureFillsTheCrossAxisFields() {
        val cur = FocusRect(100f, 100f, 200f, 150f)
        val m = FocusScoring.measure(cur, FocusRect(300f, 170f, 400f, 220f), Dir.Right)!!
        assertEquals(100f, m.primary)
        assertEquals(20f, m.crossGap)
        assertEquals(70f, m.crossCentre)
        assertEquals(70f, m.crossEdge)
        assertEquals(100f + 20f * 6f + 70f * 0.3f, FocusScoring.score(m), 1e-4f)
    }

    @Test
    fun verticalMeasureUsesTheHorizontalCrossAxis() {
        val cur = FocusRect(100f, 100f, 200f, 150f)
        val m = FocusScoring.measure(cur, FocusRect(250f, 200f, 300f, 260f), Dir.Down)!!
        assertEquals(50f, m.primary)
        assertEquals(50f, m.crossGap)
        assertEquals(125f, m.crossCentre)
        assertEquals(150f, m.crossEdge)
        assertNull(FocusScoring.measure(cur, FocusRect(250f, 200f, 300f, 260f), Dir.Up))
    }

    @Test
    fun rightStaysInTheRail() {
        val rail = (0 until 5).map { card("a$it", it, 300f, "a") }
        assertEquals("a1", find(rail[0], Dir.Right, rail)?.name)
        assertEquals("a3", find(rail[2], Dir.Right, rail)?.name)
        assertEquals("a1", find(rail[2], Dir.Left, rail)?.name)
    }

    @Test
    fun theEndOfARailNeverJumpsDiagonally() {
        val a = (0 until 3).map { card("a$it", it, 200f, "a") }
        val b = (0 until 8).map { card("b$it", it, 400f, "b") }
        val all = a + b
        // b has cards further right, but on another line: Right at a2 is an edge.
        assertNull(find(a[2], Dir.Right, all))
        assertNull(find(a[0], Dir.Left, all))
    }

    @Test
    fun leavingAGroupSidewaysOnlyToTheSameLine() {
        val a = (0 until 2).map { card("a$it", it, 200f, "a") }
        // An ungrouped button on the same line, to the right of the rail.
        val btn = El("btn", FocusRect(400f, 250f, 460f, 280f))
        assertEquals("btn", find(a[1], Dir.Right, a + btn)?.name)
    }

    @Test
    fun downPicksTheNearestRowThenTheAlignedLeadingEdge() {
        val a = (0 until 6).map { card("a$it", it, 100f, "a") }
        val b = (0 until 6).map { card("b$it", it, 300f, "b") }
        val all = a + b
        assertEquals("b3", find(a[3], Dir.Down, all)?.name)
        assertEquals("a5", find(b[5], Dir.Up, all)?.name)
    }

    @Test
    fun enteringARailFromAWideElementLandsOnItsStart() {
        // The search field is far wider than a card; Down lands on the card under its left edge.
        val field = El("q", FocusRect(48f, 32f, 668f, 70f))
        val rail = (0 until 6).map { card("r$it", it, 120f, "r") }
        assertEquals("r0", find(field, Dir.Down, listOf(field) + rail)?.name)
    }

    @Test
    fun tiesGoToTheFirstInDocumentOrder() {
        val cur = El("cur", FocusRect(100f, 100f, 200f, 150f))
        val up = El("up", FocusRect(100f, 0f, 200f, 50f))
        val down1 = El("d1", FocusRect(100f, 200f, 200f, 250f))
        val down2 = El("d2", FocusRect(100f, 200f, 200f, 250f))
        assertEquals("d1", find(cur, Dir.Down, listOf(cur, up, down1, down2))?.name)
        assertEquals("d2", find(cur, Dir.Down, listOf(cur, up, down2, down1))?.name)
    }

    @Test
    fun enteringAGroupReturnsToItsRememberedElement() {
        val a = (0 until 6).map { card("a$it", it, 100f, "a") }
        val b = (0 until 6).map { card("b$it", it, 300f, "b") }
        val all = a + b
        assertEquals("b4", find(a[0], Dir.Down, all, remembered = mapOf("b" to b[4]))?.name)
        // A remembered element that is no longer a candidate is ignored.
        val gone = card("gone", 2, 300f, "b")
        assertEquals("b0", find(a[0], Dir.Down, all, remembered = mapOf("b" to gone))?.name)
    }

    @Test
    fun enteringAGroupTheFirstTimeLandsOnItsSelectedElement() {
        val play = El("play", FocusRect(48f, 100f, 148f, 136f), group = "actions")
        val eps = (0 until 8).map { i ->
            El("e$i", FocusRect(48f + 114f * i, 300f, 148f + 114f * i, 360f), group = "eps", selected = i == 5)
        }
        assertEquals("e5", find(play, Dir.Down, listOf(play) + eps)?.name)
    }

    @Test
    fun aColumnsOwnRowsComeBeforeAnythingOutsideIt() {
        // Rows slid up out of view sit far above; the search field outside the
        // column is geometrically nearer, yet Up means "the previous row".
        val field = El("q", FocusRect(48f, 32f, 668f, 70f))
        val r0 = (0 until 3).map { card("r0$it", it, -300f, "r0", column = "results") }
        val r1 = (0 until 3).map { card("r1$it", it, 100f, "r1", column = "results") }
        val all = listOf(field) + r0 + r1
        assertEquals("r00", find(r1[0], Dir.Up, all)?.name)
        // From the first row, nothing in the column is above: the field.
        val r0Shown = r0.map { it.copy(rect = it.rect.copy(t = 100f, b = 250f)) }
        assertEquals("q", find(r0Shown[0], Dir.Up, listOf(field) + r0Shown)?.name)
    }

    @Test
    fun noCandidatesInADirectionIsAnEdge() {
        val only = El("only", FocusRect(0f, 0f, 10f, 10f))
        assertNull(find(only, Dir.Up, listOf(only)))
    }
}
