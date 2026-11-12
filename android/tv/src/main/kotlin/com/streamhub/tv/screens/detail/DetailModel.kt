package com.streamhub.tv.screens.detail

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.streamhub.core.model.ContinueItem
import com.streamhub.core.model.Favorite
import com.streamhub.core.model.ItemDetail
import com.streamhub.core.model.NewFavorite
import com.streamhub.core.model.SeasonRef
import com.streamhub.core.model.WatchHistoryEntry
import com.streamhub.core.model.WatchProgress
import com.streamhub.core.net.StreamHubException
import com.streamhub.core.resume.EntryPoint
import com.streamhub.core.resume.EntryRules
import com.streamhub.core.resume.EpisodePick
import com.streamhub.core.resume.ResumeRules
import com.streamhub.tv.nav.DetailTarget
import com.streamhub.tv.nav.PlayTarget
import com.streamhub.tv.screens.common.Load
import com.streamhub.tv.screens.common.TvCatalog
import com.streamhub.tv.screens.common.favoriteMediaType
import com.streamhub.tv.screens.common.friendlyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

sealed interface DetailPhase {
    data object Loading : DetailPhase
    data object Ready : DetailPhase
    /** `/api/item` answered 404: the provider dropped the title. */
    data object Gone : DetailPhase
    data class Failed(val message: String) : DetailPhase
}

enum class DetailKind { Movie, Series }

/**
 * Detail's state and loads (`detail.rs:48-366`). One per mount: returning to
 * Detail re-mounts it, which reloads item, progress and favourites, as Tizen.
 */
@Stable
class DetailModel(val target: DetailTarget, private val catalog: TvCatalog, private val scope: CoroutineScope) {
    var phase: DetailPhase by mutableStateOf(DetailPhase.Loading)
        private set
    var kind by mutableStateOf(DetailKind.Series)
        private set
    var title by mutableStateOf(target.title)
        private set
    var poster by mutableStateOf(target.posterUrl)
        private set
    var media by mutableStateOf(target.mediaType)
        private set
    var description by mutableStateOf("")
        private set
    var detailUrl by mutableStateOf(target.url)
        private set
    var seasons by mutableStateOf<List<SeasonRef>>(emptyList())
        private set
    var season by mutableStateOf<SeasonRef?>(null)
        private set
    /** `seasonUrl` from the item itself (single-page series), the progress key. */
    var itemSeasonUrl by mutableStateOf<String?>(null)
        private set
    var episodes by mutableStateOf<Load<List<String>>>(Load.Loading)
        private set
    var episode by mutableStateOf<String?>(null)
    var progress by mutableStateOf<Map<String, WatchProgress>>(emptyMap())
        private set
    var favorites by mutableStateOf<List<Favorite>>(emptyList())
        private set
    var favBusy by mutableStateOf(false)
        private set
    var favError by mutableStateOf<String?>(null)
        private set

    /** Bumped by every season request; an answer for an older one is dropped. */
    private var seasonSeq = 0L

    /** Play can play: a movie, or a series with an episode chosen. */
    val ready: Boolean get() = phase == DetailPhase.Ready && (kind == DetailKind.Movie || episode != null)

    val isFavorite: Boolean get() = favorites.isNotEmpty()

    /** The season URL progress and favourites are keyed by. */
    fun seasonKey(): String? = (season?.url ?: itemSeasonUrl)?.takeIf { it.isNotEmpty() }

    fun progressFor(ep: String?): WatchProgress? = progress[ResumeRules.progressKey(seasonKey(), ep)]

    fun positionFor(ep: String?): Int = ResumeRules.resumePositionSeconds(progressFor(ep))

    private val entry get() = EntryPoint(target.seasonUrl, target.episodeLabel, target.fromHistory)

    fun load() {
        scope.launch { loadNow() }
    }

    /** `load` (`detail.rs:151-252`). */
    suspend fun loadNow() {
        phase = DetailPhase.Loading
        // Progress first in meaning: it decides which season and episode open.
        val (progressResult, itemResult, favResult) = coroutineScope {
            val p = async { attempt { catalog.progress(target.provider, target.url) } }
            val i = async {
                attempt {
                    catalog.item(target.provider, target.url, target.title, target.mediaType.ifEmpty { "unknown" }, target.posterUrl)
                }
            }
            val f = async { attempt { catalog.favorites() } }
            Triple(p.await(), i.await(), f.await())
        }
        progress = ResumeRules.progressMap(progressResult.getOrNull().orEmpty())
        favResult.getOrNull()?.let { rows ->
            favorites = rows.filter { it.providerKey == target.provider && it.itemUrl == target.url }
        }
        val item = itemResult.getOrElse { e ->
            phase = if ((e as? StreamHubException)?.status == 404) DetailPhase.Gone else DetailPhase.Failed(friendlyError(e))
            return
        }
        item.title.takeIf { it.isNotEmpty() }?.let { title = it }
        item.posterUrl?.takeIf { it.isNotEmpty() }?.let { poster = it }
        item.mediaType?.takeIf { it.isNotEmpty() }?.let { media = it }
        description = item.description.orEmpty()
        val itemSeason = (item as? ItemDetail.Episodes)?.seasonUrl
        detailUrl = item.detailUrl?.takeIf { it.isNotEmpty() } ?: itemSeason?.takeIf { it.isNotEmpty() } ?: target.url

        when (item) {
            is ItemDetail.Seasons -> {
                kind = DetailKind.Series
                val chosen = EntryRules.entrySeason(item.seasons, progress, entry)
                seasons = item.seasons
                phase = DetailPhase.Ready
                (chosen ?: item.seasons.firstOrNull())?.let { openSeasonNow(it, initial = true) }
            }
            is ItemDetail.Episodes -> {
                kind = DetailKind.Series
                itemSeasonUrl = itemSeason
                val eps = item.episodes
                val pick = EntryRules.entryEpisode(eps, itemSeason, progress, entry)
                episodes = Load.Ready(eps)
                episode = (pick as? EpisodePick.Episode)?.label ?: eps.firstOrNull()
                phase = DetailPhase.Ready
            }
            is ItemDetail.Movie -> {
                kind = DetailKind.Movie
                episodes = Load.Ready(emptyList())
                phase = DetailPhase.Ready
            }
        }
    }

