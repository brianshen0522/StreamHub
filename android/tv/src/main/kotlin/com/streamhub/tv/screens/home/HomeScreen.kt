package com.streamhub.tv.screens.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.streamhub.core.model.ProgressDelete
import com.streamhub.core.net.StreamHubApi
import com.streamhub.tv.LocalTvNav
import com.streamhub.tv.LocalTvSfx
import com.streamhub.tv.focus.LocalTvFocus
import com.streamhub.tv.focus.TvKey
import com.streamhub.tv.focus.TvScreenKeys
import com.streamhub.tv.focus.TvSelect
import com.streamhub.tv.focus.rememberTvColumnState
import com.streamhub.tv.focus.rememberTvFocusTarget
import com.streamhub.tv.focus.rememberTvRowState
import com.streamhub.tv.focus.tvLoading
import com.streamhub.tv.focus.tvRegion
import com.streamhub.tv.focus.tvRowColumn
import com.streamhub.tv.nav.TvAutoplay
import com.streamhub.tv.nav.TvRoute
import com.streamhub.tv.screens.common.ProviderNames
import com.streamhub.tv.screens.common.friendlyError
import com.streamhub.tv.screens.common.toggleFavoriteWithToast
import com.streamhub.tv.sfx.Sound
import com.streamhub.tv.ui.components.LocalTvToast
import com.streamhub.tv.ui.components.PosterCardData
import com.streamhub.tv.ui.components.TvBrand
import com.streamhub.tv.ui.components.TvButton
import com.streamhub.tv.ui.components.TvButtonStyle
import com.streamhub.tv.ui.components.TvModal
import com.streamhub.tv.ui.components.TvModalDefaults
import com.streamhub.tv.ui.components.TvPosterCard
import com.streamhub.tv.ui.components.TvRail
import com.streamhub.tv.ui.components.TvSkeletonCard
import com.streamhub.tv.ui.components.TvWarnBadge
import com.streamhub.tv.ui.theme.TvColors
import com.streamhub.tv.ui.theme.TvMotion
import com.streamhub.tv.ui.theme.TvSpace
import com.streamhub.tv.ui.theme.TvText
import com.streamhub.tv.ui.theme.TvType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/*
 * Home (`tizen/app/src/screens/home.rs`, CSS `styles.css:279-391`, PARITY
 * §3.2): what you were watching, what you saved, what you watched. A backdrop
 * of the focused title, the hero text over it, three rails in a row column,
 * and a top bar that steps aside while the remote is in the rails.
 */

/** The long-press menu on a card. */
private data class Menu(val kind: RailKind, val row: LibraryRow)

/**
 * The Home screen. [userName] is the session's display name. [libraryChanges]
 * emits on every realtime `favorites` / `progress` frame and on a socket
 * reconnect (`library_version`). [onSignedOut] runs after 登出 is confirmed.
 */
