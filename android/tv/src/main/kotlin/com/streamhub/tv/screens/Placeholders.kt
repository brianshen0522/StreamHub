package com.streamhub.tv.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamhub.core.model.SearchResponse
import com.streamhub.tv.AppContainer
import com.streamhub.tv.focus.LocalTvFocus
import com.streamhub.tv.focus.TvKey
import com.streamhub.tv.focus.TvScreenKeys
import com.streamhub.tv.focus.TvSelect
import com.streamhub.tv.focus.rememberTvColumnState
import com.streamhub.tv.focus.rememberTvRowState
import com.streamhub.tv.focus.tvRegion
import com.streamhub.tv.focus.tvRowColumn
import com.streamhub.tv.nav.DetailTarget
import com.streamhub.tv.nav.PlayTarget
import com.streamhub.tv.nav.TvRoute
import com.streamhub.tv.ui.components.LocalTvToast
import com.streamhub.tv.ui.components.PosterCardData
import com.streamhub.tv.ui.components.TvButton
import com.streamhub.tv.ui.components.TvButtonStyle
import com.streamhub.tv.ui.components.TvPosterCard
import com.streamhub.tv.ui.components.TvRail
import com.streamhub.tv.ui.components.TvTextField
import com.streamhub.tv.ui.sample.SampleHome
import com.streamhub.tv.ui.sample.SampleRail
import com.streamhub.tv.ui.theme.TvColors
import com.streamhub.tv.ui.theme.TvSpace
import com.streamhub.tv.ui.theme.TvText
import com.streamhub.tv.ui.theme.TvType
import kotlinx.coroutines.launch

/*
 * Stand-ins until the screen rewrites land (PARITY §3). Each is built only from
 * the foundation, so the app runs end to end: sign in by pairing (or password),
 * browse the library, search, open a title. None of them is a parity
 * implementation; replace each file's screen wholesale.
 */

/** Search stand-in: a field and one rail per provider. */
@Composable
fun SearchPlaceholder(container: AppContainer, onOpen: (TvRoute) -> Unit) {
    var query by remember { mutableStateOf(TextFieldValue()) }
    var result by remember { mutableStateOf<SearchResponse?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val engine = LocalTvFocus.current
    val toast = LocalTvToast.current
    val column = rememberTvColumnState()
    TvScreenKeys { press ->
        press.key == TvKey.Back && engine.focusedWithin(TvSelect.inRegion("results")) && engine.backTo(TvSelect.action("q"))
    }
    Column(Modifier.fillMaxSize().padding(top = TvSpace.SafeY)) {
        TvTextField(
            query, { query = it },
            onSubmit = {
                val q = query.text.trim()
                if (q.isNotEmpty()) scope.launch {
                    busy = true
                    result = runCatching { container.api.search(q) }.onFailure { toast.show("搜尋失敗") }.getOrNull()
                    busy = false
                }
            },
            modifier = Modifier.padding(horizontal = TvSpace.SafeX).width(620.dp),
            placeholder = "搜尋片名",
            action = "q",
            focusId = "search:q",
            autofocus = true,
            imeAction = ImeAction.Search,
        )
        Spacer(Modifier.height(16.dp))
        if (busy) TvText("搜尋中…", TvType.Meta, Modifier.padding(horizontal = TvSpace.SafeX), color = TvColors.Text2)
        Column(Modifier.tvRowColumn(column).tvRegion("results")) {
            for (p in result?.results.orEmpty()) {
                val row = rememberTvRowState(p.provider)
                TvRail(
                    title = p.provider, group = "search:${p.provider}", row = row,
                    note = if (p.error == null && p.items.isNotEmpty()) p.items.size.toString() else null,
                    caption = p.error ?: if (p.items.isEmpty()) "沒有結果" else null,
                    captionIsError = p.error != null,
                ) {
                    for (item in p.items) {
                        val card = PosterCardData("${item.provider}:${item.url}", item.title, item.mediaType,
                            item.posterUrl?.takeIf { it.isNotBlank() }?.let { container.api.posterUrl(it) })
                        TvPosterCard(card, onOpen = {
                            onOpen(TvRoute.Detail(DetailTarget(item.provider, item.url, item.title, item.posterUrl.orEmpty(), item.mediaType)))
                        })
                    }
                }
            }
        }
    }
}

/** Detail stand-in: the title and a Play that opens the player stand-in. */
@Composable
fun DetailPlaceholder(target: DetailTarget, onOpen: (TvRoute) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = TvSpace.SafeX, vertical = 100.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TvText(target.title, TvType.Display, maxLines = 2)
        TvText("${target.provider} · ${target.mediaType}", TvType.Meta, color = TvColors.Text2)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("播放", onClick = {
                onOpen(TvRoute.Player(PlayTarget(target.provider, target.url, target.seasonUrl.orEmpty(), target.title, target.posterUrl, target.mediaType)))
            }, style = TvButtonStyle.Primary, autofocus = true, action = "play", focusId = "detail:play", playable = true)
            TvButton("加入收藏", onClick = {}, action = "fav", focusId = "detail:fav")
        }
        TvText("（詳細頁尚未完成）", TvType.Meta, color = TvColors.Text3)
    }
}

/** Player stand-in: black, with a caption. Back pops through the normal chain. */
@Composable
fun PlayerPlaceholder(target: PlayTarget) {
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black), contentAlignment = Alignment.Center) {
        TvText("${target.title}：播放器尚未完成", TvType.Title, color = TvColors.Text2)
    }
}
