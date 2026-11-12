package com.streamhub.tv.screens.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.streamhub.core.model.SearchItem
import com.streamhub.core.net.StreamHubException
import com.streamhub.tv.screens.common.Load
import com.streamhub.tv.screens.common.RecentStore
import com.streamhub.tv.screens.common.TvCatalog
import com.streamhub.tv.screens.common.friendlyError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** One provider's rail (`Group`, `search.rs:36-41`). */
data class SearchGroup(val key: String, val name: String, val state: Load<List<SearchItem>>)

/**
 * Search's state, which outlives the screen: the router remounts Search on
 * Back from Detail, and Tizen keeps the query and results in globals for as
 * long as Search is in the stack (`search.rs:43-86`). Requests run on the
 * session's own scope for the same reason — a search still answering when a
 * card was opened lands while Detail is up and is there on the way back.
 */
object SearchSession {
    /** The server gives each site 8 s and movieffm makes two rounds; past this the site is not coming back. */
    const val DEADLINE_MS = 20_000L
    const val RECENT_MAX = 8
    /** Cards per page of a result rail, and how near the end focus may get before the next page is added. */
    const val PAGE = 30
    const val PREFETCH = 6

    var query by mutableStateOf(TextFieldValue(""))
    var groups by mutableStateOf<List<SearchGroup>>(emptyList())
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    private var seq = 0L
    private var pending = false
    /** How many cards each provider's rail had grown to, so Back from Detail can land past the first page. */
    private val shown = HashMap<String, Int>()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(String.serializer())

    fun shownFor(provider: String): Int = maxOf(shown[provider] ?: PAGE, PAGE)

    fun rememberShown(provider: String, n: Int) {
        shown[provider] = n
    }

    /**
     * Open Search with [title] typed in and searched (the way out of a title a
     * provider dropped). Call before pushing `TvRoute.Search`.
     */
    fun prefill(title: String) {
        reset()
        query = TextFieldValue(title, TextRange(title.length))
        pending = true
    }

    /** Take the pending prefill search, once (`PENDING`). */
    fun takePending(): Boolean = pending.also { pending = false }

    /** Everything dropped (Search left the stack for good). */
    fun reset() {
        job.cancelChildren()
        shown.clear()
        seq += 1
        query = TextFieldValue("")
        groups = emptyList()
        notice = null
        pending = false
    }

    fun recent(store: RecentStore): List<String> =
        runCatching { store.load()?.let { json.decodeFromString(listSerializer, it) } }.getOrNull().orEmpty().take(RECENT_MAX)

    private fun remember(store: RecentStore, q: String) {
        val list = (listOf(q) + recent(store).filter { it != q }).take(RECENT_MAX)
        runCatching { store.save(json.encodeToString(listSerializer, list)) }
    }

    private fun setGroup(mine: Long, key: String, state: Load<List<SearchItem>>) {
        if (seq != mine) return
        groups = groups.map { if (it.key == key) it.copy(state = state) else it }
    }

    /** `run_search` (`search.rs:122-187`). */
    fun run(q: String, catalog: TvCatalog, store: RecentStore) {
        val text = q.trim()
        if (text.isEmpty()) return
        seq += 1
        val mine = seq
        remember(store, text)
        notice = "搜尋中…"
        groups = emptyList()
        scope.launch {
            val providers = try {
                catalog.providers()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (seq == mine) notice = friendlyError(e)
                return@launch
            }
            if (seq != mine) return@launch
            val list = providers
                .filter { it.allowed }
                .map { SearchGroup(it.key, it.name.ifEmpty { it.key }, Load.Loading) }
                .filter { it.key.isNotEmpty() }
            if (list.isEmpty()) {
                notice = "這個帳號沒有可搜尋的來源"
                return@launch
            }
            notice = null
            groups = list
            for (group in list) {
                scope.launch {
                    val state: Load<List<SearchItem>> = try {
                        val r = withTimeout(DEADLINE_MS) { catalog.search(text, group.key) }
                        val err = r.error?.takeIf { it.isNotEmpty() }
                        // The provider's own failure, in the server's words.
                        if (err != null) Load.Failed(friendlyError(StreamHubException(502, err))) else Load.Ready(r.items)
                    } catch (e: TimeoutCancellationException) {
                        Load.Failed("搜尋逾時")
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Load.Failed(friendlyError(e))
                    }
                    setGroup(mine, group.key, state)
                }
            }
        }
    }
}
