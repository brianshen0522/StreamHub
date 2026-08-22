package com.streamhub.core.download

import kotlinx.serialization.Serializable

/**
 * The slice of an HLS media playlist a download needs: every segment in order,
 * each with the key that decrypts it. Nothing here talks to the network — the
 * parse is pure so it can be tested against playlists written in the test.
 */
@Serializable
data class HlsSegment(
    val url: String,
    val durationSeconds: Double,
    /** HLS defaults the AES IV to this, big-endian, when the key tag has none. */
    val mediaSequence: Long,
    val keyMethod: String? = null,
    val keyUrl: String? = null,
    /** Hex, without the 0x prefix, exactly 32 digits — or null for the default. */
    val keyIvHex: String? = null,
)

@Serializable
data class HlsMediaPlaylist(
    val segments: List<HlsSegment>,
    val totalDurationSeconds: Double,
)

object HlsPlaylistParser {

    /** A master playlist lists variants, not segments; it cannot be downloaded itself. */
    fun isMaster(text: String): Boolean = text.contains("#EXT-X-STREAM-INF")

    /**
     * The URIs of a master playlist's variants, best-listed-first as served.
     * Attributes are ignored on purpose: the server's manifest endpoint points
     * every variant back at itself, so any of them arrives cleaned, and the
     * first is what a player would start with.
     */
    fun variantUris(text: String, baseUrl: String): List<String> {
        val lines = text.lines()
        val variants = mutableListOf<String>()
        for ((index, raw) in lines.withIndex()) {
            if (!raw.startsWith("#EXT-X-STREAM-INF")) continue
            val uri = lines.drop(index + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            if (uri != null) variants.add(resolve(uri.trim(), baseUrl))
        }
        return variants
    }

    fun parse(text: String, baseUrl: String): HlsMediaPlaylist {
        var sequence = 0L
        var keyMethod: String? = null
        var keyUrl: String? = null
        var keyIvHex: String? = null
        var pendingDuration: Double? = null
        val segments = mutableListOf<HlsSegment>()

        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L

                line.startsWith("#EXT-X-KEY:") -> {
                    val attributes = keyAttributes(line.substringAfter(':'))
                    keyMethod = attributes["METHOD"]
                    // METHOD=NONE turns encryption off for what follows.
                    if (keyMethod.equals("NONE", ignoreCase = true)) {
                        keyMethod = null; keyUrl = null; keyIvHex = null
                    } else {
                        keyUrl = attributes["URI"]?.let { resolve(it, baseUrl) }
                        keyIvHex = attributes["IV"]?.removePrefix("0x")?.removePrefix("0X")
                    }
                }

                line.startsWith("#EXTINF:") ->
                    pendingDuration = line.substringAfter(':').substringBefore(',').toDoubleOrNull()

                line.isNotEmpty() && !line.startsWith("#") -> {
                    segments.add(
                        HlsSegment(
                            url = resolve(line, baseUrl),
                            durationSeconds = pendingDuration ?: 0.0,
                            mediaSequence = sequence,
                            keyMethod = keyMethod,
                            keyUrl = keyUrl,
                            keyIvHex = keyIvHex,
                        )
                    )
                    sequence += 1
                    pendingDuration = null
                }
            }
        }

        return HlsMediaPlaylist(
            segments = segments,
            totalDurationSeconds = segments.sumOf { it.durationSeconds },
        )
    }

    /**
     * `URI="…"` values may contain commas, so the attribute list cannot simply
     * be split on them: quoted stretches are kept whole.
     */
    private fun keyAttributes(list: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var index = 0
        while (index < list.length) {
            val equals = list.indexOf('=', index)
            if (equals == -1) break
            val name = list.substring(index, equals).trim().trimStart(',').trim()
            var value: String
            var next: Int
            if (equals + 1 < list.length && list[equals + 1] == '"') {
                val close = list.indexOf('"', equals + 2)
                value = if (close == -1) list.substring(equals + 2) else list.substring(equals + 2, close)
                next = if (close == -1) list.length else close + 1
            } else {
                val comma = list.indexOf(',', equals + 1)
                next = if (comma == -1) list.length else comma
                value = list.substring(equals + 1, next).trim()
            }
            if (name.isNotEmpty()) out[name] = value
            index = next + 1
        }
        return out
    }

    private fun resolve(uri: String, baseUrl: String): String = try {
        java.net.URI(baseUrl).resolve(uri.trim()).toString()
    } catch (_: Exception) {
        uri.trim()
    }
}
