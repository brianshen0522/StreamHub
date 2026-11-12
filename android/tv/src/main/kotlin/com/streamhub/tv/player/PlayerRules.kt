package com.streamhub.tv.player

import com.streamhub.core.model.ProgressUpdate
import com.streamhub.core.model.Source
import com.streamhub.tv.nav.PlayTarget
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong

/*
 * The player's pure rules, ported from `tizen/app/src/player/media.rs` and the
 * helpers at the bottom of `player/mod.rs`. Everything here is unit-tested
 * (PlayerRulesTest) against the Rust tests' own cases.
 */

/**
 * One source as the player holds it: an `/api/sources` (or
 * `/api/check-sources`) NDJSON line, or the bare `{directUrl, sourceLabel}` a
 * cast `play` carries. Tizen keeps these as raw JSON so a field can be
 * *absent*; here absent is null (and "" for a string, which `merge_missing`
 * also treats as missing).
 *
 * `:core`'s [Source] decodes an absent `adSeconds` as 0, so a probe line's 0 is
 * indistinguishable from absence — it is kept as 0 (present), as the server
 * always writes the field.
 */
data class SourceLine(
    val directUrl: String? = null,
    val url: String? = null,
    val sourceLabel: String? = null,
    val proxyUrl: String? = null,
    val episodeLabel: String? = null,
    val durationSeconds: Double? = null,
    val adSeconds: Double? = null,
) {
    /** `direct_url`: `directUrl`, else `url`; null when both are empty. */
    val direct: String?
        get() = directUrl?.takeIf { it.isNotEmpty() } ?: url?.takeIf { it.isNotEmpty() }

    /** `str_field(v, "sourceLabel")`. */
    val label: String get() = sourceLabel.orEmpty()

    companion object {
        fun from(s: Source) = SourceLine(
            directUrl = s.directUrl,
            url = s.url,
            sourceLabel = s.sourceLabel,
            proxyUrl = s.proxyUrl,
            episodeLabel = s.episodeLabel,
            durationSeconds = s.durationSeconds,
            adSeconds = s.adSeconds,
        )
    }
}

/**
 * `merge_missing` (`mod.rs:1898-1909`): copy every field of [from] that [into]
 * lacks (null or ""), keeping what is there. Null when nothing was added.
 */
fun mergeMissing(into: SourceLine, from: SourceLine): SourceLine? {
    fun s(a: String?, b: String?) = if (a.isNullOrEmpty() && !b.isNullOrEmpty()) b else a
    fun <T> n(a: T?, b: T?) = a ?: b
    val merged = SourceLine(
        directUrl = s(into.directUrl, from.directUrl),
        url = s(into.url, from.url),
        sourceLabel = s(into.sourceLabel, from.sourceLabel),
        proxyUrl = s(into.proxyUrl, from.proxyUrl),
        episodeLabel = s(into.episodeLabel, from.episodeLabel),
        durationSeconds = n(into.durationSeconds, from.durationSeconds),
        adSeconds = n(into.adSeconds, from.adSeconds),
    )
    return merged.takeIf { it != into }
}

/** One line of the source picker (`SourceRow`, `mod.rs:1911-1935`). */
data class SourceRow(
    val label: String,
    /** Seconds of spliced ads the filter found and removes; 0 = none found (no 免廣告). */
    val adSeconds: Long,
    /** Content runtime, "1:52:07" or "45:10"; null when unknown. */
    val runtime: String?,
) {
    companion object {
        fun fromLine(i: Int, v: SourceLine): SourceRow {
            val label = v.label.ifEmpty { "片源 ${i + 1}" }
            fun num(d: Double?) = d?.takeIf { it.isFinite() && it > 0.0 }
            val ads = num(v.adSeconds)?.let { Math.round(it) } ?: 0L
            val runtime = num(v.durationSeconds)?.let { n ->
                val t = Math.round(n)
                val h = t / 3600
                val m = t / 60 % 60
                val sec = t % 60
                if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
                else String.format(Locale.ROOT, "%d:%02d", m, sec)
            }
            return SourceRow(label, ads, runtime)
        }
    }
}

