package com.streamhub.tv.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.streamhub.core.labels.Labels
import com.streamhub.tv.focus.TvFocusTransform
import com.streamhub.tv.focus.rememberTvFocusTarget
import com.streamhub.tv.focus.tvFocusGroup
import com.streamhub.tv.focus.tvFocusable
import com.streamhub.tv.nav.PlayTarget
import com.streamhub.tv.ui.theme.TvColors
import com.streamhub.tv.ui.theme.TvMotion
import com.streamhub.tv.ui.theme.TvShadowSpec
import com.streamhub.tv.ui.theme.TvShapes
import com.streamhub.tv.ui.theme.TvText
import com.streamhub.tv.ui.theme.TvType
import com.streamhub.tv.ui.theme.cssLinearGradient
import com.streamhub.tv.ui.theme.tvShadow

/*
 * The player's on-screen chrome: `PlayerScreen`'s view in `player/mod.rs`
 * (1638-1891) styled after `player.css`, at CSS px / 2 = dp. Only transform
 * and alpha animate. Nothing here owns state: it draws a [PlayerUi] and
 * reports presses through [PlayerActions].
 */

/** What the OSD's focusable elements do when clicked. */
interface PlayerActions {
    fun toggle()
    fun back10()
    fun fwd10()
    fun nextEpisode()
    fun sources()
    fun selectSource(index: Int)
    fun retry()
    fun back()

    object None : PlayerActions {
        override fun toggle() = Unit
        override fun back10() = Unit
        override fun fwd10() = Unit
        override fun nextEpisode() = Unit
        override fun sources() = Unit
        override fun selectSource(index: Int) = Unit
        override fun retry() = Unit
        override fun back() = Unit
    }
}

/** The SVG paths the Tizen player draws (24-unit viewBox). */
object PlayerIcons {
    const val PLAY = "M8 5v14l11-7z"
    const val PAUSE = "M7 5h3.5v14H7zM13.5 5H17v14h-3.5z"
    const val BACK10 = "M11 6V2L5 7l6 5V8a6 6 0 1 1-6 6H3a8 8 0 1 0 8-8z"
    const val FWD10 = "M13 6V2l6 5-6 5V8a6 6 0 1 0 6 6h2a8 8 0 1 1-8-8z"
    const val NEXT = "M6 5v14l9-7zM16 5h2.5v14H16z"
    const val SOURCES = "M4 6h16v2H4zm0 5h16v2H4zm0 5h10v2H4z"

    private val cache = HashMap<String, ImageVector>()

    fun vector(path: String): ImageVector = cache.getOrPut(path) {
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(PathParser().parsePathString(path).toNodes(), fill = androidx.compose.ui.graphics.SolidColor(Color.White))
            .build()
    }
}

@Composable
private fun VpIcon(path: String, size: Dp, color: Color) {
    Image(
        rememberVectorPainter(PlayerIcons.vector(path)),
        contentDescription = null,
        modifier = Modifier.size(size),
        colorFilter = ColorFilter.tint(color),
    )
}

private val OnVp = TvColors.Bg
private val Tnum = TvType.TabularNums

/** One-shot 0 → 1 over [ms] `--ease`, restarted when [key] changes (a CSS animation on insertion). */
@Composable
private fun appear(ms: Int, key: Any? = Unit): () -> Float {
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { a.animateTo(1f, TvMotion.tween(ms)) }
    return { a.value }
}

/** `vp-pulse`: opacity 1 → .3, alternate, infinite. */
@Composable
private fun pulse(periodMs: Int): () -> Float {
    val t = rememberInfiniteTransition(label = "pulse")
    val v by t.animateFloat(1f, 0.3f, infiniteRepeatable(tween(periodMs, easing = TvMotion.EaseInOut), RepeatMode.Reverse), label = "pulse")
    return { v }
}

/**
 * The whole overlay. [title] and [episodeCaption] are fixed for the screen's
 * life, as in Tizen; everything else is read from [ui].
 */