@Composable
fun HomeScreen(
    api: StreamHubApi,
    userName: String,
    onSignedOut: () -> Unit,
    libraryChanges: Flow<Unit> = emptyFlow(),
) {
    val engine = LocalTvFocus.current
    val nav = LocalTvNav.current
    val sfx = LocalTvSfx.current
    val toast = LocalTvToast.current
    val scope = rememberCoroutineScope()
    val signedOut by rememberUpdatedState(onSignedOut)

    val cont = remember { mutableStateOf<Load<List<LibraryRow>>>(Load.Loading) }
    val favs = remember { mutableStateOf<Load<List<LibraryRow>>>(Load.Loading) }
    val hist = remember { mutableStateOf<Load<List<LibraryRow>>>(Load.Loading) }
    var hero by remember { mutableStateOf<Hero?>(null) }
    // Every hero set rebuilds the hero subtree, replaying its fade-up, as Leptos does.
    var heroSerial by remember { mutableIntStateOf(0) }
    var confirming by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<Menu?>(null) }
    // True while the remote is in the rows: the top bar steps aside.
    var browsing by remember { mutableStateOf(false) }
    // Until the first answer the screen is `[data-loading]`: the watchdog leaves focus alone.
    var firstLoading by remember { mutableStateOf(true) }
    var sfxOn by remember { mutableStateOf(sfx.enabled) }
    val keysAtMount = remember { engine.keyCount }

    fun setHero(h: Hero?) {
        hero = h
        heroSerial++
    }

    // Loads are numbered: an older answer landing after a newer one is dropped.
    val loader = remember { HomeLoader() }
    fun load() {
        val mine = ++loader.seq
        scope.launch {
            val (c, f, h) = coroutineScope {
                val c = async { loadRows { api.continueWatching().map { it.toRow() } } }
                val f = async { loadRows { api.favorites().map { it.toRow() } } }
                val h = async { loadRows { api.history().map { it.toRow() } } }
                Triple(c.await(), f.await(), h.await())
            }
            if (loader.seq != mine) return@launch
            if (hero == null) initialHero(c, f)?.let(::setHero)
            val hd = if (h is Load.Ready) Load.Ready(dedupeHistory(h.value)) else h
            firstLoading = false
            // A failed rail (server down, Wi-Fi not up yet at power-on) retries by itself with backoff.
            loader.retry?.cancel()
            if (listOf(c, f, hd).any { it is Load.Failed }) {
                val n = loader.attempt++
                val wait = RETRY_BACKOFF_MS[minOf(n, RETRY_BACKOFF_MS.size - 1)]
                loader.retry = scope.launch {
                    delay(wait)
                    load()
                }
            } else {
                loader.attempt = 0
                loader.retry = null
            }
            // Replaced only when changed, so a no-op reload moves nothing.
            for ((state, value) in listOf(cont to c, favs to f, hist to hd)) {
                if (state.value != value) state.value = value
            }
            engine.afterLayout {
                // Arriving (nothing pressed, or nothing focused): the card the viewer
                // left from, else the first card. Later, focus moves only if what the
                // viewer was on disappeared.
                val noKeys = engine.keyCount == keysAtMount
                val lost = engine.focused == null
                if ((noKeys || lost) && engine.restore()) return@afterLayout
                if (engine.focused == null) engine.focusFirst(TvSelect.inRegion("rows"))
            }
        }
    }

    LaunchedEffect(Unit) {
        ProviderNames.ensure(api, scope)
        load()
    }
    // Finishing an episode on the phone shows up here without the remote: wait
    // for a second of quiet after the last event, then load once.
    LaunchedEffect(libraryChanges) {
        var pending: Job? = null
        libraryChanges.collect {
            pending?.cancel()
            pending = launch {
                delay(LIBRARY_DEBOUNCE_MS)
                load()
            }
        }
    }

    TvScreenKeys { press ->
        when {
            press.key != TvKey.Back -> false
            menu != null -> {
                menu = null
                true
            }
            confirming -> {
                confirming = false
                engine.focusLater(TvSelect.action("signout"))
                true
            }
            // Back walks home first: to the first card, rails at rest; only from there the exit prompt.
            else -> engine.backTo(TvSelect.inRegion("rows"))
        }
    }

    fun removeFavorite(row: LibraryRow) {
        scope.launch {
            runCatching { api.removeFavorite(row.id) }
                .onSuccess { toast.show("已取消收藏：${row.title}") }
                .onFailure { if (it !is CancellationException) toast.show(friendlyError(it)) }
            load()
        }
    }

    fun removeContinue(row: LibraryRow) {
        scope.launch {
            // The card stands for the whole title: `scope: "title"`. itemUrl is ignored but required.
            runCatching { api.deleteProgress(ProgressDelete(providerKey = row.providerKey, itemUrl = row.itemUrl, scope = "title", title = row.title)) }
                .onSuccess { toast.show("已從繼續觀看移除：${row.title}") }
                .onFailure { if (it !is CancellationException) toast.show(friendlyError(it)) }
            load()
        }
    }

    val rails = RailActions(
        onFocusCard = { h ->
            setHero(h)
            browsing = true
        },
        onOpen = { row, kind -> nav.push(TvRoute.Detail(targetFromRow(row, kind == RailKind.History))) },
        onPlay = { row, kind ->
            TvAutoplay.request(row.providerKey, row.itemUrl)
            nav.push(TvRoute.Detail(targetFromRow(row, kind == RailKind.History)))
        },
        onLong = { row, kind ->
            menu = Menu(kind, row)
            engine.focusLater(TvSelect.action(if (kind == RailKind.Continue) "menu-remove" else "menu-unfavorite"))
        },
        onFavorite = { row, kind ->
            if (kind == RailKind.Favorites) removeFavorite(row)
            else toggleFavoriteWithToast(api, scope, toast, favoriteKeyOf(row)) { load() }
        },
        poster = { raw -> raw.takeIf { it.isNotBlank() }?.let(api::posterUrl) },
    )

    val allEmpty = listOf(cont.value, favs.value, hist.value).all { it is Load.Ready && it.value.isEmpty() }
    val backdropUrl = hero?.poster?.takeIf { it.isNotBlank() }?.let(api::posterUrl).orEmpty()

    Box(Modifier.fillMaxSize().then(if (firstLoading) Modifier.tvLoading() else Modifier)) {
        HomeBackdrop(backdropUrl)
        HomeTopBar(
            userName = userName,
            browsing = browsing,
            sfxOn = sfxOn,
            onTopBarFocus = { browsing = false },
            onSearch = { nav.push(TvRoute.Search) },
            onToggleSfx = {
                val on = sfx.setEnabled(!sfxOn)
                sfxOn = on
                // The OK that switched it on was still silent.
                if (on) sfx.play(Sound.Select)
            },
            onSignOut = {
                confirming = true
                engine.focusLater(TvSelect.action("signout-cancel"))
            },
        )
        HomeHero(hero, heroSerial, browsing)
        // `.tv-rows-viewport`: top 456px to the bottom, clipped.
        Box(Modifier.fillMaxSize().padding(top = 228.dp).clipToBounds()) {
            val column = rememberTvColumnState()
            Column(Modifier.fillMaxWidth().tvRowColumn(column, anchor = 0.dp).tvRegion("rows")) {
                LibraryRail("繼續觀看", "home-continue", cont.value, RailKind.Continue, rails)
                LibraryRail("我的收藏", "home-favorites", favs.value, RailKind.Favorites, rails)
                LibraryRail("觀看紀錄", "home-history", hist.value, RailKind.History, rails)
                if (allEmpty) {
                    TvText(
                        "還沒有內容。按「搜尋」找點東西來看吧。",
                        TvType.Body.copy(color = TvColors.Text2),
                        Modifier.padding(vertical = 4.dp, horizontal = TvSpace.SafeX),
                    )
                }
            }
        }
        menu?.let { m ->
            TvModal(title = m.row.title, menu = true) {
                if (m.kind == RailKind.Continue) {
                    TvButton(
                        "從清單移除",
                        onClick = {
                            menu = null
                            removeContinue(m.row)
                        },
                        modifier = Modifier.widthIn(min = TvModalDefaults.ActionMinWidth),
                        autofocus = true,
                        action = "menu-remove",
                    )
                } else {
                    TvButton(
                        "取消收藏",
                        onClick = {
                            menu = null
                            removeFavorite(m.row)
                        },
                        modifier = Modifier.widthIn(min = TvModalDefaults.ActionMinWidth),
                        autofocus = true,
                        action = "menu-unfavorite",
                    )
                }
                TvButton(
                    "查看詳情",
                    onClick = {
                        menu = null
                        nav.push(TvRoute.Detail(targetFromRow(m.row, false)))
                    },
                    modifier = Modifier.widthIn(min = TvModalDefaults.ActionMinWidth),
                    action = "menu-details",
                )
            }
        }
        if (confirming) {
            TvModal(title = "要登出這台電視嗎？", text = "登出後，需要再配對一次或輸入密碼才能使用。") {
                // Focus lands on staying signed in: a reflexive second OK cancels.
                TvButton(
                    "取消",
                    onClick = {
                        confirming = false
                        engine.focusLater(TvSelect.action("signout"))
                    },
                    modifier = Modifier.widthIn(min = TvModalDefaults.ActionMinWidth),
                    autofocus = true,
                    action = "signout-cancel",
                )
                TvButton(
                    "登出",
                    onClick = {
                        scope.launch {
                            runCatching { api.logout() }
                            signedOut()
                        }
                    },
                    modifier = Modifier.widthIn(min = TvModalDefaults.ActionMinWidth),
                    style = TvButtonStyle.Danger,
                    action = "signout-confirm",
                )
            }
        }
    }
}