    /** A season picked on the rail (`open_season(…, false)`). */
    fun pickSeason(s: SeasonRef) {
        if (season?.url == s.url) return
        scope.launch { openSeasonNow(s, initial = false) }
    }

    /**
     * Load a season's episodes and pick the one to open (`open_season`,
     * `detail.rs:96-149`). [initial] applies the entry point and rolls a
     * finished season over to the next; a season picked on the rail resumes
     * within itself.
     */
    suspend fun openSeasonNow(s: SeasonRef, initial: Boolean) {
        seasonSeq += 1
        val mine = seasonSeq
        season = s
        episodes = Load.Loading
        episode = null
        val result = attempt { catalog.episodes(target.provider, s.url) }
        // Another season was picked while this one loaded: the newer request owns the rail.
        if (seasonSeq != mine) return
        val eps = result.getOrElse {
            episodes = Load.Failed(friendlyError(it))
            return
        }
        val e = if (initial) entry else EntryPoint()
        when (val pick = EntryRules.entryEpisode(eps, s.url, progress, e)) {
            is EpisodePick.Episode -> {
                episodes = Load.Ready(eps)
                episode = pick.label
            }
            EpisodePick.Empty -> episodes = Load.Ready(eps)
            EpisodePick.SeasonFinished -> {
                val index = seasons.indexOfFirst { it.url == s.url }
                val next = if (index >= 0) seasons.getOrNull(index + 1) else null
                when {
                    // Finished this season: open the next one at its start.
                    initial && next != null -> openSeasonNow(next, initial = false)
                    // Everything watched: back to the very beginning.
                    initial && index != 0 && seasons.isNotEmpty() -> openSeasonNow(seasons[0], initial = false)
                    else -> {
                        episode = eps.firstOrNull()
                        episodes = Load.Ready(eps)
                    }
                }
            }
        }
    }

    /**
     * What Play opens (`play`, `detail.rs:279-314`), or null when there is
     * nothing to play yet.
     */
    fun playTarget(ep: String?, fromStart: Boolean): PlayTarget? {
        val isMovie = kind == DetailKind.Movie
        if (!isMovie && ep == null) return null
        val seasonUrl = seasonKey().orEmpty()
        return PlayTarget(
            provider = target.provider,
            itemUrl = target.url,
            seasonUrl = seasonUrl,
            title = title,
            posterUrl = poster,
            mediaType = if (isMovie) "movie" else "tv",
            seasonLabel = seasons.firstOrNull { it.url == seasonUrl }?.label.orEmpty(),
            episodeLabel = ep.orEmpty(),
            episodes = (episodes as? Load.Ready)?.value.orEmpty(),
            source = null,
            startSeconds = if (fromStart) 0.0 else positionFor(ep).toDouble(),
            fromCast = false,
        )
    }

    /** The heart (`toggle_favorite`, `detail.rs:316-366`). */
    fun toggleFavorite() {
        if (favBusy) return
        favBusy = true
        favError = null
        val existing = favorites
        scope.launch {
            val result: Result<List<Favorite>> = if (existing.isEmpty()) {
                // Keyed (providerKey, itemUrl, seasonUrl, episodeLabel), absent as "".
                attempt {
                    listOf(
                        catalog.addFavorite(
                            NewFavorite(
                                providerKey = target.provider,
                                mediaType = favoriteMediaType(media),
                                title = title,
                                posterUrl = if (poster.startsWith("http")) poster else "",
                                itemUrl = target.url,
                                detailUrl = detailUrl,
                                seasonUrl = seasonKey().orEmpty(),
                                seasonLabel = season?.label.orEmpty(),
                                episodeLabel = episode.orEmpty(),
                            ),
                        ),
                    )
                }
            } else {
                // The heart stands for the title: every favourite row of it goes.
                var failed: Throwable? = null
                for (row in existing) {
                    attempt { catalog.removeFavorite(row.id) }.onFailure { failed = it }
                }
                failed?.let { Result.failure(it) } ?: Result.success(emptyList())
            }
            favBusy = false
            result.onSuccess { favorites = it }.onFailure { favError = friendlyError(it) }
        }
    }

    /** The 已下架 / 已無集數 badge for the library row this was opened from. */
    val badge: String?
        get() {
            val (unavailable, reason) = when (val r = target.row) {
                is Favorite -> r.isUnavailable to r.unavailableReason
                is ContinueItem -> r.isUnavailable to r.unavailableReason
                is WatchHistoryEntry -> r.isUnavailable to r.unavailableReason
                else -> false to null
            }
            if (!unavailable) return null
            return if (reason == "no-episodes") "已無集數" else "已下架"
        }
}

/** [runCatching] that lets cancellation through. */
private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
