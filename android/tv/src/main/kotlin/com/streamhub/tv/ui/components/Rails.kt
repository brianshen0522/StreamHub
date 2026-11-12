package com.streamhub.tv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.streamhub.tv.focus.TvFocusTarget
import com.streamhub.tv.focus.TvFocusTransform
import com.streamhub.tv.focus.TvIntent
import com.streamhub.tv.focus.TvRailTrack
import com.streamhub.tv.focus.TvRailTrackState
import com.streamhub.tv.focus.TvRowState
import com.streamhub.tv.focus.rememberTvFocusTarget
import com.streamhub.tv.focus.rememberTvRailTrackState
import com.streamhub.tv.focus.tvFocusable
import com.streamhub.tv.focus.tvRow
import com.streamhub.tv.focus.tvSkeleton
import com.streamhub.tv.ui.theme.TvColors
import com.streamhub.tv.ui.theme.TvGradients
import com.streamhub.tv.ui.theme.TvMotion
import com.streamhub.tv.ui.theme.TvShadows
import com.streamhub.tv.ui.theme.TvShapes
import com.streamhub.tv.ui.theme.TvSpace
import com.streamhub.tv.ui.theme.TvText
import com.streamhub.tv.ui.theme.TvType
import com.streamhub.tv.ui.theme.tvShadow
import kotlin.math.tan

/** What a [TvPosterCard] shows (`screens/common.rs` `CardData`). */
data class PosterCardData(
    /** `data-focus-id`. Stable across reloads (route memory restores by it). */
    val id: String,
    val title: String,
    val subtitle: String = "",
    /** Already proxied (`api.posterUrl`), or null for the fallback initial. */
    val posterUrl: String? = null,
    /** Watch progress 0–100; the bar shows only when > 0. */
    val progress: Double = 0.0,
    /** `isUnavailable`: the 已下架 / 已無集數 badge, the image at .4. */
    val unavailable: Boolean = false,
    /** `unavailableReason`; "no-episodes" reads 已無集數. */
    val unavailableReason: String? = null,
    /** The 下一集 chip (next-up continue rows). */
    val nextUp: Boolean = false,
)

/**
 * The poster card (PARITY §3.0 "Poster card"): 100×150 dp art with a fallback
 * initial, the badge/chip/progress, a pre-drawn shadow and an inner white ring
 * that fade in on focus, a single sheen sweep on each focus, and scale 1.08
 * with a 3 dp lift (origin 50% 45%) over 240 ms.
 *
 * The click dispatches on the engine's intent like `common.rs:128-137`: Play →
 * [onPlay] (else open), LongPress → [onLongPress] (else open), Favorite →
 * [onFavorite], Open → [onOpen]. Passing a callback sets the matching
 * `data-playable` / `data-longpress` / `data-favable`.
 */
