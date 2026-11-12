package com.streamhub.tv.focus

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.streamhub.tv.ui.theme.TvMotion
import com.streamhub.tv.ui.theme.TvSpace

/** Why a focusable is being clicked (`focus::Intent`). Also readable as [TvFocusEngine.intent] during the click. */
enum class TvIntent {
    /** OK (or anything else that clicks). */
    Open,
    /** The Play / PlayPause key on a `playable` element. */
    Play,
    /** OK held for 700 ms on a `longPress` element. */
    LongPress,
    /** The red key on a `favable` element. */
    Favorite,
}

/**
 * The element's own focus transform: what `.is-focused` sets as `transform`
 * (and `--fx`), and the transition that animates it.
 *
 * @param lift translateY after the scale (CSS `scale(s) translateY(lift)`), so
 *   it is scaled too: a card's −6px lift is −3 dp × 1.08 on screen.
 * @param bumpKeepsScale false reproduces the `.vp-pill` quirk: no `--fx`, so a
 *   bumping focused pill drops to scale 1 at the 45 % keyframe (PARITY §4.7).
 */
data class TvFocusTransform(
    val scale: Float = 1f,
    val lift: Dp = 0.dp,
    val durationMs: Int = TvMotion.T,
    val origin: TransformOrigin = TransformOrigin.Center,
    val bumpKeepsScale: Boolean = true,
) {
    companion object {
        /** No focus transform (text fields, rows of a list): `--fx: scale(1)`. */
        val None = TvFocusTransform()
        /** `.tv-btn.is-focused`: scale 1.06 over `--t-fast`. */
        val Button = TvFocusTransform(scale = 1.06f, durationMs = TvMotion.TFast)
        /** `.tv-card.is-focused`: scale 1.08 translateY(−6px), origin 50% 45%, over `--t`. */
        val Card = TvFocusTransform(scale = 1.08f, lift = (-3).dp, origin = TransformOrigin(0.5f, 0.45f))
        /** `.tv-ep.is-focused`: scale 1.08 translateY(−4px). */
        val Episode = TvFocusTransform(scale = 1.08f, lift = (-2).dp)
    }
}

/** What a text field gives the engine: real focus for the IME, its caret, and submit. */
internal class TextFieldHooks(
    val focusRequester: FocusRequester,
    val value: () -> TextFieldValue,
    val submit: () -> Unit,
)

/**
 * One focusable element: the attributes focus.rs reads off `[data-focusable]`,
 * and the state the engine writes back (`is-focused`, `data-bump`).
 *
 * Create with [rememberTvFocusTarget] and attach with [Modifier.tvFocusable].
 * The attributes are plain fields refreshed on every recomposition.
 */
@Stable
class TvFocusTarget internal constructor() {
    /** `data-focus-id`: names the element across re-renders, for route memory. */
    var focusId: String? = null
    /** `data-action`: what selectors match (`[data-action=play]`). */
    var action: String? = null
    /** `data-focus-group` on the element itself. A [TvRailTrack]'s group applies to everything in it. */
    var group: String? = null
    /** `data-autofocus`. */
    var autofocus: Boolean = false
    /** `data-longpress`: OK waits for keyup; held 700 ms it clicks with [TvIntent.LongPress]. */
    var longPress: Boolean = false
    /** `data-playable`: the Play key clicks it with [TvIntent.Play]. */
    var playable: Boolean = false
    /** `data-favable`: the red key clicks it with [TvIntent.Favorite]. */
    var favable: Boolean = false
    /** `is-selected`: entering its group the first time lands here. */
    var selected: Boolean = false
    /** `aria-disabled="true"` / `disabled`: never focusable. */
    var disabled: Boolean = false
    var onClick: (TvIntent) -> Unit = {}

    /** `is-focused`. Read it to style the element. */
    var isFocused: Boolean by mutableStateOf(false)
        private set

    /** `data-bump`: the direction of the last bump, cleared when focus moves away. */
    var bump: Dir? by mutableStateOf(null)
        internal set

