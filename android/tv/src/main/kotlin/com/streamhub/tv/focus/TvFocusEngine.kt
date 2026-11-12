package com.streamhub.tv.focus

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.streamhub.tv.nav.TvNav
import com.streamhub.tv.sfx.Sound
import com.streamhub.tv.sfx.UiSounds
import com.streamhub.tv.ui.theme.TvMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A screen's key handler (`focus::set_screen_handler`). It gets every key first. */
interface TvKeyHandler {
    /** A keydown. True consumes it (and the engine plays the move/select sound for it). Back arrives here first too. */
    fun onKey(press: TvKeyPress): Boolean

    /**
     * Every keyup, before the engine's own handling — the player's
     * capture-phase keyup listener (scrub end, OK tap vs long press).
     */
    fun onKeyUp(key: TvKey, keyCode: Int) {}
}

/** Which focusables a query matches (a CSS selector in focus.rs). Build with [TvSelect]. */
fun interface TvSelector {
    fun matches(target: TvFocusTarget): Boolean
}

/** Selector builders: `[data-focus-id=…]`, `[data-action=…]`, `.tv-rows [data-focusable]` … */
object TvSelect {
    val any = TvSelector { true }
    val autofocus = TvSelector { it.autofocus }
    fun id(focusId: String) = TvSelector { it.focusId == focusId }
    fun action(action: String) = TvSelector { it.action == action }
    fun group(group: String) = TvSelector { t -> t.group == group || t.node?.let { groupOfNode(it) } == group }
    fun inRegion(name: String) = TvSelector { it.inRegion(name) }
    fun inRow(row: TvRowState) = TvSelector { it.row() === row }
    val textField = TvSelector { it.isTextField }
    infix fun TvSelector.and(other: TvSelector) = TvSelector { matches(it) && other.matches(it) }
}

internal fun groupOfNode(n: FocusableNode): String? {
    n.target.group?.let { return it }
    var found: String? = null
    for (a in n.ancestorsOf<TvNode>()) {
        found = when (a) {
            is TrackNode -> a.group
            is GroupNode -> a.name
            else -> null
        }
        if (found != null) break
    }
    return found
}

/**
 * The focus engine: a port of `tizen/app/src/focus.rs`. It owns every key —
 * the activity forwards `dispatchKeyEvent` to [onKeyEvent] — and moves a single
 * "focused" state between [TvFocusTarget]s by Tizen's geometry and scoring.
 * Compose's own focus traversal is not used; Compose focus is only given to a
 * text field the engine focuses, because the IME needs it.
 *
 * Everything runs on the main thread. See android/tv/ARCHITECTURE.md.
 */
