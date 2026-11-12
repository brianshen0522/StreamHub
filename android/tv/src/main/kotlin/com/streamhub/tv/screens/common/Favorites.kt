package com.streamhub.tv.screens.common

import com.streamhub.core.model.NewFavorite
import com.streamhub.core.net.StreamHubApi
import com.streamhub.tv.ui.components.TvToastState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Port of Tizen's `FavoriteKey` (tizen/app/src/screens/common.rs). Absent fields are "". */
data class FavoriteKey(
    val provider: String,
    val itemUrl: String,
    val title: String,
    val poster: String = "",
    val mediaType: String = "",
    val seasonUrl: String = "",
    val episodeLabel: String = "",
)

/** "movie" / "tv", anything else "unknown" (Tizen `media_type`). */
fun favoriteMediaType(raw: String): String = when (raw) {
    "movie" -> "movie"
    "tv" -> "tv"
    else -> "unknown"
}

/**
 * The red key: un-favourite every row of this title if there is one,
 * otherwise favourite it. Returns whether it is a favourite now. Port of
 * Tizen's `toggle_favorite`.
 */
suspend fun toggleFavorite(api: StreamHubApi, key: FavoriteKey): Boolean {
    val existing = api.favorites().filter { it.providerKey == key.provider && it.itemUrl == key.itemUrl }
    if (existing.isNotEmpty()) {
        existing.forEach { api.removeFavorite(it.id) }
        return false
    }
    api.addFavorite(
        NewFavorite(
            providerKey = key.provider,
            mediaType = favoriteMediaType(key.mediaType),
            title = key.title,
            posterUrl = if (key.poster.startsWith("http")) key.poster else "",
            itemUrl = key.itemUrl,
            seasonUrl = key.seasonUrl,
            episodeLabel = key.episodeLabel,
        ),
    )
    return true
}

/** The red key on a card, with a toast for the outcome. Port of `favorite_toggle_with_toast`. */
fun toggleFavoriteWithToast(
    api: StreamHubApi,
    scope: CoroutineScope,
    toast: TvToastState,
    key: FavoriteKey,
    after: () -> Unit = {},
) {
    scope.launch {
        runCatching { toggleFavorite(api, key) }
            .onSuccess { now -> toast.show(if (now) "已加入收藏：${key.title}" else "已取消收藏：${key.title}") }
            .onFailure { toast.show(friendlyError(it)) }
        after()
    }
}