private class HomeLoader {
    var seq = 0L
    var attempt = 0
    var retry: Job? = null
}

private suspend fun loadRows(fetch: suspend () -> List<LibraryRow>): Load<List<LibraryRow>> = try {
    Load.Ready(fetch())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Load.Failed(friendlyError(e))
}

/** What a rail's cards do. */
private class RailActions(
    val onFocusCard: (Hero) -> Unit,
    val onOpen: (LibraryRow, RailKind) -> Unit,
    val onPlay: (LibraryRow, RailKind) -> Unit,
    val onLong: (LibraryRow, RailKind) -> Unit,
    val onFavorite: (LibraryRow, RailKind) -> Unit,
    val poster: (String) -> String?,
)

/** Home's `.tv-track` override: padding-top 22px, bottom 18px. */
private val HomeTrackPadding = PaddingValues(start = TvSpace.SafeX, end = TvSpace.SafeX, top = 11.dp, bottom = 9.dp)

/** Home's `.tv-row` padding-bottom 6px. */
private val HomeRowPaddingBottom = 3.dp

/** `LibraryRail`: skeletons while loading, a red caption on failure, nothing when empty. */
@Composable
private fun LibraryRail(title: String, group: String, rows: Load<List<LibraryRow>>, kind: RailKind, actions: RailActions) {
    val row = rememberTvRowState(group)
    when (rows) {
        Load.Loading -> TvRail(title, group, row, rowPaddingBottom = HomeRowPaddingBottom, trackPadding = HomeTrackPadding) {
            repeat(8) { TvSkeletonCard() }
        }
        is Load.Failed -> TvRail(title, group, row, caption = rows.message, captionIsError = true, rowPaddingBottom = HomeRowPaddingBottom) {}
        is Load.Ready -> if (rows.value.isNotEmpty()) {
            TvRail(
                title, group, row,
                note = rows.value.size.toString(),
                rowPaddingBottom = HomeRowPaddingBottom,
                trackPadding = HomeTrackPadding,
            ) {
                for (r in rows.value) {
                    val card = cardOf(kind, r)
                    val hero = heroOf(card, r, kind)
                    key(card.id) {
                        TvPosterCard(
                            card = PosterCardData(
                                id = card.id,
                                title = card.title,
                                // A favourite with no season or episode shows its provider, by display name.
                                subtitle = card.subtitle.ifEmpty { card.provider?.let(ProviderNames::name).orEmpty() },
                                posterUrl = actions.poster(card.poster),
                                progress = card.progress ?: 0.0,
                                unavailable = r.isUnavailable,
                                unavailableReason = r.unavailableReason,
                                nextUp = card.chip != null,
                            ),
                            onOpen = { actions.onOpen(r, kind) },
                            onPlay = { actions.onPlay(r, kind) },
                            // History has no long press, so its OK opens on key-down.
                            onLongPress = if (kind != RailKind.History) ({ actions.onLong(r, kind) }) else null,
                            onFavorite = { actions.onFavorite(r, kind) },
                            onFocus = { actions.onFocusCard(hero) },
                        )
                    }
                }
            }
        }
    }
}

