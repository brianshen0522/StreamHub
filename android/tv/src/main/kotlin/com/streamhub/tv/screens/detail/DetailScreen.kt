package com.streamhub.tv.screens.detail

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.streamhub.core.labels.Labels
import com.streamhub.core.model.SeasonRef
import com.streamhub.core.net.StreamHubApi
import com.streamhub.tv.LocalTvNav
import com.streamhub.tv.focus.LocalTvFocus
import com.streamhub.tv.focus.TvColumnState
import com.streamhub.tv.focus.TvFocusTransform
import com.streamhub.tv.focus.TvKey
import com.streamhub.tv.focus.TvRowState
import com.streamhub.tv.focus.TvScreenKeys
import com.streamhub.tv.focus.TvSelect
import com.streamhub.tv.focus.rememberTvColumnState
import com.streamhub.tv.focus.rememberTvFocusTarget
import com.streamhub.tv.focus.rememberTvRowState
import com.streamhub.tv.focus.tvFocusGroup
import com.streamhub.tv.focus.tvFocusable
import com.streamhub.tv.focus.tvRow
import com.streamhub.tv.focus.tvRowColumn
import com.streamhub.tv.focus.tvSkeleton
import com.streamhub.tv.nav.TvAutoplay
import com.streamhub.tv.nav.TvRoute
import com.streamhub.tv.screens.common.Load
import com.streamhub.tv.screens.common.ProviderNames
import com.streamhub.tv.screens.common.TvCatalog
import com.streamhub.tv.screens.common.TvHeart
import com.streamhub.tv.screens.common.TvIcon
import com.streamhub.tv.screens.common.TvIconPaths
import com.streamhub.tv.screens.common.clock
import com.streamhub.tv.screens.search.SearchSession
import com.streamhub.tv.ui.components.TvButton
import com.streamhub.tv.ui.components.TvButtonStyle
import com.streamhub.tv.ui.components.TvRail
import com.streamhub.tv.ui.components.TvWarnBadge
import com.streamhub.tv.ui.theme.TvColors
import com.streamhub.tv.ui.theme.TvGradients
import com.streamhub.tv.ui.theme.TvMotion
import com.streamhub.tv.ui.theme.TvShadowSpec
import com.streamhub.tv.ui.theme.TvShadows
import com.streamhub.tv.ui.theme.TvShapes
import com.streamhub.tv.ui.theme.TvSpace
import com.streamhub.tv.ui.theme.TvText
import com.streamhub.tv.ui.theme.TvType
import com.streamhub.tv.ui.theme.linearTween
import com.streamhub.tv.ui.theme.tvShadow
import kotlin.math.ceil

/**
 * Detail (`screens/detail.rs`, `styles.css:582-758`, PARITY §3.4): the
 * title, its chips and description over the poster backdrop; Play (focused
 * only once it knows what and where to resume), 從頭播放, the heart; then the
 * season pills and the episode tiles.
 *
 * [api] is used only for the provider display names (the shared helper).
 */
