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
import com.streamhub.tv.auth.LoginViewModel
import com.streamhub.tv.focus.LocalTvFocus
import com.streamhub.tv.focus.TvKey
import com.streamhub.tv.focus.TvScreenKeys
import com.streamhub.tv.focus.TvSelect
import com.streamhub.tv.focus.rememberTvColumnState
import com.streamhub.tv.focus.rememberTvRowState
import com.streamhub.tv.focus.tvRegion
import com.streamhub.tv.focus.tvRowColumn
import com.streamhub.tv.home.HomeViewModel
import com.streamhub.tv.nav.DetailTarget
import com.streamhub.tv.nav.PlayTarget
import com.streamhub.tv.nav.TvRoute
import com.streamhub.tv.ui.QrCode
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

/** Sign-in stand-in: the pairing code and QR, or username and password. */
@Composable
fun LoginPlaceholder(container: AppContainer, onSignedIn: () -> Unit) {
    val vm = viewModel { LoginViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val engine = LocalTvFocus.current
    var password by remember { mutableStateOf(false) }
    var user by remember { mutableStateOf(TextFieldValue()) }
    var pass by remember { mutableStateOf(TextFieldValue()) }
    LaunchedEffect(Unit) { vm.startPairing(onSignedIn) }
    TvScreenKeys { press ->
        if (press.key == TvKey.Back && password) {
            password = false
            engine.focusLater(TvSelect.action("use-password"))
            true
        } else {
            false
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = TvSpace.SafeX, vertical = TvSpace.SafeY)) {
        TvText("StreamHub", TvType.Brand)
        Spacer(Modifier.height(24.dp))
        if (!password) {
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(180.dp)) {
                    state.pairing?.let { QrCode(it.verificationUrlComplete, Modifier.fillMaxSize()) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvText("用手機掃描或輸入代碼", TvType.Title)
                    TvText(
                        state.pairing?.userCode ?: (state.pairingError ?: "正在取得代碼…"),
                        TvType.Display.copy(fontFamily = FontFamily.Monospace, fontSize = 28.sp),
                    )
                    TvButton("改用帳號密碼登入", onClick = { password = true; engine.focusLater(TvSelect.action("username")) },
                        style = TvButtonStyle.Text, autofocus = true, action = "use-password")
                }
            }
        } else {
            Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TvTextField(user, { user = it }, onSubmit = { engine.focusFirst(TvSelect.action("password")) },
                    label = "帳號", action = "username", autofocus = true, imeAction = ImeAction.Next)
                TvTextField(pass, { pass = it }, onSubmit = { vm.signIn(user.text, pass.text, onSignedIn) },
                    label = "密碼", action = "password", password = true)
                TvButton(if (state.busy) "登入中…" else "登入", onClick = { vm.signIn(user.text, pass.text, onSignedIn) },
                    style = TvButtonStyle.Primary, wide = true, action = "login")
                state.error?.let { TvText(it, TvType.Error) }
            }
        }
    }
}

/** Home stand-in: the sample screen fed with the library. */
@Composable
fun HomePlaceholder(container: AppContainer, sfxOn: Boolean, onToggleSfx: () -> Unit, onOpen: (TvRoute) -> Unit, onSignOut: () -> Unit) {
    val vm = viewModel { HomeViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val poster: (String?) -> String? = { p -> p?.takeIf { it.isNotBlank() }?.let { container.api.posterUrl(it) } }
    val targets = HashMap<String, DetailTarget>()
    val cont = state.continueWatching.map { c ->
        val id = "continue:${c.providerKey}:${c.itemUrl}"
        targets[id] = DetailTarget(c.providerKey, c.itemUrl, c.title, c.posterUrl.orEmpty(), c.mediaType, c.seasonUrl, c.episodeLabel, row = c)
        PosterCardData(id, c.title, c.episodeLabel.orEmpty(), poster(c.posterUrl), c.progressPercent, nextUp = c.nextUp)
    }
    val favs = state.favorites.map { f ->
        val id = "fav:${f.providerKey}:${f.itemUrl}"
        targets[id] = DetailTarget(f.providerKey, f.itemUrl, f.title, f.posterUrl.orEmpty(), f.mediaType, f.seasonUrl, f.episodeLabel, row = f)
        PosterCardData(id, f.title, f.providerKey, poster(f.posterUrl))
    }
    val rails = listOf(
        SampleRail("繼續觀看", "continue", cont, loading = state.loading),
        SampleRail("我的收藏", "favorites", favs, loading = state.loading),
    ).filter { it.loading || it.cards.isNotEmpty() } + listOfNotNull(state.error?.let { SampleRail("首頁", "error", error = it) })
    SampleHome(
        rails = rails,
        onOpen = { card -> targets[card.id]?.let { onOpen(TvRoute.Detail(it)) } },
        sfxOn = sfxOn,
        onSearch = { onOpen(TvRoute.Search) },
        onToggleSfx = onToggleSfx,
        onSignOut = onSignOut,
        hero = cont.firstOrNull()?.title,
    )
}

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
