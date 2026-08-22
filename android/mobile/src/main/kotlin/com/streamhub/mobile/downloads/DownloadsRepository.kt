package com.streamhub.mobile.downloads

import android.content.Context
import com.streamhub.core.download.DownloadPaths
import com.streamhub.core.download.DownloadRecord
import com.streamhub.core.download.DownloadStore
import com.streamhub.core.download.HlsPlaylistParser
import com.streamhub.core.download.SegmentDownloader
import com.streamhub.core.net.StreamHubApi
import com.streamhub.mobile.PlaybackRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Everything the app knows about downloads: what is on disk, what is moving,
 * and how to start, stop and continue each one.
 *
 * Stopping is cheap by design. The engine records progress after every segment,
 * so pause, process death and reboot all leave the same thing behind — a record
 * that resumes from the next segment. "Cancel at 80% and start again" continues
 * from 80%; that behaviour is what this whole feature was asked to have, and it
 * is asserted by the core tests rather than promised here.
 */
class DownloadsRepository(
    context: Context,
    private val api: StreamHubApi,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val onActiveChanged: (Int) -> Unit = {},
) {

    /**
     * App-private external storage: no permission to ask for, cleared with the
     * app, and on most phones far roomier than internal storage. Internal is
     * the fallback for the rare device without any.
     */
    private val root: File =
        (context.getExternalFilesDir(null) ?: context.filesDir).resolve("downloads").apply { mkdirs() }

    /**
     * Segments and keys travel bare. The cleaned manifest points them at the
     * CDN's own hosts, where a bearer token means nothing and does not belong —
     * only the manifest itself comes from our server and needs the session.
     */
    private val segmentClient = OkHttpClient()

    private val jobs = mutableMapOf<String, Job>()

    private val _downloads = MutableStateFlow(DownloadStore.list(root))
    val downloads: StateFlow<List<DownloadRecord>> = _downloads.asStateFlow()

    private val _active = MutableStateFlow<Set<String>>(emptySet())
    val active: StateFlow<Set<String>> = _active.asStateFlow()

    fun mediaFile(id: String): File = DownloadPaths(root, id).media

    fun refresh() {
        _downloads.value = DownloadStore.list(root)
    }

    /**
     * Starts a download for the episode-and-source the player would have used —
     * or continues it, if one with the same identity is already on disk.
     */
    suspend fun start(request: PlaybackRequest): String {
        val id = idFor(request)
        val paths = DownloadPaths(root, id)

        if (DownloadStore.read(paths.record) == null) {
            val playlist = fetchCleanedPlaylist(request.directUrl)
            SegmentDownloader.create(
                root,
                DownloadRecord(
                    id = id,
                    title = request.title,
                    posterUrl = request.posterUrl,
                    providerKey = request.providerKey,
                    mediaType = request.mediaType,
                    itemUrl = request.itemUrl,
                    seasonUrl = request.seasonUrl,
                    seasonLabel = request.seasonLabel,
                    episodeLabel = request.episodeLabel,
                    sourceLabel = request.sourceLabel,
                    createdAtMs = System.currentTimeMillis(),
                    segments = playlist.segments,
                    totalDurationSeconds = playlist.totalDurationSeconds,
                ),
            )
            refresh()
        }

        resume(id)
        return id
    }

    /** Continue a download that is on disk but not moving. */
    fun resume(id: String) {
        if (jobs[id]?.isActive == true) return
        val paths = DownloadPaths(root, id)
        if (DownloadStore.read(paths.record)?.finished == true) return

        markActive(id, true)
        jobs[id] = scope.launch {
            try {
                SegmentDownloader(segmentClient, paths, onProgress = ::publish).run()
            } catch (failure: Exception) {
                if (failure !is kotlinx.coroutines.CancellationException) {
                    // The record keeps the reason, so the row can say what
                    // stopped it instead of silently sitting still.
                    DownloadStore.read(paths.record)?.let { record ->
                        DownloadStore.write(paths.record, record.copy(error = failure.message ?: "failed"))
                    }
                }
                throw failure
            } finally {
                markActive(id, false)
                refresh()
            }
        }
    }

    /** Stop moving, keep everything fetched. The opposite of [delete]. */
    fun pause(id: String) {
        jobs.remove(id)?.cancel()
    }

    fun delete(id: String) {
        jobs.remove(id)?.cancel()
        DownloadPaths(root, id).directory.deleteRecursively()
        refresh()
    }

    private fun publish(record: DownloadRecord) {
        _downloads.value = _downloads.value.map { if (it.id == record.id) record else it }
            .ifEmpty { listOf(record) }
    }

    private fun markActive(id: String, active: Boolean) {
        _active.value = if (active) _active.value + id else _active.value - id
        onActiveChanged(_active.value.size)
    }

    /**
     * The manifest endpoint serves the playlist with ads already cut and every
     * segment as an absolute CDN URL — the same thing the player streams, so a
     * download is ad-free by the same rules. A master answers with variants
     * pointed back at the endpoint; the first is what a player would pick.
     */
    private suspend fun fetchCleanedPlaylist(directUrl: String) = withContext(Dispatchers.IO) {
        var url = api.manifestUrl(directUrl)
        var text = fetchText(url)
        if (HlsPlaylistParser.isMaster(text)) {
            val variant = HlsPlaylistParser.variantUris(text, url).firstOrNull()
                ?: throw IllegalStateException("Master playlist listed no variants.")
            url = variant
            text = fetchText(url)
        }
        val playlist = HlsPlaylistParser.parse(text, url)
        if (playlist.segments.isEmpty()) throw IllegalStateException("Playlist contained no segments.")
        playlist
    }

    private fun fetchText(url: String): String {
        api.authenticatedClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code} for the playlist")
            return response.body.string()
        }
    }

    /**
     * One identity per episode-and-source, so downloading the same thing twice
     * continues the first attempt instead of standing beside it — which is the
     * resume-after-cancel behaviour, arrived at from the other direction.
     */
    private fun idFor(request: PlaybackRequest): String {
        val seed = listOf(
            request.providerKey, request.itemUrl, request.seasonUrl ?: "",
            request.episodeLabel ?: "", request.sourceLabel,
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-1").digest(seed.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)
    }
}