    internal var textField: TextFieldHooks? = null
    internal var node: FocusableNode? = null
    internal var anchor: AnchorNode? = null
    internal var layer: FocusLayerNode? = null
    internal val bringIntoView = BringIntoViewRequester()

    /** In the composition and placed. */
    val isAttached: Boolean get() = node?.isAttached == true

    val isTextField: Boolean get() = textField != null

    internal fun setFocused(focused: Boolean) {
        if (isFocused == focused) return
        isFocused = focused
        layer?.onFocusChanged(focused)
    }

    // ── what a selector can ask (`closest(...)` equivalents) ──

    /** The names of every [Modifier.tvRegion] around this element, innermost first. */
    fun regions(): List<String> {
        val n = node ?: return emptyList()
        return n.ancestorsOf<RegionNode>().map { it.name }
    }

    fun inRegion(name: String): Boolean = regions().contains(name)

    /** The [TvRowState] of the row this element sits in, if any. */
    fun row(): TvRowState? = node?.closest<RowNode>()?.state

    /** The rail track this element sits in, if any. */
    fun track(): TvRailTrackState? = node?.closest<TrackNode>()?.state

    /** Inside a modal scope. */
    fun inScope(): Boolean = node?.closest<ScopeNode>() != null

    override fun toString() = "TvFocusTarget(${focusId ?: action ?: "?"})"
}

/**
 * Remember a [TvFocusTarget] and refresh its attributes. Pass the result to
 * [Modifier.tvFocusable] and read [TvFocusTarget.isFocused] to style.
 */
@Composable
fun rememberTvFocusTarget(
    focusId: String? = null,
    action: String? = null,
    group: String? = null,
    autofocus: Boolean = false,
    longPress: Boolean = false,
    playable: Boolean = false,
    favable: Boolean = false,
    selected: Boolean = false,
    disabled: Boolean = false,
    onClick: (TvIntent) -> Unit = {},
): TvFocusTarget {
    val t = remember { TvFocusTarget() }
    t.focusId = focusId
    t.action = action
    t.group = group
    t.autofocus = autofocus
    t.longPress = longPress
    t.playable = playable
    t.favable = favable
    t.selected = selected
    t.disabled = disabled
    t.onClick = onClick
    return t
}

/**
 * Make this element a focusable (`[data-focusable]`).
 *
 * Put it where the element's own transform belongs: everything after it in the
 * chain (background, content) is scaled and bumped with it, and the engine
 * measures the element *as drawn*, transforms included.
 */
fun Modifier.tvFocusable(target: TvFocusTarget, transform: TvFocusTransform = TvFocusTransform.None): Modifier =
    this
        .bringIntoViewRequester(target.bringIntoView)
        .then(AnchorElement(target))
        .then(FocusLayerElement(target, transform))
        .then(FocusableElement(target))

/** `[data-focus-scope]`: a modal. While one is placed, focus stays inside the last one. */
fun Modifier.tvFocusScope(): Modifier = this.then(ScopeElement)

/** `data-focus-group` on a container: its focusables remember their last one as a group. */
fun Modifier.tvFocusGroup(name: String): Modifier = this.then(GroupElement(name))

/** A named area, for selectors ([TvSelect.inRegion]) and [TvFocusEngine.focusedWithin]. */
fun Modifier.tvRegion(name: String): Modifier = this.then(RegionElement(name))

/** `[data-loading]`: the screen is still filling in. The passive ensure-valid pass then parks focus nowhere. */
fun Modifier.tvLoading(): Modifier = this.then(LoadingElement(skeleton = false))

/** `.tv-skeleton`: a placeholder card. Down at an edge waits (buffers) while any is placed. */
fun Modifier.tvSkeleton(): Modifier = this.then(LoadingElement(skeleton = true))

// ── rails and columns ────────────────────────────────────────────────────────