// ── top bar ──────────────────────────────────────────────────────────────────

/** `.tv-topbar .tv-btn`: 64px high, padding 0 32px, 26px text. */
private val TopBarButtonHeight = 32.dp
private val TopBarButtonPadding = 16.dp
private val TopBarButtonText = TvType.Button.copy(fontSize = 13.sp)

@Composable
private fun HomeTopBar(
    userName: String,
    browsing: Boolean,
    sfxOn: Boolean,
    onTopBarFocus: () -> Unit,
    onSearch: () -> Unit,
    onToggleSfx: () -> Unit,
    onSignOut: () -> Unit,
) {
    val p by animateFloatAsState(if (browsing) 1f else 0f, TvMotion.focus(), label = "topbar")
    val search = rememberTvFocusTarget(focusId = "home-search", action = "search", autofocus = true, onClick = { onSearch() })
    val sound = rememberTvFocusTarget(focusId = "home-sfx", action = "sfx", onClick = { onToggleSfx() })
    val signOut = rememberTvFocusTarget(focusId = "home-signout", action = "signout", onClick = { onSignOut() })
    val focusIn by rememberUpdatedState(onTopBarFocus)
    // `on:focusin` on the header: focus in the top bar ends browsing.
    val inBar = search.isFocused || sound.isFocused || signOut.isFocused
    LaunchedEffect(inBar) { if (inBar) focusIn() }

    Row(
        Modifier
            .zIndex(3f)
            .padding(top = TvSpace.SafeY, start = TvSpace.SafeX, end = TvSpace.SafeX)
            .fillMaxWidth()
            .height(36.dp)
            .graphicsLayer {
                alpha = 1f - p
                translationY = -14.dp.toPx() * p
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TvBrand()
        TvText(userName, TvType.Meta.copy(lineHeight = TvType.Body.lineHeight, color = TvColors.Text2))
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton(
                "搜尋", onClick = {}, target = search, style = TvButtonStyle.Primary,
                height = TopBarButtonHeight, paddingX = TopBarButtonPadding, textStyle = TopBarButtonText,
                icon = { c -> TopBarIcon(c, SearchIcon) },
            )
            TvButton(
                if (sfxOn) "音效 開" else "音效 關", onClick = {}, target = sound,
                height = TopBarButtonHeight, paddingX = TopBarButtonPadding, textStyle = TopBarButtonText,
                icon = { c -> TopBarIcon(c, if (sfxOn) SpeakerOnIcon else SpeakerOffIcon) },
            )
            TvButton(
                "登出", onClick = {}, target = signOut,
                height = TopBarButtonHeight, paddingX = TopBarButtonPadding, textStyle = TopBarButtonText,
            )
        }
    }
}

