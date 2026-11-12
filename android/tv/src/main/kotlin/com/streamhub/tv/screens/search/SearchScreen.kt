package com.streamhub.tv.screens.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.streamhub.core.model.SearchItem
import com.streamhub.core.net.StreamHubApi
import com.streamhub.tv.LocalTvNav
import com.streamhub.tv.focus.LocalTvFocus
import com.streamhub.tv.focus.TvIntent
import com.streamhub.tv.focus.TvKey
import com.streamhub.tv.focus.TvRowState
import com.streamhub.tv.focus.TvScreenKeys
import com.streamhub.tv.focus.TvSelect
import com.streamhub.tv.focus.rememberTvColumnState
import com.streamhub.tv.focus.rememberTvFocusTarget
import com.streamhub.tv.focus.tvRegion
import com.streamhub.tv.focus.tvRowColumn
import com.streamhub.tv.nav.DetailTarget
import com.streamhub.tv.nav.TvAutoplay
import com.streamhub.tv.nav.TvRoute
import com.streamhub.tv.screens.common.FavoriteKey
import com.streamhub.tv.screens.common.Load
import com.streamhub.tv.screens.common.RecentStore
import com.streamhub.tv.screens.common.TvCatalog
import com.streamhub.tv.screens.common.TvIcon
import com.streamhub.tv.screens.common.TvIconPaths
import com.streamhub.tv.screens.common.toggleFavoriteWithToast
import com.streamhub.tv.ui.components.LocalTvToast
import com.streamhub.tv.ui.components.PosterCardData
import com.streamhub.tv.ui.components.TvButton
import com.streamhub.tv.ui.components.TvButtonSize
import com.streamhub.tv.ui.components.TvPosterCard
import com.streamhub.tv.ui.components.TvRail
import com.streamhub.tv.ui.components.TvSkeletonCard
import com.streamhub.tv.ui.components.TvTextField
import com.streamhub.tv.ui.theme.TvColors
import com.streamhub.tv.ui.theme.TvSpace
import com.streamhub.tv.ui.theme.TvText
import com.streamhub.tv.ui.theme.TvType

private const val RECENT_ROW = "\u0000recent"

/**
 * Search (`screens/search.rs`, `styles.css:544-580`, PARITY §3.3): a field,
 * then one rail per allowed provider that streams in with skeletons, its count,
 * or its error. With no results, recent searches show as chips.
 *
 * [api] serves the red key's favourite toggle (the shared helper); everything
 * else goes through [catalog].
 */