@Stable
class TvFocusEngine(
    private val nav: TvNav,
    private val sounds: UiSounds = UiSounds.None,
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
) {
    /** Leave the app (`exit_app`). The activity sets it to `finish()`. */
    var onExitApp: () -> Unit = {}

    /** Whether "離開 StreamHub？" is showing (`exit_prompt()`). */
    var exitPromptOpen: Boolean by mutableStateOf(false)
        private set

    /** Keys pressed since launch (`key_count`). */
    var keyCount: Long = 0
        private set

    /** The intent of the click being handled right now (Open outside a click). */
    var intent: TvIntent = TvIntent.Open
        private set

    internal var root: FocusRootNode? = null
    private var scope: CoroutineScope? = null
    private var rootFocus: FocusRequester? = null
    /** A text field currently holds Compose focus. */
    internal var textHasComposeFocus = false

    private var focusedTarget: TvFocusTarget? = null
    private val groups = HashMap<String, TvFocusTarget>()
    private val memory = RouteMemory()
    private var beforeScope: TvFocusTarget? = null
    private val pacer = HoldPacer(clock)
    private val bumpGate = BumpGate(clock)
    private val buffer = KeyBuffer(clock)
    private var replaying = false
    private var press: Press? = null
    private var pressGen = 0L
    private var revealPending: TvFocusTarget? = null
    private var handler: TvKeyHandler? = null
    /** Keydowns the engine consumed; their keyups are swallowed too. */
    private val consumedDowns = HashSet<Int>()

    private class Press(val target: TvFocusTarget, val gen: Long, var fired: Boolean)

    internal fun attach(scope: CoroutineScope, rootFocus: FocusRequester) {
        this.scope = scope
        this.rootFocus = rootFocus
    }

    internal fun detach(scope: CoroutineScope) {
        if (this.scope === scope) {
            this.scope = null
            this.rootFocus = null
        }
    }

    private val density: Float get() = root?.takeIf { it.isAttached }?.density ?: 1f

    // ── public API (focus.rs names in comments) ─────────────────────────────

    /** The element the remote is on, if it is still placed (`focused()`). */
    val focused: TvFocusTarget? get() = focusedTarget?.takeIf { it.isAttached }

    /** Move focus to [target] (`focus_element`), recording it in route memory. */
    fun focus(target: TvFocusTarget) = focusElement(target, remember = true)

    /** Focus the first usable match in document order (`focus_selector`). False when none exists yet. */
    fun focusFirst(selector: TvSelector): Boolean {
        val el = allFocusables().firstOrNull { usable(it) && selector.matches(it) } ?: return false
        focusElement(el, true)
        return true
    }

    /** [focusFirst] once the current change has composed and laid out (`focus_later`). */
    fun focusLater(selector: TvSelector) = afterLayout { focusFirst(selector) }

    /** Return to the element last focused on this navigation entry (`restore()`). */
    fun restore(): Boolean {
        val id = memory.restore(nav.depth, nav.current.memoryKey) ?: return false
        return focusFirst(TvSelect.id(id))
    }

    /** True when the focused element matches [selector] (`focused_within`; use region/row selectors). */
    fun focusedWithin(selector: TvSelector): Boolean = focused?.let { selector.matches(it) } ?: false

    /**
     * Put every rail and column back at rest and focus the first match
     * (`back_to`). False (and nothing done) when focus is already there and
     * every track is at rest — the caller's Back then goes further.
     */
    fun backTo(selector: TvSelector): Boolean {
        val target = allFocusables().firstOrNull { usable(it) && selector.matches(it) } ?: return false
        val tracks = allNodes<TrackNode>()
        val atRest = tracks.all { (it.state.shift ?: 0f) == 0f }
        if (focused === target && atRest) return false
        for (t in tracks) {
            t.state.shift = 0f
            animate(t.state.offset, 0f, TvMotion.T)
        }
        for (c in allNodes<ColumnNode>()) restColumn(c)
        focusElement(target, true)
        return true
    }

    /** Keep focus on something real (`ensure_valid`). Runs every 200 ms and after modals open or close. */
    fun ensureValid() = ensureValidInner(active = false)

    /** Set (or clear with null) the screen's key handler. Prefer the [TvScreenKeys] composable. */
    fun setScreenHandler(h: TvKeyHandler?) {
        handler = h
    }

    /** Clear [h] only if it is still the current one (a new screen may have set its own first). */
    fun clearScreenHandler(h: TvKeyHandler) {
        if (handler === h) handler = null
    }

    fun openExitPrompt() {
        exitPromptOpen = true
        afterLayout { ensureValid() }
    }

    fun closeExitPrompt() {
        exitPromptOpen = false
        afterLayout { ensureValid() }
    }

    fun exitApp() = onExitApp()

    /** Click [target] as the given intent (what OK / Play / red do). */
    fun click(target: TvFocusTarget, intent: TvIntent = TvIntent.Open) {
        this.intent = intent
        try {
            target.onClick(intent)
        } finally {
            this.intent = TvIntent.Open
        }
    }

    /** The current route-memory entries, for tests and debugging. */
    fun routeMemory(): Map<String, String> = memory.snapshot()

    // ── keys ──────────────────────────────────────────────────────────────

    /** Feed every key event here from `Activity.dispatchKeyEvent`. True = consumed. */
    fun onKeyEvent(event: KeyEvent): Boolean = when (event.action) {
        KeyEvent.ACTION_DOWN -> keyDown(event.keyCode, event.repeatCount > 0)
        KeyEvent.ACTION_UP -> keyUp(event.keyCode)
        else -> false
    }

    /** A keydown (`on_keydown`, focus.rs:992-1140). */
    fun keyDown(keyCode: Int, repeat: Boolean): Boolean {
        val consumed = keyDownInner(keyCode, repeat)
        if (consumed) consumedDowns.add(keyCode)
        return consumed
    }

    private fun keyDownInner(keyCode: Int, repeat: Boolean): Boolean {
        keyCount += 1
        val field = focused?.takeIf { it.isTextField }
        val hooks = field?.textField
        // A composition in progress belongs to the IME.
        if (hooks != null && hooks.value().composition != null) return false
        val key = TvKeyMap.map(keyCode, inTextField = hooks != null)
        val h = if (exitPromptOpen) null else handler

        if (hooks != null) {
            // Backspace edits the field; it is not Back here.
            if (keyCode == KeyEvent.KEYCODE_DEL) return false
            if (key == TvKey.Enter) {
                hooks.submit()
                return true
            }
            if (key == TvKey.Left || key == TvKey.Right) {
                // Leave only from the edge the caret is pressed against.
                val v = hooks.value()
                val sel: TextRange = v.selection
                val atEdge = sel.collapsed && if (key == TvKey.Left) sel.start == 0 else sel.end == v.text.length
                if (!atEdge) return false
            }
        }

        // The screen gets every key first; Back goes through the chain below.
        if (key != TvKey.Back && h != null && h.onKey(TvKeyPress(key, repeat, keyCode))) {
            when {
                key.isArrow && repeat -> sounds.playRepeat(Sound.Move)
                key.isArrow -> sounds.play(Sound.Move)
                key == TvKey.Enter && !repeat -> sounds.play(Sound.Select)
                else -> Unit
            }
            return true
        }

        val dir = when (key) {
            TvKey.Up, TvKey.ChannelUp, TvKey.PageUp -> Dir.Up
            TvKey.Down, TvKey.ChannelDown, TvKey.PageDown -> Dir.Down
            TvKey.Left -> Dir.Left
            TvKey.Right -> Dir.Right
            else -> null
        }
        if (dir != null) {
            // A whole row at a time, every keydown, if the remote has them.
            val steps = if (key.isRowKey) 1 else pacer.steps(key, repeat)
            if (steps > 0) step(dir, steps, repeat)
            return true
        }

        return when (key) {
            TvKey.Enter -> {
                if (!repeat) okDown()
                true
            }
            TvKey.Back -> {
                // Tizen never delivers Back repeats; holding it must not pop several screens.
                if (!repeat) handleBack(h)
                true
            }
            TvKey.Exit -> {
                exitApp()
                true
            }
            TvKey.Play, TvKey.PlayPause -> {
                val el = focused?.takeIf { usable(it) && it.playable } ?: return false
                sounds.play(Sound.Select)
                click(el, TvIntent.Play)
                true
            }
            TvKey.Red -> {
                val el = focused?.takeIf { usable(it) && it.favable } ?: return false
                sounds.play(Sound.Select)
                click(el, TvIntent.Favorite)
                true
            }
            else -> false
        }
    }

    /** A keyup (`on_keyup`, focus.rs:1142-1160). */
    fun keyUp(keyCode: Int): Boolean {
        val key = TvKeyMap.map(keyCode, inTextField = focused?.isTextField == true)
        handler?.onKeyUp(key, keyCode)
        when {
            key.isArrow -> pacer.release()
            key == TvKey.Enter -> {
                val p = press
                press = null
                // A short press clicks now; a long one already fired.
                if (p != null && !p.fired && p.target.isAttached) click(p.target)
            }
        }
        return consumedDowns.remove(keyCode)
    }

    private fun okDown() {
        val target = focused?.takeIf { usable(it) }
        // On the press, not the release: feedback has to be immediate.
        if (target != null) sounds.play(Sound.Select)
        when {
            target == null -> ensureValidNow()
            target.longPress -> {
                rememberRoute(target)
                pressGen += 1
                val gen = pressGen
                press = Press(target, gen, false)
                launch {
                    delay(LONG_PRESS_MS)
                    val p = press
                    if (p != null && p.gen == gen && !p.fired) {
                        p.fired = true
                        if (p.target.isAttached) click(p.target, TvIntent.LongPress)
                    }
                }
            }
            else -> {
                // Pressing OK is a choice even where focus only landed by default.
                rememberRoute(target)
                click(target)
            }
        }
    }

    private fun handleBack(h: TvKeyHandler?) {
        buffer.clear()
        sounds.play(Sound.Back)
        if (h != null && h.onKey(TvKeyPress(TvKey.Back, false, KeyEvent.KEYCODE_BACK))) return
        if (exitPromptOpen) {
            closeExitPrompt()
            return
        }
        if (nav.back()) {
            afterLayout { ensureValid() }
            return
        }
        openExitPrompt()
    }

    // ── movement ──────────────────────────────────────────────────────────

    /** Move [steps] times in [dir] (`step`, focus.rs:776-820). False when nothing moved. */
    private fun step(dir: Dir, steps: Int, repeat: Boolean): Boolean {
        var cur = focused?.takeIf { usable(it) } ?: run {
            // Nothing to move from yet (route change, loading): keep the key.
            if (!repeat) buffer.push(dir)
            ensureValidNow()
            return false
        }
        var moved = false
        for (i in 0 until maxOf(steps, 1)) {
            val t = findTarget(cur, dir) ?: break
            focusElement(t, true)
            cur = t
            moved = true
        }
        if (!moved) {
            // Rows still loading can only arrive below: Down waits for them.
            if (dir == Dir.Down && screenLoading(includeSkeletons = true) && !repeat) {
                buffer.push(dir)
            } else if (bump(cur, dir)) {
                if (pacer.edged(repeat)) sounds.play(Sound.Edge)
            }
        } else {
            pacer.moved()
            if (repeat) sounds.playRepeat(Sound.Move) else sounds.play(Sound.Move)
        }
        return moved
    }

    private fun findTarget(cur: TvFocusTarget, dir: Dir): TvFocusTarget? {
        val all = candidates()
        return FocusScoring.findTarget(
            cur = cur,
            dir = dir,
            candidates = all,
            rect = { visualRect(it) ?: FocusRect(0f, 0f, 0f, 0f) },
            group = ::groupOf,
            columnOf = { it.node?.closest<ColumnNode>() },
            inColumn = { e, col -> e.node?.hasAncestor(col as TvNode) == true },
            remembered = { g -> groups[g]?.takeIf { usable(it) } },
            isSelected = { it.selected },
        )
    }

    /** Show the edge nudge (`bump`). False within 300 ms of the last one (nothing shown, no sound). */
    private fun bump(target: TvFocusTarget, dir: Dir): Boolean {
        if (!bumpGate.tryBump()) return false
        target.bump = dir
        target.layer?.playBump(dir)
        return true
    }

    // ── focusing ──────────────────────────────────────────────────────────

    private fun focusElement(el: TvFocusTarget, remember: Boolean) {
        val previous = focusedTarget
        if (previous != null && previous !== el) {
            previous.setFocused(false)
            previous.bump = null
        }
        el.setFocused(true)
        focusedTarget = el
        groupOf(el)?.let { groups[it] = el }
        if (remember) rememberRoute(el)
        composeFocus(el)
        scheduleReveal(el)
        scheduleReplay()
    }

    private fun rememberRoute(el: TvFocusTarget) {
        // Inside a modal, the screen's memory is left alone.
        if (el.inScope()) return
        val id = el.focusId ?: return
        memory.record(nav.depth, nav.current.memoryKey, id)
    }

    /** Real Compose focus: on a text field (the IME needs it), otherwise parked on the root. */
    private fun composeFocus(el: TvFocusTarget) {
        val hooks = el.textField
        if (hooks != null) {
            runCatching { hooks.focusRequester.requestFocus() }
        } else if (textHasComposeFocus) {
            runCatching { rootFocus?.requestFocus() }
            textHasComposeFocus = false
        }
    }

    /** A text field gained Compose focus by other means (`on_focusin`): sync the engine. */
    internal fun onComposeFocus(target: TvFocusTarget) {
        textHasComposeFocus = true
        if (focusedTarget !== target && target.isAttached) focusElement(target, true)
    }

    private fun scheduleReveal(el: TvFocusTarget) {
        val first = revealPending == null
        revealPending = el
        if (!first) return
        val s = scope
        if (s == null) {
            revealPending = null
            reveal(el)
            return
        }
        s.launch {
            withFrameNanos { }
            val p = revealPending
            revealPending = null
            if (p != null && p.isAttached) reveal(p)
        }
    }

    private fun scheduleReplay() {
        if (replaying || buffer.isEmpty()) return
        launch {
            if (replaying) return@launch
            replaying = true
            val keys = buffer.drain()
            try {
                for (d in keys) step(d, 1, false)
            } finally {
                replaying = false
            }
        }
    }

    private fun ensureValidNow() = ensureValidInner(active = true)

    private fun ensureValidInner(active: Boolean) {
        if (root?.isAttached != true) return
        val scopeNode = activeScope()
        val current = focused?.takeIf { usable(it) }
        if (scopeNode != null) {
            val inside = current?.node?.hasAncestor(scopeNode) == true
            if (!inside) {
                if (current != null) beforeScope = current
                val inScope = scopeNode.descendants<FocusableNode>().map { it.target }.filter { usable(it) }
                (inScope.firstOrNull { it.autofocus } ?: inScope.firstOrNull())?.let { focusElement(it, true) }
            }
            return
        }
        val before = beforeScope
        beforeScope = null
        val inDeadScope = current?.inScope() == true
        if (current != null && !inDeadScope) return
        if (before != null && usable(before)) {
            focusElement(before, true)
            return
        }
        if (restore()) return
        if (!active && screenLoading(includeSkeletons = false)) return
        val all = allFocusables().filter { usable(it) }
        (all.firstOrNull { it.autofocus } ?: all.firstOrNull())?.let { focusElement(it, false) }
    }

    // ── reveal (focus.rs:311-388) ───────────────────────────────────────────

    private fun reveal(el: TvFocusTarget) {
        val node = el.node ?: return
        var handled = false
        val track = node.closest<TrackNode>()
        if (track != null) {
            revealInTrack(el, track)
            handled = true
        }
        val column = node.closest<ColumnNode>()
        for (other in allNodes<ColumnNode>()) {
            if (other !== column && other.state.hasFocus) restColumn(other)
        }
        if (column != null) {
            revealInColumn(node, column)
            handled = true
        }
        if (!handled) {
            launch { runCatching { el.bringIntoView.bringIntoView() } }
        }
    }

    private fun revealInTrack(el: TvFocusTarget, track: TrackNode) {
        val anchor = el.anchor ?: return
        val left = (offsetWithin(anchor, track) ?: return).x / density
        val trackWidth = (track.coordinatesOrNull()?.size?.width ?: return) / density
        val viewport = track.state.viewportPx / density
        val shift = RevealMath.trackShift(left, track.startDp, trackWidth, viewport)
        // A track just rendered takes its position at once; sliding in from 0
        // would read as the rail jumping.
        val fresh = track.state.shift == null
        track.state.shift = shift
        if (fresh) snap(track.state.offset, shift) else animate(track.state.offset, shift, TvMotion.T)
    }

    private fun revealInColumn(node: FocusableNode, column: ColumnNode) {
        val row = node.closest<RowNode>() ?: return
        val top = (offsetWithin(row, column) ?: return).y / density
        val shift = RevealMath.columnShift(column.anchorDp, top)
        animate(column.state.offset, shift, column.state.durationMs)
        column.state.hasFocus = true
        var past = true
        for (r in column.descendants<RowNode>()) {
            if (r.closest<ColumnNode>() !== column) continue
            val isCurrent = r === row
            if (isCurrent) past = false
            r.state.isCurrent = isCurrent
            r.state.isPast = past && !isCurrent
        }
    }

    private fun restColumn(column: ColumnNode) {
        animate(column.state.offset, 0f, column.state.durationMs)
        column.state.hasFocus = false
        for (r in column.descendants<RowNode>()) {
            r.state.isCurrent = false
            r.state.isPast = false
        }
    }

    private fun animate(a: androidx.compose.animation.core.Animatable<Float, *>, to: Float, ms: Int) {
        val s = scope
        if (s == null) {
            return
        }
        // A new target mid-flight retargets from where it is; it never queues.
        s.launch(start = CoroutineStart.UNDISPATCHED) { a.animateTo(to, tween(ms, easing = TvMotion.Ease)) }
    }

    private fun snap(a: androidx.compose.animation.core.Animatable<Float, *>, to: Float) {
        scope?.launch(start = CoroutineStart.UNDISPATCHED) { a.snapTo(to) }
    }

    // ── the tree ───────────────────────────────────────────────────────────

    private inline fun <reified T : TvNode> allNodes(): List<T> = root?.descendants<T>() ?: emptyList()

    private fun allFocusables(): List<TvFocusTarget> = allNodes<FocusableNode>().map { it.target }

    /** The modal scope focus is confined to: the last visible one (`active_scope`). */
    private fun activeScope(): ScopeNode? = allNodes<ScopeNode>().lastOrNull { visible(it) }

    /** The usable focusables inside the active scope, else the whole canvas (`candidates`). */
    private fun candidates(): List<TvFocusTarget> {
        val s = activeScope()
        val nodes = s?.descendants<FocusableNode>() ?: allNodes()
        return nodes.map { it.target }.filter { usable(it) }
    }

    private fun groupOf(el: TvFocusTarget): String? = el.node?.let { groupOfNode(it) } ?: el.group

    /** `[data-loading]` (and, for Down at an edge, `.tv-skeleton`) anywhere on the canvas. */
    private fun screenLoading(includeSkeletons: Boolean): Boolean =
        allNodes<LoadingNode>().any { includeSkeletons || !it.skeleton }

    /** The element's rect as drawn, in canvas dp (`getBoundingClientRect`). */
    internal fun visualRect(el: TvFocusTarget): FocusRect? {
        val n = el.node ?: return null
        return rectOf(n)
    }

    private fun rectOf(n: TvNode): FocusRect? {
        val r = root?.coordinatesOrNull() ?: return null
        val c = n.coordinatesOrNull() ?: return null
        val b = runCatching { r.localBoundingBoxOf(c, clipBounds = false) }.getOrNull() ?: return null
        val d = density
        return FocusRect(b.left / d, b.top / d, b.right / d, b.bottom / d)
    }

    private fun visible(n: TvNode): Boolean = rectOf(n)?.let { it.width > 0f && it.height > 0f } ?: false

    /** `is_usable`: placed, not disabled, non-zero size as drawn. */
    private fun usable(el: TvFocusTarget): Boolean {
        if (el.disabled || !el.isAttached) return false
        val r = visualRect(el) ?: return false
        return r.width > 0f && r.height > 0f
    }

    // ── scheduling ─────────────────────────────────────────────────────────

    private fun launch(block: suspend CoroutineScope.() -> Unit) {
        scope?.launch(block = block)
    }

    /** Run [block] once the current state change has been composed and laid out (Tizen's 0 ms timeout). */
    fun afterLayout(block: () -> Unit) {
        val s = scope ?: return
        s.launch {
            // Frame N recomposes and lays out after its frame callbacks; the
            // next frame's callback sees the result.
            withFrameNanos { }
            withFrameNanos { }
            block()
        }
    }

    internal suspend fun tick() {
        while (true) {
            delay(ENSURE_VALID_MS)
            ensureValid()
        }
    }

    companion object {
        /** `LONG_PRESS_MS`. */
        const val LONG_PRESS_MS = 700L
        /** The ensure-valid watchdog interval. */
        const val ENSURE_VALID_MS = 200L
        /** The rail start, 96 px. */
        val RailStart = RevealMath.RAIL_START_DP.dp
    }
}
