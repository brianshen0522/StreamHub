package com.streamhub.tv.screens.common

import android.content.Context
import com.streamhub.core.model.Favorite
import com.streamhub.core.model.ItemDetail
import com.streamhub.core.model.NewFavorite
import com.streamhub.core.model.ProviderInfo
import com.streamhub.core.model.ProviderResults
import com.streamhub.core.model.WatchProgress
import com.streamhub.core.net.StreamHubApi

/**
 * What Search and Detail read and write, as one seam: [ApiCatalog] on the
 * device, a fake in the screenshot and focus tests.
 */
interface TvCatalog {
    /** `/me/providers` (allowed ones; [all] adds the rest, for display names). */
    suspend fun providers(all: Boolean = false): List<ProviderInfo>
    /** `/search?q&provider=<key>`: that provider's slice (`results[0]`). */
    suspend fun search(query: String, provider: String): ProviderResults
    suspend fun item(provider: String, url: String, title: String, mediaType: String, posterUrl: String): ItemDetail
    suspend fun episodes(provider: String, sourceUrl: String): List<String>
    suspend fun progress(providerKey: String, itemUrl: String): List<WatchProgress>
    suspend fun favorites(): List<Favorite>
    suspend fun addFavorite(favorite: NewFavorite): Favorite
    suspend fun removeFavorite(id: String)
    /** `/api/poster?target=` for an image (behind auth). */
    fun posterUrl(target: String): String
}

class ApiCatalog(private val api: StreamHubApi) : TvCatalog {
    override suspend fun providers(all: Boolean) = api.providers(includeUnavailable = all)
    override suspend fun search(query: String, provider: String): ProviderResults =
        api.search(query, setOf(provider)).results.firstOrNull() ?: ProviderResults(provider)
    override suspend fun item(provider: String, url: String, title: String, mediaType: String, posterUrl: String) =
        api.item(provider, url, title, mediaType, posterUrl)
    override suspend fun episodes(provider: String, sourceUrl: String) = api.episodes(provider, sourceUrl)
    override suspend fun progress(providerKey: String, itemUrl: String) = api.progress(providerKey, itemUrl)
    override suspend fun favorites() = api.favorites()
    override suspend fun addFavorite(favorite: NewFavorite) = api.addFavorite(favorite)
    override suspend fun removeFavorite(id: String) {
        api.removeFavorite(id)
    }
    override fun posterUrl(target: String) = api.posterUrl(target)
}

/** Where recent searches live (`localStorage "streamhub.tizen.recentSearches"`, a JSON array). */
interface RecentStore {
    fun load(): String?
    fun save(raw: String)
}

class PrefsRecentStore(context: Context) : RecentStore {
    private val prefs = context.applicationContext.getSharedPreferences("streamhub", Context.MODE_PRIVATE)
    override fun load(): String? = prefs.getString(KEY, null)
    override fun save(raw: String) {
        prefs.edit().putString(KEY, raw).apply()
    }
    private companion object {
        const val KEY = "streamhub.tizen.recentSearches"
    }
}

/** A load state (`Load<T>`, `common.rs:60-65`). */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

/** `1:05:09` / `5:09` (`clock`, `common.rs:50-58`). */
fun clock(seconds: Double): String {
    val t = seconds.coerceAtLeast(0.0).toLong()
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