@Composable
fun PlayerOsd(ui: PlayerUi, target: PlayTarget, actions: PlayerActions, modifier: Modifier = Modifier) {
    val episode = Labels.episodeCaption(target.episodeLabel)
    val currentEp = target.episodes.indexOf(target.episodeLabel).takeIf { it >= 0 }
    Box(modifier.fillMaxSize()) {
        ui.driver?.let { DriverBadge(it) }
        ui.flash?.let { f -> key(f.nonce) { FlashGlyph(f.playing) } }
        Chrome(ui, target.title, episode, actions)
        ui.strip?.let { idx -> EpisodeStrip(ui, idx, target.episodes, currentEp) }
        ui.upNext?.let { UpNextCardView(it) }
        if (ui.picker) SourcePicker(ui, actions)
        if (ui.fatal != null) ErrorPanel(actions)
    }
}

// ── driver badge ───────────────────────────────────────────────────────────

@Composable
private fun BoxScope.DriverBadge(name: String) {
    val fade = appear(TvMotion.T)
    val dot = pulse(1400)
    Row(
        Modifier
            .align(Alignment.TopEnd)
            .padding(top = 32.dp, end = 48.dp)
            .graphicsLayer { alpha = fade() }
            .widthIn(max = 360.dp)
            .clip(TvShapes.Pill)
            .background(Color(21, 23, 28).copy(alpha = 0.86f))
            .padding(start = 10.dp, end = 13.dp, top = 5.dp, bottom = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).graphicsLayer { alpha = dot() }.clip(CircleShape).background(TvColors.Accent))
        TvText(driverCaption(name), TvType.Meta, color = TvColors.Text)
    }
}

// ── flash ──────────────────────────────────────────────────────────────────

@Composable
private fun BoxScope.FlashGlyph(playing: Boolean) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(600, easing = LinearEasing)) }
    Box(
        Modifier
            .align(Alignment.TopStart)
            .offset(x = 480.dp - 38.dp, y = (540 * 0.42f).dp - 38.dp)
            .size(76.dp)
            .graphicsLayer {
                // 0% α0 s.8 → 25% α1 s1 → 70% α1 s1 → 100% α0 s1.04, --ease per interval.
                alpha = TvMotion.keyframes(p.value, TvMotion.Ease, 0f to 0f, 0.25f to 1f, 0.7f to 1f, 1f to 0f)
                val s = TvMotion.keyframes(p.value, TvMotion.Ease, 0f to 0.8f, 0.25f to 1f, 0.7f to 1f, 1f to 1.04f)
                scaleX = s
                scaleY = s
            }
            .clip(CircleShape)
            .background(TvColors.bg(0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        VpIcon(if (playing) PlayerIcons.PLAY else PlayerIcons.PAUSE, 36.dp, TvColors.Text)
    }
}

// ── chrome ─────────────────────────────────────────────────────────────────

private val ChromeScrim = Brush.verticalGradient(
    0f to TvColors.bg(0f),
    0.38f to TvColors.bg(0.38f),
    0.70f to TvColors.bg(0.78f),
    1f to TvColors.bg(0.94f),
)

@Composable
private fun BoxScope.Chrome(ui: PlayerUi, title: String, episode: String, actions: PlayerActions) {
    val shown by animateFloatAsState(if (ui.shown) 1f else 0f, TvMotion.focus(), label = "chrome")
    val kicker = when {
        episode.isNotEmpty() && ui.sourceLabel.isNotEmpty() -> "$episode · ${ui.sourceLabel}"
        episode.isNotEmpty() -> episode
        else -> ui.sourceLabel
    }
    Column(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .graphicsLayer {
                alpha = shown
                translationY = (1f - shown) * 10.dp.toPx()
            }
            .background(ChromeScrim)
            .padding(start = 48.dp, end = 48.dp, top = 132.dp, bottom = 32.dp),
    ) {
        // Head: room under the title is reserved for the seek pill.
        Column(Modifier.widthIn(max = 650.dp).padding(bottom = 32.dp)) {
            if (ui.busy) StatusPill(ui.notice ?: "載入中…")
            TvText(
                kicker,
                TvType.Meta.copy(color = TvColors.Text2, letterSpacing = 0.02.em),
                Modifier.padding(bottom = 3.dp),
            )
            TvText(title, TvType.Title)
        }
        Scrubber(ui)
        Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val timeStyle = TvType.Meta.copy(fontFeatureSettings = Tnum)
            TvText(PlayerRules.formatTime(ui.posS), timeStyle, color = TvColors.Text)
            val d = ui.durS
            TvText(
                if (d == 0L) "--:--" else "−" + PlayerRules.formatTime((d - ui.posS).coerceAtLeast(0)),
                timeStyle,
                color = TvColors.Text2,
            )
        }
        if (ui.controls) ControlPills(ui, actions)
    }
}