@Composable
fun TvPosterCard(
    card: PosterCardData,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onFavorite: (() -> Unit)? = null,
    autofocus: Boolean = false,
    target: TvFocusTarget? = null,
) {
    val own = rememberTvFocusTarget(
        focusId = card.id,
        autofocus = autofocus,
        longPress = onLongPress != null,
        playable = onPlay != null,
        favable = onFavorite != null,
        onClick = { intent ->
            when (intent) {
                TvIntent.Play -> (onPlay ?: onOpen)()
                TvIntent.LongPress -> (onLongPress ?: onOpen)()
                TvIntent.Favorite -> onFavorite?.invoke()
                TvIntent.Open -> onOpen()
            }
        },
    )
    val t = target ?: own
    val focused = t.isFocused
    val glow by animateFloatAsState(if (focused) 1f else 0f, TvMotion.focus(), label = "card-glow")
    val sheen = remember { Animatable(0f) }
    LaunchedEffect(focused) {
        if (focused) {
            sheen.snapTo(0f)
            sheen.animateTo(1f, tween(TvMotion.SheenMs, TvMotion.SheenDelayMs, TvMotion.Ease))
        } else {
            sheen.snapTo(0f)
        }
    }

    Column(
        modifier
            .zIndex(if (focused) 2f else 0f)
            .width(100.dp)
            .tvFocusable(t, TvFocusTransform.Card),
    ) {
        Box(Modifier.size(100.dp, 150.dp)) {
            // The drop shadow, pre-rendered behind the artwork (`.tv-card::before`).
            Box(
                Modifier
                    .offset(x = 4.dp, y = 12.dp)
                    .size(92.dp, 142.dp)
                    .tvShadow(TvShadows.Card, TvShapes.RCard) { glow },
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(TvShapes.Card)
                    .background(TvColors.Surface)
                    .drawWithContent {
                        drawContent()
                        // z3: the sheen.
                        val p = sheen.value
                        if (focused && p > 0f && p < 1f) drawSheen(TvMotion.sheenX(p))
                        // z4: the inner 3px ring.
                        if (glow > 0f) {
                            val w = 1.5.dp.toPx()
                            val r = TvShapes.RCard.toPx()
                            drawRoundRect(
                                color = TvColors.Focus.copy(alpha = glow),
                                topLeft = Offset(w / 2, w / 2),
                                size = Size(size.width - w, size.height - w),
                                cornerRadius = CornerRadius(r - w / 2, r - w / 2),
                                style = Stroke(w),
                            )
                        }
                    },
            ) {
                Box(Modifier.fillMaxSize().background(TvGradients.CardFallback), contentAlignment = Alignment.Center) {
                    TvText(card.title.take(1), TvType.Body.copy(fontSize = 42.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold), color = TvColors.CardFallbackGlyph)
                }
                if (!card.posterUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = card.posterUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().alpha(if (card.unavailable) 0.4f else 1f),
                    )
                }
                if (card.unavailable) {
                    TvWarnBadge(
                        if (card.unavailableReason == "no-episodes") "已無集數" else "已下架",
                        Modifier.align(Alignment.TopStart).padding(5.dp),
                    )
                }
                if (card.nextUp) {
                    TvCardChip("下一集", Modifier.align(Alignment.TopEnd).padding(5.dp))
                }
                if (card.progress > 0) {
                    TvProgressBar(
                        fraction = (card.progress.coerceIn(2.0, 100.0) / 100.0).toFloat(),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
                    )
                }
            }
        }
        TvText(
            card.title,
            TvType.CardTitle,
            Modifier.padding(top = 7.dp),
            color = if (focused) TvColors.Text else TvColors.Text2,
        )
        Box(Modifier.heightIn(min = 14.dp)) {
            TvText(card.subtitle, TvType.CardSub, color = if (focused) TvColors.Text2 else TvColors.Text3)
        }
    }
}

/** The sheen strip: 60 % × 120 % of the art, skewed −14°, at translateX [x] × its own width. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSheen(x: Float) {
    val w = size.width * 0.6f
    val h = size.height * 1.2f
    val top = -size.height * 0.1f
    val skew = Matrix().apply { values[Matrix.SkewX] = tan(Math.toRadians(-14.0)).toFloat() }
    translate(left = x * w, top = top) {
        withTransform({
            translate(w / 2f, h / 2f)
            transform(skew)
            translate(-w / 2f, -h / 2f)
        }) {
            drawRect(TvGradients.Sheen, size = Size(w, h))
        }
    }
}

/** `.tv-badge.tv-badge-warn`: amber pill, 10 sp w700. */
@Composable
fun TvWarnBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(17.dp)
            .clip(TvShapes.Pill)
            .background(TvColors.Amber)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        TvText(text, TvType.Badge, color = TvColors.OnAmber)
    }
}

/** `.tv-chip`: dark pill, green 10 sp w700. */
@Composable
fun TvCardChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(16.dp)
            .clip(TvShapes.Pill)
            .background(TvColors.ChipBg)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        TvText(text, TvType.Badge, color = TvColors.Green)
    }
}