/** SVG path data on a 24-unit viewBox (`home.rs:557-586`). */
private val SearchIcon = listOf("M17 10.5a6.5 6.5 0 1 1-13 0a6.5 6.5 0 1 1 13 0z", "m20 20-4.6-4.6")
private val SpeakerOnIcon = listOf("M4 9v6h4l5 4V5L8 9z", "M16.5 8.5a5 5 0 0 1 0 7M19 6a8.5 8.5 0 0 1 0 12")
private val SpeakerOffIcon = listOf("M4 9v6h4l5 4V5L8 9z", "m17 9 5 6m0-6-5 6")

private val iconPaths = HashMap<String, androidx.compose.ui.graphics.Path>()

/** `.tv-icon` in the top bar: 28px (14 dp), stroke-only, width 2.4, round caps and joins. */
@Composable
private fun TopBarIcon(color: Color, paths: List<String>) {
    Canvas(Modifier.size(14.dp)) {
        val k = size.width / 24f
        scale(k, k, pivot = Offset.Zero) {
            for (d in paths) {
                val path = iconPaths.getOrPut(d) { PathParser().parsePathString(d).toPath() }
                drawPath(path, color, style = Stroke(width = 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

// ── hero ─────────────────────────────────────────────────────────────────────

private val HeroTitle = TvType.Display.copy(letterSpacing = (-0.25).sp)
private val HeroMeta = TvType.Body.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium)
private val HeroHint = TvType.Body.copy(fontSize = 11.sp, color = TvColors.Text3)

/**
 * `.tv-hero`: bottom edge at y = 220 dp, 520 dp wide, raised 12 dp while
 * browsing. Rebuilt on every hero change ([serial]), replaying the fade-ups.
 */
@Composable
private fun HomeHero(hero: Hero?, serial: Int, browsing: Boolean) {
    val lift by animateFloatAsState(if (browsing) 1f else 0f, TvMotion.focus(), label = "hero-lift")
    Box(
        Modifier
            .zIndex(1f)
            .fillMaxWidth()
            .height(220.dp)
            .graphicsLayer { translationY = -12.dp.toPx() * lift },
    ) {
        key(serial) {
            val title = remember { Animatable(0f) }
            val meta = remember { Animatable(0f) }
            val fade = remember { Animatable(0f) }
            LaunchedEffect(Unit) { title.animateTo(1f, TvMotion.focus()) }
            LaunchedEffect(Unit) { meta.animateTo(1f, TvMotion.tween(TvMotion.T, delayMs = 40)) }
            LaunchedEffect(Unit) { fade.animateTo(1f, TvMotion.tween(TvMotion.T, delayMs = 80)) }
            Column(Modifier.align(Alignment.BottomStart).padding(start = TvSpace.SafeX).width(520.dp)) {
                TvText(hero?.title ?: "歡迎回來", HeroTitle, Modifier.fadeUp(title), maxLines = 2)
                Row(
                    Modifier.padding(top = 9.dp).fadeUp(meta),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val line = hero?.line ?: "選一部繼續看，或按「搜尋」找新片。"
                    if (line.isNotEmpty()) TvText(line, HeroMeta)
                    if (hero != null && hero.meta.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // `.tv-hero-line + .tv-chip-plain::before`: "·", margin-right 16px.
                            if (line.isNotEmpty()) TvText("·", HeroMeta, Modifier.padding(end = 8.dp), color = TvColors.Text3)
                            TvText(ProviderNames.name(hero.meta), HeroMeta, color = TvColors.Text2)
                        }
                    }
                    hero?.badge?.let { TvWarnBadge(it) }
                }
                hero?.progress?.let { p ->
                    Box(
                        Modifier
                            .padding(top = 9.dp)
                            .graphicsLayer { alpha = fade.value }
                            .size(180.dp, 3.dp)
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(Color.White.copy(alpha = 0.2f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = (p / 100.0).coerceIn(0.02, 1.0).toFloat()
                                    transformOrigin = TransformOrigin(0f, 0.5f)
                                }
                                .background(TvColors.Accent),
                        )
                    }
                }
                hero?.hint?.let {
                    TvText(it, HeroHint, Modifier.padding(top = 7.dp).graphicsLayer { alpha = fade.value })
                }
            }
        }
    }
}

/** `tv-fade-up`: opacity 0 → 1 and translateY 12px → 0. */
private fun Modifier.fadeUp(p: Animatable<Float, *>): Modifier = graphicsLayer {
    alpha = p.value
    translationY = (1f - p.value) * TvMotion.FadeUpDistance.toPx()
}