@Composable
private fun StatusPill(text: String) {
    val fade = appear(TvMotion.T)
    val dot = pulse(1100)
    Row(
        Modifier
            .padding(bottom = 9.dp)
            .graphicsLayer { alpha = fade() }
            .clip(TvShapes.Pill)
            .background(Color.White.copy(alpha = 0.1f))
            .padding(start = 8.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).graphicsLayer { alpha = dot() }.clip(CircleShape).background(TvColors.Text))
        TvText(text, TvType.Meta, color = TvColors.Text)
    }
}

private fun frac(num: Long, den: Long): Float =
    if (den <= 0L) 0f else (num.coerceAtMost(den).toFloat() / den).coerceIn(0f, 1f)

private val BusyGradient = Brush.horizontalGradient(
    0f to Color.White.copy(alpha = 0f),
    0.5f to Color.White.copy(alpha = 0.9f),
    1f to Color.White.copy(alpha = 0f),
)

@Composable
private fun Scrubber(ui: PlayerUi) {
    val seeking = ui.seeking
    val open by animateFloatAsState(if (seeking) 1f else 0f, TvMotion.focus(), label = "seeking")
    val fill by animateFloatAsState(frac(ui.posS, ui.durS), tween(TvMotion.T, easing = LinearEasing), label = "fill")
    val rail by animateFloatAsState(frac(ui.shownPos, ui.durS), TvMotion.focus(), label = "rail")
    val target = frac(ui.shownPos, ui.durS)
    Box(Modifier.fillMaxWidth().height(17.dp)) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .height(5.dp)
                .graphicsLayer { scaleY = 0.6f + 0.4f * open }
                .clip(RoundedCornerShape(2.5.dp))
                .background(Color.White.copy(alpha = 0.22f)),
        ) {
            if (seeking) {
                Box(
                    Modifier.fillMaxSize()
                        .graphicsLayer { scaleX = target; transformOrigin = TransformOrigin(0f, 0.5f) }
                        .background(Color.White.copy(alpha = 0.5f)),
                )
            }
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { scaleX = fill; transformOrigin = TransformOrigin(0f, 0.5f) }
                    .background(TvColors.Accent),
            )
            if (ui.buffering && ui.fatal == null) BusyLine()
        }
        // The rail: full width, moved by translateX(frac × its width).
        Box(
            Modifier
                .fillMaxWidth()
                .height(17.dp)
                .graphicsLayer { translationX = rail * size.width },
        ) {
            Box(
                Modifier
                    .offset(x = (-5.5).dp, y = 3.dp)
                    .size(11.dp)
                    .graphicsLayer {
                        val s = 0.64f + 0.36f * open
                        scaleX = s
                        scaleY = s
                        alpha = if (ui.durS == 0L) 0f else 1f
                    }
                    .clip(CircleShape)
                    .background(TvColors.Focus),
            )
            ui.seekPreview?.let { SeekPill(PlayerRules.formatTime(it)) }
        }
    }
}

