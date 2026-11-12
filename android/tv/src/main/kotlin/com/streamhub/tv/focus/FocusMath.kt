package com.streamhub.tv.focus

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/*
 * The pure half of the focus engine: geometry, scoring, pacing, the bump gate,
 * the key buffer, route memory and the reveal arithmetic. A line-by-line port
 * of tizen/app/src/focus.rs with no Android or Compose types, so all of it is
 * unit-tested on the JVM (src/test/.../focus).
 *
 * Units: canvas dp (960×540). Tizen works in CSS px = 2 dp; every constant here
 * is already converted (SLACK 2px = 1 dp, rail start 96px = 48 dp), and
 * rounding happens on the CSS-px grid ([roundCssPx]) as `f64::round` does there.
 */

/** A direction the remote can move in. */
enum class Dir { Up, Down, Left, Right }

/** A visual rectangle in canvas dp: left, top, right, bottom. */
data class FocusRect(val l: Float, val t: Float, val r: Float, val b: Float) {
    val cx: Float get() = (l + r) / 2f
    val cy: Float get() = (t + b) / 2f
    val width: Float get() = r - l
    val height: Float get() = b - t
}

/** How a candidate lies relative to the focused element (`focus.rs` `Measure`). */
data class Measure(
    /** Distance along the pressed direction, 0 when touching/overlapping. */
    val primary: Float,
    /** Gap on the other axis, 0 when they overlap. */
    val crossGap: Float,
    /** Offset between centres on the other axis. */
    val crossCentre: Float,
    /** Offset between leading (left/top) edges on the other axis. */
    val crossEdge: Float,
)

object FocusScoring {
    /** `SLACK = 2.0` CSS px. */
    const val SLACK = 1f

    /** Gap between two intervals, 0 when they overlap. */
    fun gap(a0: Float, a1: Float, b0: Float, b1: Float): Float = when {
        a1 < b0 -> b0 - a1
        b1 < a0 -> a0 - b1
        else -> 0f
    }

    /** Non-null when [c] lies in [dir] from [cur] (`focus.rs:613-640`). */
    fun measure(cur: FocusRect, c: FocusRect, dir: Dir): Measure? {
        val slackX = max((cur.r - cur.l) * 0.25f, SLACK)
        val slackY = max((cur.b - cur.t) * 0.25f, SLACK)
        fun horizontal(primary: Float) = Measure(
            primary = max(primary, 0f),
            crossGap = gap(cur.t, cur.b, c.t, c.b),
            crossCentre = abs(c.cy - cur.cy),
            crossEdge = abs(c.t - cur.t),
        )
        fun vertical(primary: Float) = Measure(
            primary = max(primary, 0f),
            crossGap = gap(cur.l, cur.r, c.l, c.r),
            crossCentre = abs(c.cx - cur.cx),
            crossEdge = abs(c.l - cur.l),
        )
        return when (dir) {
            Dir.Right -> if (c.cx > cur.cx + slackX && c.l >= cur.l + SLACK) horizontal(c.l - cur.r) else null
            Dir.Left -> if (c.cx < cur.cx - slackX && c.r <= cur.r - SLACK) horizontal(cur.l - c.r) else null
            Dir.Down -> if (c.cy > cur.cy + slackY && c.t >= cur.t + SLACK) vertical(c.t - cur.b) else null
            Dir.Up -> if (c.cy < cur.cy - slackY && c.b <= cur.b - SLACK) vertical(cur.t - c.b) else null
        }
    }

    /** Primary distance plus a heavy penalty on cross-axis offset. */
    fun score(m: Measure): Float = m.primary + m.crossGap * 6f + m.crossCentre * 0.3f

    /** The first minimum in list order (`min_by` + `total_cmp`). */
    private inline fun <E> pick(list: List<Pair<E, Measure>>, key: (Measure) -> Float): E? {
        var best: Pair<E, Measure>? = null
        var bestKey = 0f
        for (item in list) {
            val k = key(item.second)
            if (best == null || k.compareTo(bestKey) < 0) {
                best = item
                bestKey = k
            }
        }
        return best?.first
    }