/** `.tv-progress`: 3 dp track rgba(255,255,255,.28) with an accent fill of [fraction]. */
@Composable
fun TvProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(1.5.dp))
            .background(TvColors.ProgressTrack),
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(TvColors.Accent))
    }
}

/** A loading placeholder card (`.tv-card.tv-skeleton`): never focusable, makes the screen "loading". */
@Composable
fun TvSkeletonCard(modifier: Modifier = Modifier) {
    val shimmer = rememberInfiniteTransition(label = "shimmer")
    val x by shimmer.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(TvMotion.ShimmerMs, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer-x",
    )
    Column(modifier.width(100.dp).tvSkeleton()) {
        Box(
            Modifier
                .size(100.dp, 150.dp)
                .clip(TvShapes.Card)
                .background(TvColors.Surface)
                .drawWithContent {
                    drawContent()
                    translate(left = x * size.width) { drawRect(TvGradients.Shimmer, alpha = 0.5f) }
                },
        )
        Box(Modifier.padding(top = 8.dp).fillMaxWidth(0.78f).height(10.dp).clip(RoundedCornerShape(3.dp)).background(TvColors.Surface))
        Box(Modifier.padding(top = 5.dp).fillMaxWidth(0.46f).height(10.dp).clip(RoundedCornerShape(3.dp)).background(TvColors.Surface))
    }
}

/** The opacity rule for a row: Home/Search hide past rows (`.tv-home .tv-row.is-past`). */
fun homeRowAlpha(row: TvRowState): Float = when {
    row.isCurrent -> 1f
    row.isPast -> 0f
    else -> 0.72f
}

/**
 * A rail row (`Rail`, `common.rs:175-199`): heading (title, count note,
 * caption) over a [TvRailTrack]. A [caption] makes it a collapsed row with no
 * track (a provider error, "沒有結果"). [alpha] maps the row's is-current /
 * is-past state to its opacity, animated 240 ms.
 *
 * Paddings default to the generic `.tv-row` / `.tv-track`; Home passes its
 * overrides (row bottom 3 dp, track 11/9 dp).
 */
@Composable
fun TvRail(
    title: String,
    group: String,
    row: TvRowState,
    modifier: Modifier = Modifier,
    note: String? = null,
    caption: String? = null,
    captionIsError: Boolean = false,
    alpha: (TvRowState) -> Float = ::homeRowAlpha,
    rowPaddingBottom: Dp = TvSpace.RowPaddingBottom,
    trackPadding: PaddingValues = PaddingValues(start = TvSpace.SafeX, end = TvSpace.SafeX, top = 13.dp, bottom = 14.dp),
    trackState: TvRailTrackState = rememberTvRailTrackState(),
    content: @Composable RowScope.() -> Unit,
) {
    val a by animateFloatAsState(alpha(row), TvMotion.focus(), label = "row-alpha")
    val collapsed = caption != null
    Column(
        modifier
            .fillMaxWidth()
            .tvRow(row)
            .graphicsLayer { this.alpha = a }
            .padding(bottom = rowPaddingBottom),
    ) {
        Row(
            Modifier.padding(horizontal = TvSpace.SafeX),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            TvText(
                title,
                if (collapsed) TvType.RowHeading.copy(fontSize = 14.sp, color = TvColors.Text2) else TvType.RowHeading,
                Modifier.alignByBaseline(),
            )
            if (note != null) TvText(note, TvType.RowNote, Modifier.alignByBaseline())
            if (caption != null) {
                TvText(caption, TvType.RowCaption, Modifier.alignByBaseline(), color = if (captionIsError) TvColors.Danger else TvColors.Text3)
            }
        }
        if (!collapsed) {
            TvRailTrack(group = group, state = trackState, contentPadding = trackPadding, content = content)
        } else {
            Spacer(Modifier.height(0.dp))
        }
    }
}