@Composable
fun DetailScreen(target: com.streamhub.tv.nav.DetailTarget, catalog: TvCatalog, api: StreamHubApi?) {
    val engine = LocalTvFocus.current
    val nav = LocalTvNav.current
    val scope = rememberCoroutineScope()
    val model = remember { DetailModel(target, catalog, scope) }
    val column = rememberTvColumnState()

    LaunchedEffect(Unit) {
        if (api != null) ProviderNames.ensure(api, scope)
        model.load()
    }

    fun play(ep: String?, fromStart: Boolean, replace: Boolean) {
        val t = model.playTarget(ep, fromStart) ?: return
        if (replace) nav.replace(TvRoute.Player(t)) else nav.push(TvRoute.Player(t))
    }

    // Focus Play the first time it can actually play, or wherever focus was
    // when this screen was left (coming back from the player). A Play key
    // pressed on a card elsewhere starts the player here instead.
    var settled by remember { mutableStateOf(false) }
    val ready = model.ready
    LaunchedEffect(ready) {
        if (!ready || settled) return@LaunchedEffect
        settled = true
        if (TvAutoplay.take(target.provider, target.url)) {
            play(model.episode, fromStart = false, replace = true)
        } else {
            engine.afterLayout { if (!engine.restore()) engine.focusFirst(TvSelect.action("play")) }
        }
    }

    // Play/PlayPause anywhere plays (resume rules); Back from deep in the rows
    // returns to 播放 first, then leaves (detail.rs:430-449).
    TvScreenKeys { press ->
        when (press.key) {
            TvKey.Play, TvKey.PlayPause -> if (model.ready) {
                play(model.episode, fromStart = false, replace = false)
                true
            } else {
                false
            }
            TvKey.Back -> !engine.focusedWithin(TvSelect.group("detail-actions")) && engine.backTo(TvSelect.action("play"))
            else -> false
        }
    }

    Box(Modifier.fillMaxSize()) {
        DetailBackdrop(model.poster.takeIf { it.isNotEmpty() }?.let { catalog.posterUrl(it) })
        Column(
            Modifier
                .fillMaxWidth()
                .tvRowColumn(column, anchor = 360.dp)
                .padding(top = 100.dp),
        ) {
            InfoRow(model, rememberTvRowState("info"), onPlay = ::play, onSearchElsewhere = {
                SearchSession.prefill(target.title)
                nav.push(TvRoute.Search)
            })
            if (model.seasons.size > 1) SeasonsRow(model, column)
            if (model.phase == DetailPhase.Ready && model.kind == DetailKind.Series) {
                EpisodesRow(model, column, onPlay = { ep ->
                    model.episode = ep
                    play(ep, fromStart = false, replace = false)
                })
            }
        }
    }
}

// ── info row ────────────────────────────────────────────────────────────────

private val ChipText = TvType.Body.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium)

/** 「MovieFFM · 影集 · 第 1 季 · 11 集」: display names and captions only (`detail.rs:457-476`). */
internal fun detailChips(model: DetailModel, providerName: String): List<String> = buildList {
    add(providerName)
    add(if (model.kind == DetailKind.Movie) "電影" else "影集")
    if (model.seasons.size > 1) {
        model.season?.takeIf { it.label.isNotEmpty() }?.let { add(Labels.seasonParts(it.label).main) }
    }
    if (model.kind == DetailKind.Series) {
        (model.episodes as? Load.Ready)?.value?.takeIf { it.isNotEmpty() }?.let { add("${it.size} 集") }
    }
}

/** The Play label (`detail.rs:478-495`). */
internal fun playLabel(model: DetailModel): String {
    if (!model.ready) {
        val eps = model.episodes
        return when {
            eps is Load.Failed -> "無法載入集數"
            eps is Load.Ready && eps.value.isEmpty() && model.kind == DetailKind.Series -> "沒有可播放的集數"
            else -> "載入中…"
        }
    }
    val ep = model.episode
    val pos = model.positionFor(ep)
    val epText = ep?.let { " " + Labels.episodeCaption(it) }.orEmpty()
    return if (pos > 0) "繼續播放$epText · ${clock(pos.toDouble())}" else "播放$epText"
}