@Composable
fun SearchScreen(catalog: TvCatalog, api: StreamHubApi, recents: RecentStore) {
    val engine = LocalTvFocus.current
    val nav = LocalTvNav.current
    val toast = LocalTvToast.current
    val scope = rememberCoroutineScope()
    val column = rememberTvColumnState()
    val rowStates = remember { HashMap<String, TvRowState>() }
    fun rowState(k: String) = rowStates.getOrPut(k) { TvRowState(k) }

    // Mount: a prefill (Detail's 已下架) searches now; a remount with results
    // restores focus instead of autofocusing the field (search.rs:303-318).
    val hadResults = remember {
        if (SearchSession.takePending()) SearchSession.run(SearchSession.query.text, catalog, recents)
        SearchSession.groups.isNotEmpty()
    }
    var recentList by remember { mutableStateOf(SearchSession.recent(recents)) }
    LaunchedEffect(Unit) {
        if (hadResults) engine.afterLayout { engine.restore() }
    }
    DisposableEffect(Unit) {
        onDispose {
            // Leaving for Detail keeps the results; leaving Search for good drops them.
            if (nav.stack.none { it is TvRoute.Search }) SearchSession.reset()
        }
    }

    val groups = SearchSession.groups
    val showRecents = groups.isEmpty() && recentList.isNotEmpty()
    val firstRow: TvRowState? = when {
        showRecents -> rowState(RECENT_ROW)
        else -> groups.firstOrNull()?.let { rowState(it.key) }
    }

    // Back from the results returns to the field first; Up from the first row
    // always lands on it, whatever is nearest (search.rs:325-338).
    TvScreenKeys { press ->
        when (press.key) {
            TvKey.Back -> engine.focusedWithin(TvSelect.inRegion("results")) && engine.backTo(TvSelect.action("q"))
            TvKey.Up -> {
                val row = firstRow
                if (row != null && engine.focusedWithin(with(TvSelect) { inRegion("results") and inRow(row) })) {
                    engine.focusFirst(TvSelect.action("q"))
                } else {
                    false
                }
            }
            else -> false
        }
    }

    val submit = {
        SearchSession.run(SearchSession.query.text, catalog, recents)
        recentList = SearchSession.recent(recents)
    }
    val field = rememberTvFocusTarget(focusId = "search-input", action = "q", autofocus = !hadResults)

    Box(Modifier.fillMaxSize()) {
        // The bar (a form): top 32, sides 48, 48 high, gap 12, z2.
        Row(
            Modifier
                .zIndex(2f)
                .padding(start = TvSpace.SafeX, end = TvSpace.SafeX, top = TvSpace.SafeY)
                .fillMaxWidth()
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.width(620.dp)) {
                TvTextField(
                    value = SearchSession.query,
                    onValueChange = { SearchSession.query = it },
                    onSubmit = submit,
                    placeholder = "輸入片名，按 OK 搜尋",
                    imeAction = ImeAction.Search,
                    target = field,
                    height = 48.dp,
                    contentPadding = PaddingValues(start = 46.dp, end = 16.dp),
                    textStyle = TvType.Input.copy(fontSize = 18.sp),
                )
                TvIcon(
                    TvIconPaths.Search,
                    if (field.isFocused) TvColors.Text else TvColors.Text3,
                    Modifier.align(Alignment.CenterStart).padding(start = 18.dp),
                    size = 18.dp,
                )
            }
            TvButton("搜尋", onClick = { submit() }, action = "search-submit", height = 40.dp)
        }

        // The results: from 100 dp to the bottom, clipped.
        Box(Modifier.fillMaxSize().padding(top = 100.dp).clipToBounds()) {
            Column(Modifier.fillMaxWidth().tvRegion("results")) {
                SearchSession.notice?.let {
                    TvText(
                        it,
                        TvType.Body.copy(fontSize = 13.sp),
                        Modifier.padding(start = TvSpace.SafeX, end = TvSpace.SafeX, top = 4.dp, bottom = 10.dp),
                        color = TvColors.Text2,
                    )
                }
                Column(Modifier.fillMaxWidth().tvRowColumn(column, anchor = 0.dp)) {
                    if (showRecents) {
                        TvRail(title = "最近搜尋", group = "search-recent", row = rowState(RECENT_ROW), gap = 9.dp) {
                            for (r in recentList) {
                                key(r) {
                                    TvButton(
                                        r,
                                        onClick = {
                                            SearchSession.query = TextFieldValue(r, TextRange(r.length))
                                            SearchSession.run(r, catalog, recents)
                                            recentList = SearchSession.recent(recents)
                                        },
                                        size = TvButtonSize.Chip,
                                        focusId = "recent:$r",
                                    )
                                }
                            }
                        }
                    }
                    for (g in groups) {
                        key(g.key) {
                            ProviderRail(g, rowState(g.key), catalog) { target, fav ->
                                when (fav) {
                                    null -> nav.push(TvRoute.Detail(target))
                                    else -> toggleFavoriteWithToast(api, scope, toast, fav)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The Play key on a card: autoplay, then open (`search.rs:286-289`). */
private fun playTarget(t: DetailTarget, push: (TvRoute) -> Unit) {
    TvAutoplay.request(t.provider, t.url)
    push(TvRoute.Detail(t))
}

/** `ProviderRail` (`search.rs:223-300`). [act] opens a target (fav null) or toggles a favourite. */
@Composable
private fun ProviderRail(group: SearchGroup, row: TvRowState, catalog: TvCatalog, act: (DetailTarget, FavoriteKey?) -> Unit) {
    val railGroup = "search-${group.key}"
    val nav = LocalTvNav.current
    when (val state = group.state) {
        Load.Loading -> TvRail(title = group.name, group = railGroup, row = row) {
            repeat(8) { TvSkeletonCard() }
        }
        // Empty and failed providers collapse to one caption line.
        is Load.Failed -> TvRail(
            title = group.name, group = railGroup, row = row,
            caption = state.message, captionIsError = true, rowPaddingBottom = 12.dp,
        ) {}
        is Load.Ready -> if (state.value.isEmpty()) {
            TvRail(title = group.name, group = railGroup, row = row, caption = "沒有結果", rowPaddingBottom = 12.dp) {}
        } else {
            val items = state.value
            val total = items.size
            // A popular title answers with 240+ cards: show a page and add the
            // next as focus nears the end, remembered for the way back.
            var shown by remember(total) { mutableIntStateOf(minOf(SearchSession.shownFor(group.key), total)) }
            val grow = {
                val next = minOf(shown + SearchSession.PAGE, total)
                if (next > shown) {
                    shown = next
                    SearchSession.rememberShown(group.key, next)
                }
            }
            TvRail(title = group.name, group = railGroup, row = row, note = total.toString()) {
                for (i in 0 until shown) {
                    key(i) {
                        ResultCard(items[i], group.key, catalog, onFocus = { if (i + SearchSession.PREFETCH >= shown) grow() }, act = act, push = nav::push)
                    }
                }
            }
        }
    }
}

private fun resultTarget(item: SearchItem, provider: String) = DetailTarget(
    provider = item.provider.ifEmpty { provider },
    url = item.url,
    title = item.title,
    posterUrl = item.posterUrl.orEmpty(),
    mediaType = item.mediaType,
)

/** Subtitle: non-empty [year, 電影 | 影集] joined by " · " (`result_card`, `search.rs:200-220`). */
internal fun resultSubtitle(item: SearchItem): String = listOf(
    item.year.orEmpty(),
    when (item.mediaType) {
        "movie" -> "電影"
        "tv" -> "影集"
        else -> ""
    },
).filter { it.isNotEmpty() }.joinToString(" · ")

@Composable
private fun ResultCard(
    item: SearchItem,
    provider: String,
    catalog: TvCatalog,
    onFocus: () -> Unit,
    act: (DetailTarget, FavoriteKey?) -> Unit,
    push: (TvRoute) -> Unit,
) {
    val target = resultTarget(item, provider)
    val card = PosterCardData(
        id = "s:$provider:${item.url}",
        title = item.title,
        subtitle = resultSubtitle(item),
        posterUrl = item.posterUrl?.takeIf { it.isNotBlank() }?.let { catalog.posterUrl(it) },
    )
    val fav = FavoriteKey(target.provider, target.url, target.title, target.posterUrl, target.mediaType)
    val t = rememberTvFocusTarget(
        focusId = card.id,
        playable = true,
        favable = true,
        onClick = { intent ->
            when (intent) {
                TvIntent.Play -> playTarget(target, push)
                TvIntent.Favorite -> act(target, fav)
                TvIntent.LongPress, TvIntent.Open -> act(target, null)
            }
        },
    )
    val focusHook by rememberUpdatedState(onFocus)
    LaunchedEffect(t.isFocused) { if (t.isFocused) focusHook() }
    TvPosterCard(card, onOpen = { act(target, null) }, target = t)
}