    /**
     * `find_target` (`focus.rs:655-723`) over abstract elements.
     *
     * @param candidates the usable focusables of the active scope, in document order.
     * @param rect an element's visual rect (transforms included).
     * @param group an element's focus group (own or nearest ancestor's).
     * @param columnOf the nearest row column containing an element, if any.
     * @param inColumn whether an element lies anywhere inside a column.
     * @param remembered the element last focused in a group (may be stale).
     * @param isSelected `is-selected` (the current season or episode).
     */
    fun <E : Any> findTarget(
        cur: E,
        dir: Dir,
        candidates: List<E>,
        rect: (E) -> FocusRect,
        group: (E) -> String?,
        columnOf: (E) -> Any? = { null },
        inColumn: (E, Any) -> Boolean = { _, _ -> false },
        remembered: (String) -> E? = { null },
        isSelected: (E) -> Boolean = { false },
    ): E? {
        val curRect = rect(cur)
        val curGroup = group(cur)
        val rects = HashMap<E, FocusRect>()
        fun rectOf(e: E) = rects.getOrPut(e) { rect(e) }
        val measured = candidates.asSequence()
            .filter { it != cur }
            .mapNotNull { e -> measure(curRect, rectOf(e), dir)?.let { e to it } }
            .toList()
        if (measured.isEmpty()) return null

        val chosen: E = when (dir) {
            Dir.Left, Dir.Right -> {
                // Stay in the rail while it has somewhere to go.
                val same = curGroup?.let { g -> pick(measured.filter { group(it.first) == g }, ::score) }
                // Leaving sideways only to something on the same line: the end of
                // one rail must not jump diagonally into the next.
                same ?: pick(measured.filter { it.second.crossGap == 0f }, ::score) ?: return null
            }
            Dir.Up, Dir.Down -> {
                val column = columnOf(cur)
                val inCol = if (column != null) measured.filter { inColumn(it.first, column) } else emptyList()
                val pool = inCol.ifEmpty { measured }
                val nearest = pick(pool) { it.primary + it.crossCentre * 0.001f } ?: return null
                val n = rectOf(nearest)
                val row = pool.filter { val r = rectOf(it.first); r.t < n.b && r.b > n.t }
                pick(row) { it.crossGap * 10f + it.crossEdge } ?: nearest
            }
        }

        // Entering a different group returns to where it was left, or the first
        // time, to its selected item.
        val g = group(chosen)
        if (g != null && curGroup != g) {
            val r = remembered(g)
            if (r != null && candidates.contains(r)) return r
            candidates.firstOrNull { group(it) == g && isSelected(it) }?.let { return it }
        }
        return chosen
    }
}

/** f64::round: half away from zero. */
internal fun roundHalfAway(x: Float): Float = if (x >= 0f) floor(x + 0.5f) else -floor(-x + 0.5f)

/** Round a dp length on the CSS-px grid (1 px = 0.5 dp), as Tizen rounds px. */
fun roundCssPx(dp: Float): Float = roundHalfAway(dp * 2f) / 2f

/** The reveal arithmetic (`reveal_in_track`, `reveal_in_column`, `focus.rs:311-354`). */
object RevealMath {
    /** `data-rail-start` default, 96 px. */
    const val RAIL_START_DP = 48f

    /**
     * How far a rail track shifts left so the element at [left] (its untransformed
     * x inside the track, padding included) sits at [start], clamped so the end
     * of the rail stays put. Returns the positive shift; the track's
     * translationX is its negative.
     */
    fun trackShift(left: Float, start: Float, trackWidth: Float, viewport: Float): Float {
        val maxShift = max(trackWidth - viewport, 0f)
        return roundCssPx((left - start).coerceIn(0f, maxShift))
    }