@Composable
private fun InfoRow(
    model: DetailModel,
    row: TvRowState,
    onPlay: (String?, Boolean, Boolean) -> Unit,
    onSearchElsewhere: () -> Unit,
) {
    Column(
        Modifier
            .tvRow(row)
            .widthIn(max = 620.dp)
            .padding(start = TvSpace.SafeX, end = TvSpace.SafeX, bottom = 22.dp),
    ) {
        TvText(model.title, TvType.Display.copy(letterSpacing = (-0.25).sp), maxLines = 2)
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            detailChips(model, ProviderNames.name(model.target.provider)).forEachIndexed { i, part ->
                if (i > 0) TvText("·", ChipText, Modifier.padding(horizontal = 7.dp), color = TvColors.Text3)
                TvText(part, ChipText, color = TvColors.Text2)
            }
            model.badge?.let { TvWarnBadge(it, Modifier.padding(start = 9.dp)) }
        }
        model.description.takeIf { it.isNotEmpty() }?.let {
            TvText(
                it,
                TvType.Body.copy(fontSize = 13.sp),
                Modifier.padding(top = 9.dp).widthIn(max = 500.dp),
                color = TvColors.Text2,
                maxLines = 2,
            )
        }
        when (val phase = model.phase) {
            DetailPhase.Loading -> LoadingBar()
            DetailPhase.Gone -> Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TvText("已下架", TvType.Title, color = TvColors.Amber)
                TvText("這個來源已經不再提供這部片。到其他來源搜尋看看？", TvType.Body, color = TvColors.Text2)
                TvButton(
                    "搜尋「${model.target.title}」",
                    onClick = { onSearchElsewhere() },
                    style = TvButtonStyle.Primary,
                    autofocus = true,
                    action = "search-elsewhere",
                )
            }
            is DetailPhase.Failed -> Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ErrorLine(phase.message)
                TvButton("重試", onClick = { model.load() }, style = TvButtonStyle.Primary, autofocus = true, action = "retry")
            }
            DetailPhase.Ready -> {
                Actions(model, onPlay)
                ErrorLine(model.favError.orEmpty(), Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** `.tv-error`: 12 sp `--danger`, at least one 1.3em line even when empty. */
@Composable
private fun ErrorLine(text: String, modifier: Modifier = Modifier) {
    // An empty <p> has no line box: only its min-height.
    Box(modifier.heightIn(min = 15.6.dp)) { if (text.isNotEmpty()) TvText(text, TvType.Error, maxLines = 3) }
}

@Composable
private fun Actions(model: DetailModel, onPlay: (String?, Boolean, Boolean) -> Unit) {
    val ready = model.ready
    Row(
        Modifier.padding(top = 18.dp).tvFocusGroup("detail-actions"),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        // A flex row: buttons with their own height sit at the top, not centred on the taller Play.
        verticalAlignment = Alignment.Top,
    ) {
        TvButton(
            playLabel(model),
            onClick = { if (model.ready) onPlay(model.episode, false, false) },
            modifier = Modifier.widthIn(min = 140.dp),
            style = TvButtonStyle.Primary,
            action = "play",
            focusId = "detail-play",
            disabled = !ready,
            icon = { c -> TvIcon(TvIconPaths.Play, c, filled = true) },
            height = 40.dp,
            paddingX = 22.dp,
            textStyle = TvType.Button.copy(fontSize = 15.sp, fontFeatureSettings = TvType.TabularNums),
        )
        if (ready && model.positionFor(model.episode) > 0) {
            TvButton(
                "從頭播放",
                onClick = { onPlay(model.episode, true, false) },
                action = "restart",
                focusId = "detail-restart",
                icon = { c -> TvIcon(TvIconPaths.Restart, c) },
            )
        }
        val fav = model.isFavorite
        TvButton(
            if (fav) "已收藏" else "加入收藏",
            onClick = { model.toggleFavorite() },
            action = "favorite",
            focusId = "detail-favorite",
            icon = { c -> TvHeart(fav, c) },
        )
    }
}

/** `.tv-detail-loading`: a 180 × 2 dp track with a block sliding −100 % → +250 %, 1.1 s `--ease-in-out`. */
@Composable
private fun LoadingBar() {
    val t = rememberInfiniteTransition(label = "detail-loading")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = TvMotion.EaseInOut)), label = "bar")
    Box(
        Modifier
            .padding(top = 24.dp)
            .size(180.dp, 2.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(Color.White.copy(alpha = 0.12f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(72.dp)
                .graphicsLayer { translationX = (-1f + 3.5f * p) * size.width }
                .background(TvColors.Text2),
        )
    }
}

// ── rows ──────────────────────────────────────────────────────────────────

/** Detail rows: 1 at rest; while the column has focus, the rows not holding it are .72. */
private fun detailRowAlpha(column: TvColumnState): (TvRowState) -> Float = { r ->
    if (column.hasFocus && !r.isCurrent) 0.72f else 1f
}

private val CompactTitle = TvType.RowHeading.copy(fontSize = 14.sp)
private val CompactTrack = PaddingValues(start = TvSpace.SafeX, end = TvSpace.SafeX, top = 10.dp, bottom = 12.dp)

@Composable
private fun SeasonsRow(model: DetailModel, column: TvColumnState) {
    TvRail(
        title = "季",
        group = "detail-seasons",
        row = rememberTvRowState("seasons"),
        alpha = detailRowAlpha(column),
        rowPaddingBottom = 4.dp,
        trackPadding = CompactTrack,
        gap = 6.dp,
        titleStyle = CompactTitle,
    ) {
        val current = model.season?.url
        for (s in model.seasons) {
            key(s.url) { SeasonPill(s, selected = s.url == current, onClick = { model.pickSeason(s) }) }
        }
    }
}

private val SegBg = Color(31, 34, 42).copy(alpha = 0.9f)
private val SegSelectedBg = Color(229, 56, 59).copy(alpha = 0.22f)
private val OnFocusMuted = Color(11, 12, 15).copy(alpha = 0.6f)

/** `.tv-btn.tv-btn-seg` (`styles.css:652-674`): 「第 N 季」 over the site's own text. */
@Composable
private fun SeasonPill(season: SeasonRef, selected: Boolean, onClick: () -> Unit) {
    val (main, rest) = if (season.label.isEmpty()) "季" to "" else Labels.seasonParts(season.label).let { it.main to it.rest }
    val t = rememberTvFocusTarget(focusId = "season:${season.url}", selected = selected, onClick = { onClick() })
    val focused = t.isFocused
    val shadow by animateFloatAsState(if (focused) 1f else 0f, TvMotion.tween(TvMotion.T), label = "seg-shadow")
    Column(
        Modifier
            .zIndex(if (focused) 2f else 0f)
            .tvFocusable(t, TvFocusTransform.Button)
            .tvShadow(TvShadows.Button, 999.dp) { shadow }
            .height(36.dp)
            .clip(TvShapes.Pill)
            .background(
                when {
                    focused -> TvColors.Focus
                    selected -> SegSelectedBg
                    else -> SegBg
                },
            )
            .then(if (selected && !focused) Modifier.border(1.dp, TvColors.Accent, TvShapes.Pill) else Modifier)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        TvText(
            main,
            TvType.Body.copy(fontSize = 13.sp, lineHeight = 1.2.em, fontWeight = FontWeight.Bold),
            color = if (focused) TvColors.OnFocus else TvColors.Text,
        )
        if (rest.isNotEmpty()) {
            TvText(
                rest,
                TvType.Body.copy(fontSize = 10.sp, lineHeight = 1.2.em, fontWeight = FontWeight.Medium),
                Modifier.widthIn(max = 110.dp),
                color = if (focused) OnFocusMuted else TvColors.Text3,
            )
        }
    }
}

@Composable
private fun EpisodesRow(model: DetailModel, column: TvColumnState, onPlay: (String) -> Unit) {
    val eps = model.episodes
    TvRail(
        title = "集數",
        group = "detail-episodes",
        row = rememberTvRowState("episodes"),
        note = (eps as? Load.Ready)?.value?.size?.toString(),
        alpha = detailRowAlpha(column),
        rowPaddingBottom = 4.dp,
        trackPadding = CompactTrack,
        titleStyle = CompactTitle,
    ) {
        when (eps) {
            Load.Loading -> repeat(7) { EpisodeSkeleton() }
            is Load.Failed -> TvText(eps.message, TvType.RowCaption, color = TvColors.Danger)
            is Load.Ready -> for (ep in eps.value) {
                key(ep) { EpisodeTile(model, ep, selected = ep == model.episode, onClick = { onPlay(ep) }) }
            }
        }
    }
}

private val TileBg = Color(31, 34, 42).copy(alpha = 0.92f)
private val TileShadow = TvShadowSpec(8.dp, 18.dp, Color.Black.copy(alpha = 0.6f))
private val TileProgressTrack = Color.White.copy(alpha = 0.16f)

/** What an episode tile says under its label (`detail.rs:684-702`), or null for an unwatched one. */
internal fun episodeStatus(model: DetailModel, ep: String): Pair<Boolean, String?> {
    val p = model.progressFor(ep)
    val done = p?.isCompleted == true
    val partial = p?.takeIf { !it.isCompleted && it.progressPercent > 0 }
    val caption = when {
        done -> "已看完"
        partial != null && partial.durationSeconds > 0 -> {
            val left = (partial.durationSeconds - partial.positionSeconds).coerceAtLeast(0)
            "剩 ${maxOf(1, ceil(left / 60.0).toInt())} 分鐘"
        }
        partial != null -> "看到 ${clock(partial.positionSeconds.toDouble())}"
        else -> null
    }
    return done to caption
}

/** `.tv-ep` (`styles.css:676-754`): 100 × 52 dp, label bottom-left, status, progress, selected bar. */
@Composable
private fun EpisodeTile(model: DetailModel, ep: String, selected: Boolean, onClick: () -> Unit) {
    val t = rememberTvFocusTarget(focusId = "ep:$ep", selected = selected, onClick = { onClick() })
    val focused = t.isFocused
    val shadow by animateFloatAsState(if (focused) 1f else 0f, TvMotion.focus(), label = "ep-shadow")
    val (done, caption) = episodeStatus(model, ep)
    val pct = model.progressFor(ep)?.takeIf { !it.isCompleted && it.progressPercent > 0 }?.progressPercent ?: 0.0
    Box(
        Modifier
            .zIndex(if (focused) 2f else 0f)
            .tvFocusable(t, TvFocusTransform.Episode)
            .tvShadow(TileShadow, TvShapes.RCard) { shadow }
            .size(100.dp, 52.dp)
            .clip(TvShapes.Card)
            .background(if (focused) TvColors.Focus else TileBg),
    ) {
        Column(
            Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = 7.dp, bottom = 9.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            TvText(
                Labels.episodeCaption(ep),
                TvType.Body.copy(fontSize = 14.sp, lineHeight = 1.15.em, fontWeight = FontWeight.ExtraBold, fontFeatureSettings = TvType.TabularNums),
                color = when {
                    focused -> TvColors.OnFocus
                    done -> TvColors.Text2
                    else -> TvColors.Text
                },
            )
            if (caption != null) {
                Row(
                    Modifier.padding(top = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (done) {
                        TvIcon(TvIconPaths.Check, if (focused) TvColors.EpisodeCheckFocused else TvColors.Green, size = 11.dp, strokeWidth = 3f)
                    }
                    TvText(
                        caption,
                        TvType.Body.copy(fontSize = 10.sp, lineHeight = 1.3.em, fontFeatureSettings = TvType.TabularNums),
                        color = if (focused) OnFocusMuted else TvColors.Text3,
                    )
                }
            }
        }
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(vertical = 10.dp)
                    .width(2.5.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topEnd = 1.5.dp, bottomEnd = 1.5.dp))
                    .background(TvColors.Accent),
            )
        }
        if (pct > 0) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.5.dp).background(TileProgressTrack)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = (pct / 100.0).coerceIn(0.03, 1.0).toFloat()
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                        }
                        .background(TvColors.Accent),
                )
            }
        }
    }
}