object PlayerRules {
    const val SEEK_STEP_MS = 10_000L
    const val MEDIA_SEEK_STEP_MS = 30_000L
    const val SEEK_DEBOUNCE_MS = 400L
    const val CONTROLS_TIMEOUT_MS = 4_000L
    const val PROGRESS_EVERY_MS = 15_000L
    /** How long to wait for the saved source before taking whatever arrived first. */
    const val PREFERENCE_WAIT_MS = 2_500L
    const val STALL_MS = 45_000L
    const val FORGIVE_MS = 60_000L
    const val SCRUB_TICK_MS = 120L
    /** No keyup and no repeat for this long means the release was lost. */
    const val SCRUB_HOLD_GRACE_MS = 450L
    const val OK_LONG_PRESS_MS = 700L
    const val FLASH_MS = 650L
    /** A key-down this recent starts the scrub's acceleration clock. */
    const val SCRUB_KEYDOWN_WINDOW_MS = 2_000L
    /** Offer the next episode once less than this much remains (resume.rs). */
    const val UP_NEXT_LEAD_SECONDS = 120.0
    /** The cast echo: publish again this long after a command. */
    const val ECHO_MS = 60L
    /** Focus 重試 this long after giving up. */
    const val RETRY_FOCUS_MS = 30L

    /** Strip tile pitch (260px + 24px gap) and the visible width (1728px), in dp. */
    const val STRIP_TILE_DP = 142f
    const val STRIP_VIEW_DP = 864f

    /** Scrub acceleration: 10 s steps, 30 s after a second held, 60 s after three. */
    fun scrubStepMs(heldMs: Long): Long = when {
        heldMs < 1_000 -> 10_000
        heldMs < 3_000 -> 30_000
        else -> 60_000
    }

    /** Keep the focused tile two from the left edge until the strip runs out (dp). */
    fun stripOffsetDp(idx: Int, count: Int): Float {
        val maxOffset = max(count * STRIP_TILE_DP - 12f - STRIP_VIEW_DP, 0f)
        return ((idx - 2) * STRIP_TILE_DP).coerceIn(0f, maxOffset)
    }

