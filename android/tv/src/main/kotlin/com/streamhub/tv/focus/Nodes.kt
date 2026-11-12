package com.streamhub.tv.focus

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.node.traverseAncestors
import androidx.compose.ui.node.traverseDescendants
import androidx.compose.ui.unit.Constraints
import com.streamhub.tv.ui.theme.TvMotion
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/*
 * The engine's view of the layout tree. Each `data-*` attribute focus.rs reads
 * off the DOM is a Modifier.Node here, all sharing one traverse key, so the
 * engine walks them the way focus.rs walks the document:
 *
 *   querySelectorAll  → traverseDescendants from the root (document order)
 *   el.closest(sel)   → traverseAncestors from the element
 *   getBoundingClientRect → root.localBoundingBoxOf(node, clipBounds = false),
 *                          which includes every graphicsLayer transform
 *   offsetLeft/Top walk  → localPositionOf from an untransformed anchor
 */

internal const val TV_NODE_KEY = "com.streamhub.tv.focus.node"

internal abstract class TvNode : Modifier.Node(), TraversableNode {
    override val traverseKey: Any get() = TV_NODE_KEY

    /** This node's coordinates, or null when it is not placed. */
    fun coordinatesOrNull(): LayoutCoordinates? =
        if (isAttached) runCatching { requireLayoutCoordinates() }.getOrNull()?.takeIf { it.isAttached } else null

    inline fun <reified T : TvNode> closest(): T? {
        if (!isAttached) return null
        var found: T? = null
        traverseAncestors(TV_NODE_KEY) { n ->
            if (n is T) {
                found = n
                false
            } else {
                true
            }
        }
        return found
    }

    inline fun <reified T : TvNode> ancestorsOf(): List<T> {
        val out = ArrayList<T>()
        if (!isAttached) return out
        traverseAncestors(TV_NODE_KEY) { n ->
            if (n is T) out.add(n)
            true
        }
        return out
    }

    fun hasAncestor(node: TvNode): Boolean {
        if (!isAttached) return false
        var found = false
        traverseAncestors(TV_NODE_KEY) { n ->
            if (n === node) {
                found = true
                false
            } else {
                true
            }
        }
        return found
    }

    inline fun <reified T : TvNode> descendants(): List<T> {
        val out = ArrayList<T>()
        if (!isAttached) return out
        traverseDescendants(TV_NODE_KEY) { n ->
            if (n is T) out.add(n)
            androidx.compose.ui.node.TraversableNode.Companion.TraverseDescendantsAction.ContinueTraversal
        }
        return out
    }
}

// ── root ────────────────────────────────────────────────────────────────────

internal class FocusRootNode(var engine: TvFocusEngine) : TvNode() {
    override fun onAttach() {
        engine.root = this
    }

    override fun onDetach() {
        if (engine.root === this) engine.root = null
    }

    val density: Float get() = requireDensity().density
}

internal data class FocusRootElement(val engine: TvFocusEngine) : ModifierNodeElement<FocusRootNode>() {
    override fun create() = FocusRootNode(engine)
    override fun update(node: FocusRootNode) {
        node.engine = engine
        engine.root = node
    }
}

// ── focusable: anchor (untransformed) → layer (focus + bump transform) → node (as drawn) ──

/** Marks where the element is laid out before its own transform, for `offset_within`. */
internal class AnchorNode(var target: TvFocusTarget) : TvNode() {
    override fun onAttach() {
        target.anchor = this
    }

    override fun onDetach() {
        if (target.anchor === this) target.anchor = null
    }
}

internal data class AnchorElement(val target: TvFocusTarget) : ModifierNodeElement<AnchorNode>() {
    override fun create() = AnchorNode(target)
    override fun update(node: AnchorNode) {
        node.target = target
        target.anchor = node
    }
}

/**
 * The element's own transform: focus scale and lift (animated with the
 * component's transition), composed after the edge-bump nudge, exactly as
 * `transform: translateX(12px) var(--fx)` composes in styles.css:945-959.
 */
internal class FocusLayerNode(var target: TvFocusTarget, var transform: TvFocusTransform) : TvNode(), LayoutModifierNode {
    private val focus = Animatable(if (target.isFocused) 1f else 0f)
    private val bump = Animatable(1f)
    private var bumpDir: Dir? = null

    override fun onAttach() {
        target.layer = this
    }

    override fun onDetach() {
        if (target.layer === this) target.layer = null
    }

