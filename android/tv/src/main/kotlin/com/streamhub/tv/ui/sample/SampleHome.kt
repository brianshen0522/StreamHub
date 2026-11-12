package com.streamhub.tv.ui.sample

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.streamhub.tv.focus.LocalTvFocus
import com.streamhub.tv.focus.TvKey
import com.streamhub.tv.focus.TvScreenKeys
import com.streamhub.tv.focus.TvSelect
import com.streamhub.tv.focus.rememberTvColumnState
import com.streamhub.tv.focus.rememberTvRowState
import com.streamhub.tv.focus.tvFocusGroup
import com.streamhub.tv.focus.tvLoading
import com.streamhub.tv.focus.tvRegion
import com.streamhub.tv.focus.tvRowColumn
import com.streamhub.tv.ui.components.PosterCardData
import com.streamhub.tv.ui.components.TvButton
import com.streamhub.tv.ui.components.TvButtonSize
import com.streamhub.tv.ui.components.TvButtonStyle
import com.streamhub.tv.ui.components.TvModal
import com.streamhub.tv.ui.components.TvModalDefaults
import com.streamhub.tv.ui.components.TvPosterCard
import com.streamhub.tv.ui.components.TvRail
import com.streamhub.tv.ui.components.TvSkeletonCard
import com.streamhub.tv.ui.theme.TvColors
import com.streamhub.tv.ui.theme.TvSpace
import com.streamhub.tv.ui.theme.TvText
import com.streamhub.tv.ui.theme.TvType