    /** `format_time`: "1:02:05" or "12:34". */
    fun formatTime(seconds: Long): String {
        val s = max(seconds, 0)
        val h = s / 3600
        val m = s % 3600 / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.ROOT, "%d:%02d", m, sec)
    }

    /** `should_offer_up_next`, on fractional seconds as resume.rs has it. */
    fun shouldOfferUpNext(durationSeconds: Double, positionSeconds: Double): Boolean =
        durationSeconds > 0.0 && durationSeconds - positionSeconds < UP_NEXT_LEAD_SECONDS

    /** What Detail resolved; failing that (a cast play), a movie is whatever has no episode. */
    fun mediaType(t: PlayTarget): String = when (t.mediaType) {
        "movie" -> "movie"
        "tv" -> "tv"
        else -> if (t.episodeLabel.isEmpty() && t.episodes.isEmpty()) "movie" else "tv"
    }

    /** What `/api/sources` is fetched against: the season (movieffm), else the title's own page. */
    fun episodeSourceUrl(t: PlayTarget): String = if (t.seasonUrl.isBlank()) t.itemUrl else t.seasonUrl

    /** The server validates posterUrl as a URL or "": a relative path would 400 every save. */
    fun absoluteOrEmpty(url: String): String {
        val u = url.trim()
        val lower = u.take(8).lowercase(Locale.ROOT)
        return if (lower.startsWith("http://") || lower.startsWith("https://")) u else ""
    }

    /**
     * The `PUT /me/progress` body (`progress_body`). Integer seconds, position
     * capped at the duration; never `progressPercent` / `isCompleted`;
     * `seasonLabel` only when known, so a cast play cannot blank a label the row has.
     */
    fun progressBody(t: PlayTarget, sourceLabel: String, positionMs: Double, durationMs: Double, event: String): ProgressUpdate {
        val duration = max((durationMs / 1000.0).roundToLong(), 0L)
        val position = max((positionMs / 1000.0).roundToLong(), 0L).coerceAtMost(duration)
        return ProgressUpdate(
            providerKey = t.provider,
            mediaType = mediaType(t),
            title = t.title,
            posterUrl = absoluteOrEmpty(t.posterUrl),
            itemUrl = t.itemUrl,
            detailUrl = episodeSourceUrl(t),
            seasonUrl = t.seasonUrl,
            seasonLabel = t.seasonLabel.ifEmpty { null },
            episodeLabel = t.episodeLabel,
            sourceLabel = sourceLabel,
            durationSeconds = duration.toInt(),
            positionSeconds = position.toInt(),
            event = event,
        )
    }

    /**
     * Tier 1 (`absolute_relay`): the line's `proxyUrl` made absolute and pinned
     * to `/api/v1`. [token] is appended when the URL has none; Android passes
     * null, because its HTTP client sends the bearer header to this server
     * itself (and renews it, which a token baked into a URL cannot).
     */
    fun absoluteRelay(base: String, proxy: String, token: String?): String {
        val b = base.trimEnd('/')
        var url = when {
            proxy.startsWith("http://") || proxy.startsWith("https://") -> proxy
            proxy.startsWith("/api/v1/") -> "$b/api/v1/${proxy.removePrefix("/api/v1/")}"
            proxy.startsWith("/api/") -> "$b/api/v1/${proxy.removePrefix("/api/")}"
            else -> "$b$proxy"
        }
        if (token != null && !url.contains("accessToken=")) {
            url += (if (url.contains('?')) "&" else "?") + "accessToken=" + java.net.URLEncoder.encode(token, "UTF-8")
        }
        return url
    }

    /** The neighbour episode: same title and season, from the start (`neighbour`, `mod.rs:238-245`). */
    fun neighbour(t: PlayTarget, label: String): PlayTarget =
        t.copy(episodeLabel = label, source = null, startSeconds = 0.0)

    /** Start position of a target in ms (`start_seconds`, never negative). */
    fun startMs(t: PlayTarget): Double = max(t.startSeconds ?: 0.0, 0.0) * 1000.0

    fun wholeSeconds(ms: Double): Long = max(floor(ms / 1000.0), 0.0).toLong()
}

/**
 * What the "controlled by" badge calls the driving device (`friendly_device`).
 * A real name is kept; the server's fallback "StreamHub (<kind>)" becomes
 * something a viewer reads.
 */
fun friendlyDevice(raw: String): String {
    val name = raw.trim()
    val kind = name.takeIf { it.startsWith("StreamHub (") && it.endsWith(")") }
        ?.removePrefix("StreamHub (")?.removeSuffix(")")?.lowercase(Locale.ROOT)
    return when {
        kind == "android" -> "Android 手機"
        kind == "ios" -> "iPhone"
        kind == "web" || kind == "browser" -> "瀏覽器"
        kind == "tv" -> "電視"
        kind != null -> "另一部裝置"
        name.isEmpty() || name == "Unknown device" -> "另一部裝置"
        else -> name
    }
}

/** 「由 Android 手機控制」: a space only where Latin text meets the CJK frame (`driver_caption`). */
fun driverCaption(name: String): String {
    val n = friendlyDevice(name)
    fun latin(c: Char?) = c != null && c.code < 128 && c.isLetterOrDigit()
    val before = if (latin(n.firstOrNull())) " " else ""
    val after = if (latin(n.lastOrNull())) " " else ""
    return "由$before$n${after}控制"
}
