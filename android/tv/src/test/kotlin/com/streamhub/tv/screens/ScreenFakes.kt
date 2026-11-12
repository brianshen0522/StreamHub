package com.streamhub.tv.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.dp
import com.streamhub.core.ClientKind
import com.streamhub.core.model.Favorite
import com.streamhub.core.model.ItemDetail
import com.streamhub.core.model.NewFavorite
import com.streamhub.core.model.ProviderInfo
import com.streamhub.core.model.ProviderResults
import com.streamhub.core.model.SearchItem
import com.streamhub.core.model.WatchProgress
import com.streamhub.core.net.InMemorySessionStore
import com.streamhub.core.net.StreamHubApi
import com.streamhub.core.net.StreamHubException
import com.streamhub.tv.TvShell
import com.streamhub.tv.focus.TvFocusEngine
import com.streamhub.tv.nav.TvNav
import com.streamhub.tv.nav.TvRoute
import com.streamhub.tv.notoSansCjk
import com.streamhub.tv.screens.common.RecentStore
import com.streamhub.tv.screens.common.TvCatalog
import com.streamhub.tv.screens.detail.DetailScreen
import com.streamhub.tv.screens.search.SearchScreen
import com.streamhub.tv.sfx.SfxControl
import com.streamhub.tv.ui.components.TvButton
import com.streamhub.tv.ui.theme.LocalTvFontFamily
import kotlinx.coroutines.CompletableDeferred

/** A catalogue answered from memory. Searches wait on [gate] so a test decides when each provider answers. */
class FakeCatalog : TvCatalog {
    var providerList = listOf(
        ProviderInfo("movieffm", "MovieFFM"),
        ProviderInfo("777tv", "777TV"),
        ProviderInfo("dramasq", "DramasQ"),
        ProviderInfo("chinaq", "ChinaQ"),
    )
    private val gates = HashMap<String, CompletableDeferred<ProviderResults>>()
    val items = HashMap<String, ItemDetail>()
    val itemErrors = HashMap<String, Exception>()
    val seasonEpisodes = HashMap<String, List<String>>()
    var progressRows: List<WatchProgress> = emptyList()
    var favoriteRows: MutableList<Favorite> = mutableListOf()
    var poster: String = ""
    val itemCalls = ArrayList<String>()

    fun gate(provider: String): CompletableDeferred<ProviderResults> = gates.getOrPut(provider) { CompletableDeferred() }

    fun answer(provider: String, items: List<SearchItem>) {
        gate(provider).complete(ProviderResults(provider, items))
    }

    fun fail(provider: String, error: String) {
        gate(provider).complete(ProviderResults(provider, error = error))
    }

    override suspend fun providers(all: Boolean) = providerList
    override suspend fun search(query: String, provider: String) = gate(provider).await()
    override suspend fun item(provider: String, url: String, title: String, mediaType: String, posterUrl: String): ItemDetail {
        itemCalls.add(url)
        itemErrors[url]?.let { throw it }
        return items[url] ?: throw StreamHubException(404, "Not found")
    }
    override suspend fun episodes(provider: String, sourceUrl: String) = seasonEpisodes[sourceUrl] ?: emptyList()
    override suspend fun progress(providerKey: String, itemUrl: String) = progressRows.filter { it.itemUrl == itemUrl }
    override suspend fun favorites(): List<Favorite> = favoriteRows.toList()
    override suspend fun addFavorite(favorite: NewFavorite): Favorite {
        val row = Favorite(
            id = "f${favoriteRows.size + 1}", providerKey = favorite.providerKey, title = favorite.title,
            itemUrl = favorite.itemUrl, seasonUrl = favorite.seasonUrl, episodeLabel = favorite.episodeLabel,
        )
        favoriteRows.add(row)
        return row
    }
    override suspend fun removeFavorite(id: String) {
        favoriteRows.removeAll { it.id == id }
    }
    override fun posterUrl(target: String) = poster.ifEmpty { target }
}

