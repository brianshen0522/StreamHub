package com.streamhub.core.download

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Everything a download is, in one file beside its media.
 *
 * The segment list is captured once, when the download begins, and the record
 * carries it from then on. Resuming re-reads *this* rather than re-fetching the
 * playlist, because these providers rebuild their playlists — the same episode
 * asked for twice can answer with different segment URLs, and a resume stitched
 * from a fresh fetch would append the second half of a different encode onto
 * the first half of this one.
 *
 * Progress is two numbers: how many segments are wholly on disk, and how many
 * bytes those add up to. A crash mid-segment leaves a torn tail past that
 * offset; resuming truncates back to it, so the invariant "the file is exactly
 * the completed segments" survives being killed at any moment.
 */
@Serializable
data class DownloadRecord(
    val id: String,
    val title: String,
    val posterUrl: String? = null,
    val providerKey: String,
    val mediaType: String = "unknown",
    val itemUrl: String,
    val seasonUrl: String? = null,
    val seasonLabel: String? = null,
    val episodeLabel: String? = null,
    val sourceLabel: String,
    val createdAtMs: Long,
    val segments: List<HlsSegment>,
    val totalDurationSeconds: Double,
    val completedSegments: Int = 0,
    val bytesWritten: Long = 0,
    /** Set once every segment is on disk; the row stops being resumable and starts being playable. */
    val finished: Boolean = false,
    /** The last failure, kept so the row can say why it stopped. Cleared on retry. */
    val error: String? = null,
) {
    val progress: Float
        get() = if (segments.isEmpty()) 0f else completedSegments.toFloat() / segments.size
}

/** The record and its media, laid out as `<root>/<id>/…`. */
class DownloadPaths(root: File, id: String) {
    val directory: File = File(root, id)
    val record: File = File(directory, "download.json")
    val media: File = File(directory, "media.ts")
}

object DownloadStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun read(file: File): DownloadRecord? = try {
        json.decodeFromString<DownloadRecord>(file.readText())
    } catch (_: Exception) {
        null
    }

    /**
     * Written to a sibling and renamed into place, so a crash mid-write leaves
     * the previous record rather than half of a new one. The record is the
     * resume point; losing it costs the whole download.
     */
    fun write(file: File, record: DownloadRecord) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.encodeToString(DownloadRecord.serializer(), record))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    fun list(root: File): List<DownloadRecord> =
        root.listFiles()?.mapNotNull { dir -> read(File(dir, "download.json")) }
            ?.sortedByDescending { it.createdAtMs }
            ?: emptyList()
}
