package com.streamhub.tv.player

import com.streamhub.core.model.ItemDetail
import com.streamhub.core.model.ProgressUpdate
import com.streamhub.core.model.SeasonRef
import com.streamhub.core.model.WatchProgress
import com.streamhub.core.net.StreamHubApi
import com.streamhub.tv.nav.PlayTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Everything the player asks the server (`player/media.rs` and the calls in
 * `player/mod.rs`), behind an interface so the controller runs under test.
 * Failures are exceptions; an [IOException] means no answer (Tizen's
 * `is_offline`).
 */
interface PlayerData {
    /** `preferred_label`: the saved source label for this title, or null. Never throws. */
    suspend fun preferredLabel(target: PlayTarget): String?

    /** `stream_sources`: every source that passes its probe, as it passes. */
    fun sources(target: PlayTarget, preferred: String?): Flow<SourceLine>

    suspend fun putProgress(body: ProgressUpdate)

    /** `save_preference`. */
    suspend fun savePreference(target: PlayTarget, sourceLabel: String)

    /** `GET /me/progress?providerKey&itemUrl`, for the episode strip. */
    suspend fun progress(provider: String, itemUrl: String): List<WatchProgress>

    /** `GET /item?…&mediaType=tv` → `seasons`, for the next-season roll-over. */
    suspend fun seasons(target: PlayTarget): List<SeasonRef>

    suspend fun episodes(provider: String, sourceUrl: String): List<String>

    /** `server_reachable`: did the server give *any* HTTP answer to /health. */
    suspend fun serverReachable(): Boolean

    /** Tier 0, `/api/v1/manifest?target=…`. */
    fun manifestUrl(directUrl: String): String

    /** Tier 1: the line's proxyUrl on /api/v1, or `/api/v1/stream?target=…`. */
    fun relayUrl(line: SourceLine): String?
}

class ApiPlayerData(private val api: StreamHubApi, private val baseUrl: String) : PlayerData {

    override suspend fun preferredLabel(target: PlayTarget): String? = runCatching {
        api.sourcePreference(target.provider, target.title, PlayerRules.mediaType(target))
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    override fun sources(target: PlayTarget, preferred: String?): Flow<SourceLine> {
        val pref = preferred.orEmpty()
        if (PlayerRules.mediaType(target) == "tv") {
            return api.sources(target.provider, PlayerRules.episodeSourceUrl(target), target.episodeLabel, pref)
                .map(SourceLine::from)
        }
        // Movies: the raw streams from /item, then /check-sources — the web app's two calls.
        return flow {
            val item = api.item(target.provider, target.itemUrl, target.title, "movie")
            val streams = (item as? ItemDetail.Movie)?.streams.orEmpty()
            if (streams.isEmpty()) return@flow
            api.checkSources(target.provider, streams, pref).collect { emit(SourceLine.from(it)) }
        }
    }

    override suspend fun putProgress(body: ProgressUpdate) = api.putProgress(body)

    override suspend fun savePreference(target: PlayTarget, sourceLabel: String) {
        if (sourceLabel.isEmpty() || target.title.isEmpty()) return
        runCatching { api.rememberSourcePreference(target.provider, target.title, PlayerRules.mediaType(target), sourceLabel) }
    }

    override suspend fun progress(provider: String, itemUrl: String): List<WatchProgress> =
        api.progress(provider, itemUrl)

    override suspend fun seasons(target: PlayTarget): List<SeasonRef> =
        (api.item(target.provider, target.itemUrl, target.title, "tv") as? ItemDetail.Seasons)?.seasons.orEmpty()

    override suspend fun episodes(provider: String, sourceUrl: String): List<String> = api.episodes(provider, sourceUrl)

    override suspend fun serverReachable(): Boolean = try {
        api.health()
        true
    } catch (e: IOException) {
        false
    } catch (e: Exception) {
        // A 5xx, an unreadable body: the server answered, which is the question.
        if (e is kotlinx.coroutines.CancellationException) throw e
        true
    }

    override fun manifestUrl(directUrl: String): String = api.manifestUrl(directUrl)

    override fun relayUrl(line: SourceLine): String? {
        val proxy = line.proxyUrl.orEmpty()
        if (proxy.isEmpty()) return line.direct?.let(api::streamUrl)
        return PlayerRules.absoluteRelay(baseUrl, proxy, token = null)
    }
}