    /** A column's translationY so its row at [rowTop] sits on [anchor]; rows above it never move down. */
    fun columnShift(anchor: Float, rowTop: Float): Float = roundCssPx(min(anchor - rowTop, 0f))
}

/**
 * Hold-to-scroll pacing (`hold_steps`, `focus.rs:822-851`) and the
 * one-edge-per-hold flag (`hold_edged`, `focus.rs:796-818`).
 */
class HoldPacer(private val clock: () -> Long) {
    var holdKey: TvKey? = null
        private set
    private var holdStart = 0L
    private var lastMove = 0L
    /** A held direction already sounded its edge. */
    var holdEdged = false
        private set

    /** How many steps this arrow keydown takes; 0 drops it. */
    fun steps(key: TvKey, repeat: Boolean): Int {
        val now = clock()
        if (!repeat || holdKey != key) {
            holdKey = key
            holdEdged = false
            holdStart = now
            lastMove = now
            return 1
        }
        val held = now - holdStart
        val horizontal = key == TvKey.Left || key == TvKey.Right
        val (interval, steps) = when {
            !horizontal -> 220L to 1
            held < 400L -> 160L to 1
            held < 1500L -> 80L to 1
            else -> 80L to 3
        }
        if (now - lastMove < interval) return 0
        lastMove = now
        return steps
    }

    /** Arrow keyup: release stops exactly where focus is. */
    fun release() {
        holdKey = null
    }

    /** Focus moved: the next wall may thud again. */
    fun moved() {
        holdEdged = false
    }

    /** A bump was shown. True when this is the first edge of the hold (play the Edge sound). */
    fun edged(repeat: Boolean): Boolean {
        val first = !(repeat && holdEdged)
        holdEdged = true
        return first
    }
}

/** The global 300 ms bump de-dupe (`bump`, `focus.rs:740-752`). */
class BumpGate(private val clock: () -> Long) {
    private var lastBump: Long? = null

    /** True when a bump may be shown now (and records it); false within 300 ms of the last. */
    fun tryBump(): Boolean {
        val now = clock()
        val last = lastBump
        if (last != null && now - last < WINDOW_MS) return false
        lastBump = now
        return true
    }

    companion object {
        const val WINDOW_MS = 300L
    }
}

/** Directions waiting for something focusable (`buffer`, `focus.rs:730-737, 473-501`). */
class KeyBuffer(private val clock: () -> Long) {
    private val keys = ArrayList<Pair<Dir, Long>>()
    val size: Int get() = keys.size
    fun isEmpty() = keys.isEmpty()

    fun push(dir: Dir) {
        if (keys.size < MAX) keys.add(dir to clock())
    }

    fun clear() = keys.clear()

    /** Take every key, keeping those at most 2 s old, in order. */
    fun drain(): List<Dir> {
        val now = clock()
        val fresh = keys.filter { now - it.second <= TTL_MS }.map { it.first }
        keys.clear()
        return fresh
    }

    companion object {
        const val MAX = 3
        const val TTL_MS = 2000L
    }
}

/** Route memory: `"{depth}|{what}"` → the focus id last focused there (`focus.rs:228-246, 395-409, 528-538`). */
class RouteMemory {
    private val routes = HashMap<String, String>()

    fun key(depth: Int, what: String) = "$depth|$what"

    private fun pruneDeeper(depth: Int) {
        routes.keys.removeAll { k -> k.substringBefore('|').toIntOrNull()?.let { it > depth } ?: false }
    }

    fun record(depth: Int, what: String, focusId: String) {
        pruneDeeper(depth)
        routes[key(depth, what)] = focusId
    }

    fun restore(depth: Int, what: String): String? {
        pruneDeeper(depth)
        return routes[key(depth, what)]
    }

    fun snapshot(): Map<String, String> = HashMap(routes)
}