/** `.tv-ep.tv-skeleton`: a tile in `--surface` with the shimmer. */
@Composable
private fun EpisodeSkeleton() {
    val shimmer = rememberInfiniteTransition(label = "ep-shimmer")
    val x by shimmer.animateFloat(-1f, 1f, infiniteRepeatable(linearTween(TvMotion.ShimmerMs)), label = "x")
    Box(
        Modifier
            .tvSkeleton()
            .size(100.dp, 52.dp)
            .clip(TvShapes.Card)
            .background(TvColors.Surface)
            .drawWithContent {
                drawContent()
                translate(left = x * size.width) { drawRect(TvGradients.Shimmer, alpha = 0.5f) }
            },
    )
}

// ── backdrop ────────────────────────────────────────────────────────────────

/**
 * `.tv-backdrop.tv-backdrop-detail` (`styles.css:231-277`): one layer, always
 * shown — a full-canvas wash at .16 and the poster right-aligned at 360 × 540
 * at .7 with its left edge masked — under the detail scrim.
 */
@Composable
private fun DetailBackdrop(src: String?) {
    Box(Modifier.fillMaxSize().background(TvColors.Bg)) {
        if (src != null) {
            AsyncImage(
                model = src,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.16f },
            )
            AsyncImage(
                model = src,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(360.dp, 540.dp)
                    .graphicsLayer {
                        alpha = 0.7f
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(TvGradients.BackdropMask, blendMode = BlendMode.DstIn)
                    },
            )
        }
        // CSS paints the first background on top: bottom→top under left→right.
        Box(Modifier.fillMaxSize().background(TvGradients.DetailScrimBottom))
        Box(Modifier.fillMaxSize().background(TvGradients.DetailScrimLeft))
    }
}