@Composable
private fun BusyLine() {
    val t = rememberInfiniteTransition(label = "busy")
    val x by t.animateFloat(-1f, 3.85f, infiniteRepeatable(tween(1250, easing = TvMotion.EaseInOut)), label = "busy")
    Box(Modifier.fillMaxSize().layout { m, c ->
        val w = (c.maxWidth * 0.26f).toInt()
        val p = m.measure(Constraints.fixed(w, c.maxHeight))
        layout(c.maxWidth, c.maxHeight) { p.placeWithLayer(0, 0) { translationX = x * w } }
    }.background(BusyGradient))
}

/** The time above the thumb: centred on it, its bottom 22 dp above the rail's. */
@Composable
private fun SeekPill(text: String) {
    val inP = appear(TvMotion.TFast)
    Box(
        Modifier
            .layout { m, _ ->
                val p = m.measure(Constraints())
                layout(0, 0) { p.place(-p.width / 2, (17.dp - 22.dp).roundToPx() - p.height) }
            }
            .graphicsLayer {
                alpha = inP()
                translationY = (1f - inP()) * 4.dp.toPx()
            }
            .drawBehind {
                val s = 7.dp.toPx()
                rotate(45f, Offset(size.width / 2f, size.height)) {
                    drawRoundRect(
                        TvColors.Focus,
                        topLeft = Offset(size.width / 2f - s / 2f, size.height - s / 2f),
                        size = Size(s, s),
                        cornerRadius = CornerRadius(1.dp.toPx()),
                    )
                }
            }
            .clip(TvShapes.Pill)
            .background(TvColors.Focus)
            .padding(horizontal = 11.dp, vertical = 3.dp),
    ) {
        TvText(text, TvType.Body.copy(fontSize = 15.sp, lineHeight = 1.3.em, fontWeight = FontWeight.Bold, fontFeatureSettings = Tnum), color = OnVp)
    }
}

// ── pills ──────────────────────────────────────────────────────────────────

private val PillFocus = TvFocusTransform(scale = 1.06f, durationMs = TvMotion.TFast, bumpKeepsScale = false)
private val SourceFocus = TvFocusTransform(scale = 1.03f, durationMs = TvMotion.TFast, bumpKeepsScale = false)
private val PillText = TvType.Body.copy(fontSize = 14.sp, lineHeight = 1.em, fontWeight = FontWeight.SemiBold)

@Composable
private fun VpPill(
    text: String,
    action: String,
    group: String,
    onClick: () -> Unit,
    icon: String? = null,
    primary: Boolean = false,
    minWidth: Dp = 0.dp,
) {
    val t = rememberTvFocusTarget(focusId = "vp:$action", action = action, group = group, onClick = { onClick() })
    val fg = if (t.isFocused) OnVp else TvColors.Text
    Row(
        Modifier
            .tvFocusable(t, PillFocus)
            .widthIn(min = minWidth)
            .height(36.dp)
            .clip(TvShapes.Pill)
            .background(if (t.isFocused) TvColors.Focus else if (primary) TvColors.Accent else TvColors.Surface2)
            .padding(horizontal = 17.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) VpIcon(icon, 15.dp, fg)
        TvText(text, PillText, color = fg)
    }
}

@Composable
private fun ControlPills(ui: PlayerUi, actions: PlayerActions) {
    val rise = appear(TvMotion.T)
    Row(
        Modifier
            .padding(top = 18.dp)
            .graphicsLayer {
                alpha = rise()
                translationY = (1f - rise()) * 12.dp.toPx()
            }
            .tvFocusGroup("vp-controls"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val g = "vp-controls"
        VpPill(if (ui.paused) "播放" else "暫停", "toggle", g, actions::toggle, if (ui.paused) PlayerIcons.PLAY else PlayerIcons.PAUSE)
        VpPill("10 秒", "back10", g, actions::back10, PlayerIcons.BACK10)
        VpPill("10 秒", "fwd10", g, actions::fwd10, PlayerIcons.FWD10)
        if (ui.hasNext) VpPill("下一集", "next-episode", g, actions::nextEpisode, PlayerIcons.NEXT)
        VpPill("片源", "sources", g, actions::sources, PlayerIcons.SOURCES)
    }
}

// ── episode strip ──────────────────────────────────────────────────────────

private val StripScrim = Brush.verticalGradient(
    0f to TvColors.bg(0f),
    0.55f to TvColors.bg(0.8f),
    1f to TvColors.bg(0.96f),
)

@Composable
private fun KeyHint(pairs: List<Pair<String, String>>, style: androidx.compose.ui.text.TextStyle) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        pairs.forEachIndexed { i, (k, label) ->
            Box(
                Modifier
                    .padding(start = if (i > 0) 11.dp else 0.dp, end = 4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.14f))
                    .padding(horizontal = 5.dp, vertical = 0.5.dp),
            ) {
                TvText(k, TvType.Body.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold), color = TvColors.Text)
            }
            TvText(label, style, color = TvColors.Text2)
        }
    }
}