    fun onFocusChanged(focused: Boolean) {
        if (!isAttached) return
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            focus.animateTo(if (focused) 1f else 0f, tween(transform.durationMs, easing = TvMotion.Ease))
        }
    }

    fun playBump(dir: Dir) {
        if (!isAttached) return
        bumpDir = dir
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            bump.snapTo(0f)
            bump.animateTo(1f, tween(TvMotion.BumpMs, easing = androidx.compose.animation.core.LinearEasing))
        }
    }

    private val layerBlock: GraphicsLayerScope.() -> Unit = {
        val f = focus.value
        var s = 1f + (transform.scale - 1f) * f
        val b = bumpDir?.let { TvMotion.bump(bump.value) } ?: 0f
        if (!transform.bumpKeepsScale && b > 0f) s += (1f - s) * b
        val nudge = TvMotion.BumpDistance.toPx() * b
        scaleX = s
        scaleY = s
        transformOrigin = transform.origin
        // translate(bump) · scale(s) · translateY(lift): the lift is scaled.
        translationY = transform.lift.toPx() * f * s + when (bumpDir) {
            Dir.Up -> -nudge
            Dir.Down -> nudge
            else -> 0f
        }
        translationX = when (bumpDir) {
            Dir.Left -> -nudge
            Dir.Right -> nudge
            else -> 0f
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val p = measurable.measure(constraints)
        return layout(p.width, p.height) { p.placeWithLayer(0, 0, layerBlock = layerBlock) }
    }
}

internal data class FocusLayerElement(val target: TvFocusTarget, val transform: TvFocusTransform) :
    ModifierNodeElement<FocusLayerNode>() {
    override fun create() = FocusLayerNode(target, transform)
    override fun update(node: FocusLayerNode) {
        node.target = target
        node.transform = transform
        target.layer = node
    }
}

/** `[data-focusable]`: the element as drawn. Its bounds include its own transform. */
internal class FocusableNode(var target: TvFocusTarget) : TvNode() {
    override fun onAttach() {
        target.node = this
    }

    override fun onDetach() {
        if (target.node === this) target.node = null
    }
}

internal data class FocusableElement(val target: TvFocusTarget) : ModifierNodeElement<FocusableNode>() {
    override fun create() = FocusableNode(target)
    override fun update(node: FocusableNode) {
        node.target = target
        target.node = node
    }
}

// ── containers ───────────────────────────────────────────────────────────────

/** `[data-rail-track]` (with its `data-focus-group` and `data-rail-start`). Sits inside the track's translation. */
internal class TrackNode(var state: TvRailTrackState, var group: String?, var startDp: Float) : TvNode() {
    override fun onAttach() {
        state.node = this
    }

    override fun onDetach() {
        if (state.node === this) state.node = null
    }
}

internal data class TrackElement(val state: TvRailTrackState, val group: String?, val startDp: Float) :
    ModifierNodeElement<TrackNode>() {
    override fun create() = TrackNode(state, group, startDp)
    override fun update(node: TrackNode) {
        node.state = state
        node.group = group
        node.startDp = startDp
        state.node = node
    }
}

/** `[data-row-column]` with `data-row-anchor`. Sits inside the column's translation. */
internal class ColumnNode(var state: TvColumnState, var anchorDp: Float) : TvNode()

internal data class ColumnElement(val state: TvColumnState, val anchorDp: Float) : ModifierNodeElement<ColumnNode>() {
    override fun create() = ColumnNode(state, anchorDp)
    override fun update(node: ColumnNode) {
        node.state = state
        node.anchorDp = anchorDp
    }
}

/** `[data-row]`: a row of a column; gets is-current / is-past. */
internal class RowNode(var state: TvRowState) : TvNode()

internal data class RowElement(val state: TvRowState) : ModifierNodeElement<RowNode>() {
    override fun create() = RowNode(state)
    override fun update(node: RowNode) {
        node.state = state
    }
}

/** `[data-focus-scope]`: a modal. */
internal class ScopeNode : TvNode()

internal object ScopeElement : ModifierNodeElement<ScopeNode>() {
    override fun create() = ScopeNode()
    override fun update(node: ScopeNode) = Unit
    override fun hashCode() = 1
    override fun equals(other: Any?) = other === this
}

/** `[data-focus-group]` on a container that is not a rail track. */
internal class GroupNode(var name: String) : TvNode()

internal data class GroupElement(val name: String) : ModifierNodeElement<GroupNode>() {
    override fun create() = GroupNode(name)
    override fun update(node: GroupNode) {
        node.name = name
    }
}

/** A named area (`.tv-rows`, `.tv-results`, `[data-menu]`…) for selectors. */
internal class RegionNode(var name: String) : TvNode()

internal data class RegionElement(val name: String) : ModifierNodeElement<RegionNode>() {
    override fun create() = RegionNode(name)
    override fun update(node: RegionNode) {
        node.name = name
    }
}

/** `[data-loading]` ([skeleton] = false) or `.tv-skeleton` ([skeleton] = true). */
internal class LoadingNode(var skeleton: Boolean) : TvNode()

internal data class LoadingElement(val skeleton: Boolean) : ModifierNodeElement<LoadingNode>() {
    override fun create() = LoadingNode(skeleton)
    override fun update(node: LoadingNode) {
        node.skeleton = skeleton
    }
}

/** Offset of [inner] inside [outer] in px; transforms between them are applied (there are none by construction). */
internal fun offsetWithin(inner: TvNode, outer: TvNode): Offset? {
    val i = inner.coordinatesOrNull() ?: return null
    val o = outer.coordinatesOrNull() ?: return null
    return runCatching { o.localPositionOf(i, Offset.Zero) }.getOrNull()
}

/** Default transform origin, as CSS (50% 50%). */
internal val CenterOrigin = TransformOrigin.Center