class MemoryRecents(var raw: String? = null) : RecentStore {
    override fun load() = raw
    override fun save(raw: String) {
        this.raw = raw
    }
}

/** An API nobody answers: only the shared helpers' (provider names, red key) calls reach it, and fail. */
fun deadApi() = StreamHubApi("http://127.0.0.1:9", InMemorySessionStore(), ClientKind.TV)

fun searchItems(provider: String, n: Int, prefix: String = "瑞克和莫蒂"): List<SearchItem> =
    (1..n).map { i ->
        SearchItem(
            provider = provider,
            title = if (i == 1) "$prefix：日本動畫版" else "$prefix 第${i}季",
            url = "https://$provider.example/v/$i",
            posterUrl = "https://img.example/$provider/$i.jpg",
            mediaType = if (i % 5 == 0) "movie" else "tv",
            year = if (i % 3 == 0) "${2010 + i}" else null,
        )
    }

/** A 400×600 bitmap standing in for a poster (no network in tests). */
fun fakePosterBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint().apply {
            shader = LinearGradient(0f, 0f, 400f, 600f, intArrayOf(0xFF0E3B4F.toInt(), 0xFF2E7D32.toInt(), 0xFF101820.toInt()), null, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, 400f, 600f, p)
        c.drawCircle(220f, 330f, 140f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7CB342.toInt() })
        c.drawCircle(200f, 300f, 60f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4FC3F7.toInt() })
        return bmp
}

@OptIn(ExperimentalCoilApi::class)
/** TvApp's routing over the fakes: Home is one button, the player a blank screen. */
@Composable
fun ScreensHost(
    engine: TvFocusEngine,
    nav: TvNav,
    catalog: FakeCatalog,
    recents: RecentStore,
    api: StreamHubApi? = null,
    posters: Boolean = false,
) {
    // Coil cannot load under Robolectric; in inspection mode its preview
    // handler answers every request with the stand-in poster instead.
    val preview = AsyncImagePreviewHandler { fakePosterBitmap().asImage() }
    CompositionLocalProvider(
        LocalTvFontFamily provides notoSansCjk(),
        LocalInspectionMode provides posters,
        LocalAsyncImagePreviewHandler provides preview,
    ) {
        TvShell(engine, nav, SfxControl.Silent()) {
            val entry = nav.top
            key(entry.id) {
                when (val r = entry.route) {
                    TvRoute.Home -> Box(Modifier.fillMaxSize().padding(48.dp)) {
                        TvButton("搜尋", onClick = { nav.push(TvRoute.Search) }, action = "search", autofocus = true)
                    }
                    TvRoute.Search -> SearchScreen(catalog, api ?: deadApi(), recents)
                    is TvRoute.Detail -> DetailScreen(r.target, catalog, api)
                    is TvRoute.Player -> Box(Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** Key presses and clock steps against an engine under a paused compose clock. */
class Remote(private val compose: ComposeContentTestRule, private val engine: TvFocusEngine) {
    fun settle(ms: Long = 400) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    fun press(code: Int, wait: Long = 300) {
        compose.runOnIdle {
            engine.keyDown(code, false)
            engine.keyUp(code)
        }
        settle(wait)
    }

    fun down() = press(KeyEvent.KEYCODE_DPAD_DOWN)
    fun up() = press(KeyEvent.KEYCODE_DPAD_UP)
    fun right() = press(KeyEvent.KEYCODE_DPAD_RIGHT, 200)
    fun left() = press(KeyEvent.KEYCODE_DPAD_LEFT, 200)
    fun ok() = press(KeyEvent.KEYCODE_DPAD_CENTER, 600)
    fun back() = press(KeyEvent.KEYCODE_BACK, 600)

    val focusedId: String? get() = compose.runOnIdle { engine.focused?.focusId ?: engine.focused?.action }
}