/** A rail track's translation and the shift the engine last gave it (`data-shift`). */
@Stable
class TvRailTrackState {
    /** The shift in dp (translationX is its negative). Animated 240 ms `--ease`, retargeting. */
    internal val offset = Animatable(0f)
    /** `data-shift`; null until the first reveal, which snaps. */
    var shift: Float? by mutableStateOf(null)
        internal set
    internal var viewportPx: Int = 0
    internal var node: TrackNode? = null

    /** The current translation in dp (negative = moved left). */
    val translationDp: Float get() = -offset.value
}

/** A row column's translation, plus `has-focus`. */
@Stable
class TvColumnState(internal val durationMs: Int = TvMotion.T) {
    internal val offset = Animatable(0f)
    /** `has-focus`: focus is inside this column. */
    var hasFocus: Boolean by mutableStateOf(false)
        internal set

    /** The current translationY in dp. */
    val translationDp: Float get() = offset.value
}

/** A row of a column: `is-current` (holds focus) and `is-past` (above the current row). */
@Stable
class TvRowState(val key: Any? = null) {
    var isCurrent: Boolean by mutableStateOf(false)
        internal set
    var isPast: Boolean by mutableStateOf(false)
        internal set
}

@Composable
fun rememberTvRailTrackState(): TvRailTrackState = remember { TvRailTrackState() }

@Composable
fun rememberTvColumnState(durationMs: Int = TvMotion.T): TvColumnState = remember { TvColumnState(durationMs) }

@Composable
fun rememberTvRowState(key: Any? = null): TvRowState = remember(key) { TvRowState(key) }

/**
 * `[data-row-column]`: a column of rows moved by translateY so the focused
 * row's top sits on [anchor] (Home and Search 0, Detail 360 dp). Apply to the
 * column that holds the rows; the translation is part of this modifier. The
 * column is measured at its full natural height (it overflows its parent, which
 * should clip), so every row is laid out and scoreable.
 */
fun Modifier.tvRowColumn(state: TvColumnState, anchor: Dp = 0.dp): Modifier =
    this
        // Its natural height, like a DOM column: rows below the fold must be
        // laid out at full size to be scored, revealed and focused.
        .wrapContentHeight(Alignment.Top, unbounded = true)
        .graphicsLayer { translationY = state.offset.value.dp.toPx() }
        .then(ColumnElement(state, anchor.value))

/** `[data-row]`: one row of a [tvRowColumn]. Read [TvRowState.isCurrent]/[TvRowState.isPast] for its opacity. */
fun Modifier.tvRow(state: TvRowState): Modifier = this.then(RowElement(state))

/**
 * `.tv-rail > .tv-track[data-rail-track]`: a horizontal track, full width,
 * moved by translateX so the focused element sits at [start] (96px = 48 dp),
 * clamped at the end. Not lazy: Tizen renders every card, and the engine needs
 * every one placed to score it.
 *
 * Defaults are `.tv-track`: padding 26/96/28 px (13/48/14 dp), gap 28 px.
 */
@Composable
fun TvRailTrack(
    group: String?,
    modifier: Modifier = Modifier,
    state: TvRailTrackState = rememberTvRailTrackState(),
    start: Dp = RevealMath.RAIL_START_DP.dp,
    contentPadding: PaddingValues = PaddingValues(start = TvSpace.SafeX, end = TvSpace.SafeX, top = 13.dp, bottom = 14.dp),
    gap: Dp = TvSpace.RailGap,
    content: @Composable RowScope.() -> Unit,
) {
    Layout(
        content = {
            Row(
                Modifier
                    .graphicsLayer { translationX = -state.offset.value.dp.toPx() }
                    .then(TrackElement(state, group, start.value))
                    .padding(contentPadding),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.Top,
                content = content,
            )
        },
        modifier = modifier,
    ) { measurables, constraints ->
        // width: max-content, min-width: 100% — the viewport is the rail's width.
        val viewport = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        state.viewportPx = viewport
        val track = measurables.first().measure(
            Constraints(minWidth = viewport, maxWidth = Constraints.Infinity, minHeight = 0, maxHeight = constraints.maxHeight),
        )
        layout(viewport, track.height) { track.place(0, 0) }
    }
}