/** One rail of the sample: cards, still loading (skeletons), or failed (a collapsed row). */
data class SampleRail(
    val title: String,
    val group: String,
    val cards: List<PosterCardData> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * A Home-shaped screen built only from the foundation: a top bar of pill
 * buttons, a hero line, and a row column of rails with cards, skeletons and a
 * collapsed error row, plus a sign-out confirm modal (a focus scope). It is the
 * screenshot test's subject and, until the real Home lands, the Home
 * placeholder. Not a parity implementation of Home — see PARITY §3.2.
 */
@Composable
fun SampleHome(
    rails: List<SampleRail>,
    onOpen: (PosterCardData) -> Unit,
    modifier: Modifier = Modifier,
    sfxOn: Boolean = true,
    onSearch: () -> Unit = {},
    onToggleSfx: () -> Unit = {},
    onSignOut: () -> Unit = {},
    hero: String? = null,
) {
    val engine = LocalTvFocus.current
    var confirm by remember { mutableStateOf(false) }
    val column = rememberTvColumnState()
    val anyLoading = rails.any { it.loading }
    val firstCard = rails.firstOrNull { !it.loading && it.error == null && it.cards.isNotEmpty() }?.cards?.first()?.id

    // Back from deep in the rails returns to the first card with every rail at
    // rest; the next Back goes up the chain (home.rs:516).
    // One handler per screen (the engine holds a single slot): Back closes the
    // confirm modal, else it returns to the first card if the rails are not at rest.
    TvScreenKeys { press ->
        when {
            press.key != TvKey.Back -> false
            confirm -> {
                confirm = false
                engine.focusLater(TvSelect.action("signout"))
                true
            }
            else -> engine.backTo(TvSelect.inRegion("rows"))
        }
    }

    Box(modifier.fillMaxSize().then(if (anyLoading) Modifier.tvLoading() else Modifier)) {
        // Top bar.
        Row(
            Modifier
                .zIndex(3f)
                .fillMaxWidth()
                .padding(start = TvSpace.SafeX, end = TvSpace.SafeX, top = TvSpace.SafeY)
                .tvFocusGroup("topbar"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TvText("StreamHub", TvType.Brand)
            Spacer(Modifier.weight(1f))
            TvButton("搜尋", onClick = { onSearch() }, size = TvButtonSize.Chip, action = "search", focusId = "top:search")
            TvButton(if (sfxOn) "音效 開" else "音效 關", onClick = { onToggleSfx() }, size = TvButtonSize.Chip, action = "sfx", focusId = "top:sfx")
            TvButton("登出", onClick = { confirm = true }, style = TvButtonStyle.Text, action = "signout", focusId = "top:signout")
        }

        // Hero.
        Column(Modifier.padding(start = TvSpace.SafeX, top = 92.dp).widthIn(max = 560.dp)) {
            TvText(hero ?: "繼續觀看", TvType.Display, maxLines = 2)
            Spacer(Modifier.height(8.dp))
            TvText("按 OK 繼續播放", TvType.Meta, color = TvColors.Text2)
        }

        // The rows: a column moved by translateY, its rows faded by state.
        Box(Modifier.fillMaxSize().padding(top = 216.dp).clipToBounds()) {
            Column(Modifier.fillMaxWidth().tvRowColumn(column, anchor = 0.dp).tvRegion("rows")) {
                for (rail in rails) {
                    val row = rememberTvRowState(rail.group)
                    when {
                        rail.error != null -> TvRail(
                            title = rail.title, group = rail.group, row = row,
                            caption = rail.error, captionIsError = true, rowPaddingBottom = 3.dp,
                        ) {}
                        else -> TvRail(
                            title = rail.title,
                            group = rail.group,
                            row = row,
                            note = if (rail.loading) null else rail.cards.size.toString(),
                            rowPaddingBottom = 3.dp,
                            trackPadding = PaddingValues(start = TvSpace.SafeX, end = TvSpace.SafeX, top = 11.dp, bottom = 9.dp),
                        ) {
                            if (rail.loading) {
                                repeat(8) { TvSkeletonCard() }
                            } else {
                                for (card in rail.cards) {
                                    // Where focus lands when nothing is focused: the first card.
                                    TvPosterCard(card, onOpen = { onOpen(card) }, onPlay = { onOpen(card) }, autofocus = card.id == firstCard)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (confirm) {
            TvModal(title = "要登出嗎？", text = "登出後需要重新配對這台電視。") {
                TvButton("取消", onClick = { confirm = false; engine.focusLater(TvSelect.action("signout")) }, autofocus = true,
                    action = "signout-cancel", modifier = Modifier.widthIn(min = TvModalDefaults.ActionMinWidth))
                TvButton("登出", onClick = { confirm = false; onSignOut() }, style = TvButtonStyle.Danger,
                    action = "signout-confirm", modifier = Modifier.widthIn(min = TvModalDefaults.ActionMinWidth))
            }
        }
    }
}

/** Fixed data for previews and the screenshot test. */
object SampleData {
    val continueWatching = listOf(
        PosterCardData("c:1", "彈一場完美戀愛", "第 8 集 · 剩 12 分鐘", progress = 64.0),
        PosterCardData("c:2", "我們與惡的距離", "第 3 集", progress = 22.0, nextUp = true),
        PosterCardData("c:3", "想見你", "第 11 集", progress = 88.0),
        PosterCardData("c:4", "天橋上的魔術師", "已下架", unavailable = true),
        PosterCardData("c:5", "華燈初上", "第 2 季 · 第 5 集", progress = 40.0),
        PosterCardData("c:6", "茶金", "第 1 集", progress = 5.0),
        PosterCardData("c:7", "斯卡羅", "第 4 集", progress = 51.0),
        PosterCardData("c:8", "火神的眼淚", "第 9 集", progress = 76.0),
        PosterCardData("c:9", "模仿犯", "第 6 集", progress = 33.0),
    )
    val favorites = listOf(
        PosterCardData("f:1", "俗女養成記", "movieffm"),
        PosterCardData("f:2", "做工的人", "777tv"),
        PosterCardData("f:3", "台北女子圖鑑", "dramasq"),
        PosterCardData("f:4", "不夠善良的我們", "chinaq", unavailable = true, unavailableReason = "no-episodes"),
        PosterCardData("f:5", "誰是被害者", "movieffm"),
    )
    val rails = listOf(
        SampleRail("繼續觀看", "continue", continueWatching),
        SampleRail("我的收藏", "favorites", favorites),
        SampleRail("觀看紀錄", "history", loading = true),
        SampleRail("chinaq", "chinaq", error = "無法連線，稍後重試"),
    )
}