@Composable
private fun BoxScope.EpisodeStrip(ui: PlayerUi, index: Int, episodes: List<String>, currentEp: Int?) {
    val rise = appear(TvMotion.T)
    val offset by animateFloatAsState(PlayerRules.stripOffsetDp(index, episodes.size), TvMotion.focus(), label = "strip")
    Column(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .graphicsLayer {
                alpha = rise()
                translationY = (1f - rise()) * 12.dp.toPx()
            }
            .background(StripScrim)
            .padding(top = 110.dp, bottom = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp), verticalAlignment = Alignment.Bottom) {
            TvText("選集", TvType.RowHeading)
            Spacer(Modifier.width(10.dp))
            TvText("共 ${episodes.size} 集", TvType.Body.copy(fontSize = 12.sp), color = TvColors.Text2, modifier = Modifier.padding(bottom = 1.dp))
            Spacer(Modifier.weight(1f))
            KeyHint(listOf("◀ ▶" to "瀏覽", "OK" to "播放", "返回" to "關閉"), TvType.Body.copy(fontSize = 11.sp))
        }
        // Viewport: room above/below for the focused tile's scale and lift.
        Box(Modifier.fillMaxWidth().clipToBounds().padding(horizontal = 48.dp, vertical = 12.dp)) {
            Row(
                Modifier
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .graphicsLayer { translationX = -offset.dp.toPx() },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                episodes.forEachIndexed { i, label ->
                    EpisodeTile(label, i == index, i == currentEp, ui.stripInfo.getOrNull(i))
                }
            }
        }
    }
}

@Composable
private fun EpisodeTile(label: String, focused: Boolean, current: Boolean, info: StripInfo?) {
    val f by animateFloatAsState(if (focused) 1f else 0f, TvMotion.focus(), label = "tile")
    val meta = when {
        current -> "播放中"
        info?.completed == true -> "已看完"
        info != null && info.fraction > 0 -> "已看 ${Math.round(info.fraction * 100)}%"
        else -> ""
    }
    val fill = info?.let { if (it.completed) 1f else it.fraction.toFloat() } ?: 0f
    Box(
        Modifier
            .graphicsLayer {
                val s = 1f + 0.08f * f
                scaleX = s
                scaleY = s
                translationY = -3.dp.toPx() * f
            }
            .size(130.dp, 75.dp)
            .clip(TvShapes.Card)
            .background(if (focused) TvColors.Focus else TvColors.Surface2),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 11.dp)) {
            TvText(
                Labels.episodeCaption(label),
                TvType.Body.copy(fontSize = 16.sp, lineHeight = 1.2.em, fontWeight = FontWeight.Bold),
                color = if (focused) OnVp else TvColors.Text,
            )
            TvText(
                meta,
                TvType.Body.copy(fontSize = 11.sp, lineHeight = 1.3.em, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal),
                color = when {
                    focused -> TvColors.bg(0.66f)
                    current -> TvColors.Accent
                    else -> TvColors.Text2
                },
                modifier = Modifier.padding(top = 3.dp).heightIn(min = 14.5.dp),
            )
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(3.dp)
                .background(if (focused) TvColors.bg(0.14f) else Color.White.copy(alpha = 0.14f)),
        ) {
            Box(Modifier.fillMaxSize().graphicsLayer { scaleX = fill; transformOrigin = TransformOrigin(0f, 0.5f) }.background(TvColors.Accent))
        }
    }
}

