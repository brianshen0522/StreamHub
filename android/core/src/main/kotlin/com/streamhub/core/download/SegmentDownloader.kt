package com.streamhub.core.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

/**
 * Pulls a recorded download's segments onto disk, from wherever it stopped.
 *
 * Cancellation is the designed-for case, not the exceptional one: the record is
 * rewritten after every completed segment, so stopping — a tap, the app dying,
 * the phone rebooting — costs at most the segment in flight. Starting again
 * truncates the media file back to the last completed byte and carries on from
 * the next segment; nothing already fetched is fetched twice.
 *
 * Segments encrypted with AES-128 are decrypted here and stored plain, the way
 * the web downloader stores them, so the result is one ordinary file any player
 * can open with no keys to keep alongside it.
 */
class SegmentDownloader(
    private val client: OkHttpClient,
    private val paths: DownloadPaths,
    private val onProgress: (DownloadRecord) -> Unit = {},
) {

    /** Keys are tiny and shared by whole stretches of segments; one fetch each. */
    private val keys = mutableMapOf<String, ByteArray>()

    /**
     * Runs until the download is complete or the coroutine is cancelled.
     * Returns the record as it stands when this call is done with it.
     */
    suspend fun run(): DownloadRecord = withContext(Dispatchers.IO) {
        var record = DownloadStore.read(paths.record)
            ?: throw IllegalStateException("no download record at ${paths.record}")
        if (record.finished) return@withContext record

        record = record.copy(error = null)

        // Heal a torn tail: the file is trusted only up to the recorded offset.
        RandomAccessFile(paths.media, "rw").use { file ->
            if (file.length() != record.bytesWritten) file.setLength(record.bytesWritten)

            file.seek(record.bytesWritten)
            var completed = record.completedSegments
            var bytes = record.bytesWritten

            while (completed < record.segments.size) {
                coroutineContext.ensureActive()
                val segment = record.segments[completed]
                val data = decryptIfNeeded(segment, fetch(segment.url))

                file.write(data)
                bytes += data.size
                completed += 1

                record = record.copy(
                    completedSegments = completed,
                    bytesWritten = bytes,
                    finished = completed == record.segments.size,
                )
                DownloadStore.write(paths.record, record)
                onProgress(record)
            }
        }
        record
    }

    private fun fetch(url: String): ByteArray {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code} for $url")
            return response.body.bytes()
        }
    }

    private fun decryptIfNeeded(segment: HlsSegment, data: ByteArray): ByteArray {
        if (!segment.keyMethod.equals("AES-128", ignoreCase = true) || segment.keyUrl == null) {
            return data
        }
        val key = keys.getOrPut(segment.keyUrl) { fetch(segment.keyUrl) }
        val iv = segment.keyIvHex?.let(::hexToBytes) ?: sequenceIv(segment.mediaSequence)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /** The HLS default: the segment's media sequence number, big-endian, in 16 bytes. */
    private fun sequenceIv(sequence: Long): ByteArray {
        val iv = ByteArray(16)
        for (i in 0 until 8) {
            iv[15 - i] = ((sequence shr (8 * i)) and 0xFF).toByte()
        }
        return iv
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = if (hex.length % 2 == 0) hex else "0$hex"
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    companion object {
        /**
         * Creates the on-disk shape of a brand-new download: directory, empty
         * media file, and the record that everything afterwards resumes from.
         */
        fun create(root: File, record: DownloadRecord): DownloadPaths {
            val paths = DownloadPaths(root, record.id)
            paths.directory.mkdirs()
            if (!paths.media.exists()) paths.media.createNewFile()
            DownloadStore.write(paths.record, record)
            return paths
        }
    }
}