// ── up next ────────────────────────────────────────────────────────────────

private val UpNextShadow = TvShadowSpec(12.dp, 30.dp, Color.Black.copy(alpha = 0.55f))
private val UpNextGradient = cssLinearGradient(135f, 0f to TvColors.CardFallbackTop, 1f to TvColors.CardFallbackBottom)

@Composable
private fun BoxScope.UpNextCardView(card: UpNextCard) {
    val slide = appear(TvMotion.TSlow)
    val drain by animateFloatAsState(
        (card.secondsLeft / PlayerRules.UP_NEXT_LEAD_SECONDS).toFloat().coerceIn(0f, 1f),
        tween(1000, easing = LinearEasing),
        label = "ring",
    )
    Row(
        Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 48.dp, bottom = 150.dp)
            .graphicsLayer {
                alpha = slide()
                translationX = (1f - slide()) * 40.dp.toPx()
            }
            .tvShadow(UpNextShadow, TvShapes.RPanel)
            .width(320.dp)
            .clip(TvShapes.Panel)
            .background(UpNextGradient)
            .border(0.5.dp, Color.White.copy(alpha = 0.08f), TvShapes.Panel)
            .padding(start = 14.dp, end = 20.dp, top = 14.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                // viewBox 80 drawn at 56 dp: r 34, stroke 6, in user units.
                val u = size.width / 80f
                val r = 34f * u
                val stroke = 6f * u
                val c = Offset(size.width / 2f, size.height / 2f)
                drawCircle(Color.White.copy(alpha = 0.16f), r, c, style = Stroke(stroke))
                drawArc(
                    TvColors.Focus,
                    startAngle = -90f,
                    sweepAngle = 360f * drain,
                    useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r),
                    size = Size(2 * r, 2 * r),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            TvText(
                card.secondsLeft.toString(),
                TvType.Body.copy(fontSize = 18.2.sp, lineHeight = 1.em, fontWeight = FontWeight.Bold, fontFeatureSettings = Tnum),
                color = TvColors.Text,
            )
        }
        Column(Modifier.weight(1f)) {
            TvText("即將播放", TvType.Body.copy(fontSize = 10.sp, lineHeight = 1.3.em, fontWeight = FontWeight.SemiBold, letterSpacing = 0.12.em), color = TvColors.Text3)
            TvText(
                card.caption,
                TvType.Body.copy(fontSize = 18.sp, lineHeight = 1.2.em, fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
            )
            KeyHint(listOf("OK" to "立即播放", "返回" to "取消"), TvType.Body.copy(fontSize = 11.sp, lineHeight = 1.3.em))
        }
    }
}

// ── modals ─────────────────────────────────────────────────────────────────

private val PanelGradient = cssLinearGradient(160f, 0f to TvColors.Surface2, 0.7f to TvColors.Surface, 1f to TvColors.Surface)
private val PanelShadow = TvShadowSpec(16.dp, 40.dp, Color.Black.copy(alpha = 0.6f))

@Composable
private fun VpModal(minWidth: Dp, centred: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val fade = appear(TvMotion.T)
    val rise = appear(TvMotion.TSlow)
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade() }
            .background(TvColors.ModalBackdrop),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .graphicsLayer {
                    alpha = rise()
                    translationY = (1f - rise()) * 12.dp.toPx()
                }
                .tvShadow(PanelShadow, TvShapes.RPanel)
                .widthIn(min = minWidth, max = 500.dp)
                .width(IntrinsicSize.Max)
                .clip(TvShapes.Panel)
                .background(PanelGradient)
                .border(0.5.dp, Color.White.copy(alpha = 0.07f), TvShapes.Panel)
                .padding(start = 32.dp, end = 32.dp, top = 28.dp, bottom = 30.dp),
            horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start,
            content = content,
        )
    }
}

@Composable
private fun SourcePicker(ui: PlayerUi, actions: PlayerActions) {
    VpModal(minWidth = 410.dp, centred = false) {
        TvText("選擇片源", TvType.Title)
        TvText("切換後會從目前位置繼續播放", TvType.Body, color = TvColors.Text2, modifier = Modifier.padding(top = 7.dp))
        // Room for the focused row's scale, which the clip would otherwise cut.
        Column(
            Modifier
                .padding(top = 14.dp)
                .layout { m, c ->
                    val bleed = 14.dp.roundToPx()
                    val maxW = if (c.hasBoundedWidth) c.maxWidth + 2 * bleed else Constraints.Infinity
                    val minW = if (c.hasBoundedWidth) c.minWidth + 2 * bleed else c.minWidth
                    val p = m.measure(c.copy(minWidth = minW, maxWidth = maxW))
                    layout((p.width - 2 * bleed).coerceAtLeast(0), p.height) { p.place(-bleed, 0) }
                }
                .heightIn(max = 308.dp)
                .clipToBounds()
                .verticalScroll(rememberScrollState())
                .tvFocusGroup("vp-sources")
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ui.sources.forEachIndexed { i, row ->
                key(i) { SourceRowView(i, row, ui.currentSource == i, actions) }
            }
        }
    }
}

@Composable
private fun SourceRowView(i: Int, row: SourceRow, current: Boolean, actions: PlayerActions) {
    val t = rememberTvFocusTarget(focusId = "vp:source:$i", action = "source", group = "vp-sources", selected = current, onClick = { actions.selectSource(i) })
    val focused = t.isFocused
    val dim = TvColors.bg(0.7f)
    Row(
        Modifier
            .tvFocusable(t, SourceFocus)
            .fillMaxWidth()
            .height(36.dp)
            .clip(TvShapes.Pill)
            .background(if (focused) TvColors.Focus else TvColors.Surface2)
            .padding(start = 18.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvText(row.label, PillText, color = if (focused) OnVp else TvColors.Text, modifier = Modifier.weight(1f))
        if (row.adSeconds > 0) {
            Box(
                Modifier
                    .clip(TvShapes.Pill)
                    .background(if (focused) TvColors.AdFreeBgFocused else TvColors.AdFreeBg)
                    .padding(horizontal = 7.dp, vertical = 1.dp),
            ) {
                TvText("免廣告", TvType.Body.copy(fontSize = 11.sp, lineHeight = 1.4.em, fontWeight = FontWeight.Bold), color = if (focused) TvColors.AdFreeTextFocused else TvColors.AdFreeText)
            }
        }
        row.runtime?.let {
            TvText(it, TvType.Meta.copy(lineHeight = 1.em, fontFeatureSettings = Tnum), color = if (focused) dim else TvColors.Text2)
        }
        if (current) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(TvColors.Accent))
                Spacer(Modifier.width(5.dp))
                TvText("播放中", TvType.Body.copy(fontSize = 11.sp, lineHeight = 1.em, fontWeight = FontWeight.SemiBold), color = if (focused) dim else TvColors.Text2)
            }
        }
    }
}

@Composable
private fun ErrorPanel(actions: PlayerActions) {
    VpModal(minWidth = 380.dp, centred = true) {
        Box(
            Modifier.padding(bottom = 12.dp).size(42.dp).border(2.dp, TvColors.Accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            TvText("!", TvType.Body.copy(fontSize = 24.sp, lineHeight = 1.em, fontWeight = FontWeight.ExtraBold), color = TvColors.Accent)
        }
        TvText("無法播放", TvType.Title, textAlign = TextAlign.Center)
        TvText(
            "這一集目前沒有可用的片源。可以重試，或返回改看其他集數。",
            TvType.Body,
            color = TvColors.Text2,
            maxLines = 3,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 7.dp),
        )
        Row(
            Modifier.padding(top = 22.dp).tvFocusGroup("vp-error"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VpPill("重試", "retry", "vp-error", actions::retry, primary = true, minWidth = 100.dp)
            VpPill("返回", "back", "vp-error", actions::back, minWidth = 100.dp)
        }
    }
}
