package com.streamhub.tv.player

import com.streamhub.core.model.Source
import com.streamhub.tv.nav.PlayTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `player/media.rs` and `player/mod.rs` tests, ported, plus the rules they leave implicit. */
class PlayerRulesTest {

    private fun target() = PlayTarget(
        provider = "dramasq",
        itemUrl = "https://dramasq.io/drama/1.html",
        title = "T",
        episodeLabel = "第01集",
        episodes = listOf("第01集", "第02集"),
    )

    // ── SourceRow / merge_missing (mod.rs source_row_tests) ──────────────

    @Test
    fun adsAndRuntimeComeFromTheLine() {
        val r = SourceRow.fromLine(0, SourceLine(sourceLabel = "線路A", adSeconds = 31.6, durationSeconds = 6727.0))
        assertEquals(SourceRow("線路A", 32, "1:52:07"), r)
    }

    @Test
    fun aKnownSourceGainsTheProbeFieldsWithoutLosingItsOwn() {
        val known = SourceLine(directUrl = "https://cdn/a.m3u8", durationSeconds = null, sourceLabel = "")
        val probe = SourceLine(directUrl = "https://cdn/other.m3u8", sourceLabel = "矽谷線路", adSeconds = 52.0, durationSeconds = 2838.0)
        val merged = mergeMissing(known, probe)!!
        assertEquals("https://cdn/a.m3u8", merged.directUrl)
        assertEquals(52L, SourceRow.fromLine(0, merged).adSeconds)
        assertEquals("矽谷線路", SourceRow.fromLine(0, merged).label)
        assertNull(mergeMissing(merged, probe))
    }

    @Test
    fun missingFieldsMeanNoBadgeAndNoRuntime() {
        assertEquals(SourceRow("片源 3", 0, null), SourceRow.fromLine(2, SourceLine(durationSeconds = null)))
        assertEquals("45:10", SourceRow.fromLine(0, SourceLine(durationSeconds = 2710.0)).runtime)
        // Zero, negative and non-finite are "unknown", not a badge.
        assertEquals(SourceRow("x", 0, null), SourceRow.fromLine(0, SourceLine(sourceLabel = "x", adSeconds = 0.0, durationSeconds = -1.0)))
        assertEquals(0L, SourceRow.fromLine(0, SourceLine(adSeconds = Double.NaN)).adSeconds)
    }

    @Test
    fun aCoreSourceKeepsEveryField() {
        val s = Source(sourceLabel = "L", url = "u", directUrl = "d", proxyUrl = "/api/stream?target=d", durationSeconds = 60.0, adSeconds = 0.0)
        val line = SourceLine.from(s)
        assertEquals("d", line.direct)
        assertEquals(0.0, line.adSeconds!!, 0.0)
        // A present 0 is not "missing" (Tizen: only null and "" are).
        assertNull(mergeMissing(line, SourceLine(adSeconds = 40.0)))
    }

    @Test
    fun directUrlFallsBackToUrl() {
        assertEquals("u", SourceLine(directUrl = "", url = "u").direct)
        assertNull(SourceLine().direct)
    }

    // ── media.rs tests ───────────────────────────────────────────────────

    @Test
    fun progressIsIntegerSecondsWithoutDerivedFields() {
        val b = PlayerRules.progressBody(target(), "S1", 12_600.0, 1_500_400.0, "progress")
        assertEquals(13, b.positionSeconds)
        assertEquals(1500, b.durationSeconds)
        assertEquals("tv", b.mediaType)
        assertEquals("", b.seasonUrl)
        assertEquals("https://dramasq.io/drama/1.html", b.detailUrl)
        assertEquals("S1", b.sourceLabel)
        assertEquals("第01集", b.episodeLabel)
        assertEquals("progress", b.event)
        // Left out, not sent empty, when unknown.
        assertNull(b.seasonLabel)
        assertEquals("S2", PlayerRules.progressBody(target().copy(seasonLabel = "S2"), "", 0.0, 1000.0, "pause").seasonLabel)
    }

    @Test
    fun positionIsCappedAtTheDuration() {
        val b = PlayerRules.progressBody(target(), "", 99_000.0, 60_000.0, "ended")
        assertEquals(60, b.positionSeconds)
        assertEquals(60, b.durationSeconds)
    }

    @Test
    fun relativePostersAreNotSent() {
        val t = target().copy(posterUrl = "/wp-content/uploads/x.jpg")
        assertEquals("", PlayerRules.progressBody(t, "", 0.0, 1000.0, "progress").posterUrl)
        assertEquals("https://img/x.jpg", PlayerRules.progressBody(t.copy(posterUrl = "https://img/x.jpg"), "", 0.0, 1000.0, "progress").posterUrl)
        assertEquals("HTTPS://A/b", PlayerRules.absoluteOrEmpty("HTTPS://A/b"))
        assertEquals("", PlayerRules.absoluteOrEmpty("  "))
    }

    @Test
    fun driverNamesReadAsDevices() {
        assertEquals("由 Android 手機控制", driverCaption("StreamHub (android)"))
        assertEquals("由 iPhone 控制", driverCaption("StreamHub (ios)"))
        assertEquals("由瀏覽器控制", driverCaption("StreamHub (web)"))
        assertEquals("由瀏覽器控制", driverCaption("StreamHub (browser)"))
        assertEquals("由電視控制", driverCaption("StreamHub (tv)"))
        assertEquals("由另一部裝置控制", driverCaption("StreamHub (fridge)"))
        assertEquals("由 Pixel 7 控制", driverCaption("Pixel 7"))
        assertEquals("由 Chrome on Mac 控制", driverCaption("Chrome on Mac"))
        assertEquals("由另一部裝置控制", driverCaption("Unknown device"))
        assertEquals("由另一部裝置控制", driverCaption(""))
        assertEquals("由客廳的手機控制", driverCaption("客廳的手機"))
    }

    @Test
    fun sourcesFallBackToTheItemPage() {
        val t = target()
        assertEquals(t.itemUrl, PlayerRules.episodeSourceUrl(t))
        assertEquals("https://m/s2", PlayerRules.episodeSourceUrl(t.copy(seasonUrl = "https://m/s2")))
    }

    @Test
    fun relayIsVersioned() {
        assertEquals("http://h/api/v1/stream?target=x&accessToken=tok", PlayerRules.absoluteRelay("http://h", "/api/stream?target=x", "tok"))
        assertEquals("http://h/api/v1/stream?target=x&accessToken=a", PlayerRules.absoluteRelay("http://h", "/api/v1/stream?target=x&accessToken=a", "tok"))
        // Android: the header carries the token.
        assertEquals("http://h/api/v1/stream?target=x", PlayerRules.absoluteRelay("http://h/", "/api/stream?target=x", null))
        assertEquals("https://o/p", PlayerRules.absoluteRelay("http://h", "https://o/p", null))
    }

    @Test
    fun captionsAndTimes() {
        assertEquals("0:00", PlayerRules.formatTime(0))
        assertEquals("1:02:05", PlayerRules.formatTime(3725))
        assertEquals("12:34", PlayerRules.formatTime(754))
    }

    @Test
    fun mediaTypeIsInferredWhenUnknown() {
        assertEquals("tv", PlayerRules.mediaType(target()))
        assertEquals("movie", PlayerRules.mediaType(target().copy(episodeLabel = "", episodes = emptyList())))
        assertEquals("movie", PlayerRules.mediaType(target().copy(mediaType = "movie")))
        assertEquals("tv", PlayerRules.mediaType(PlayTarget("p", "u", mediaType = "tv")))
    }

    // ── mod.rs helpers ───────────────────────────────────────────────────

    @Test
    fun scrubAccelerates() {
        assertEquals(10_000L, PlayerRules.scrubStepMs(0))
        assertEquals(10_000L, PlayerRules.scrubStepMs(999))
        assertEquals(30_000L, PlayerRules.scrubStepMs(1_000))
        assertEquals(30_000L, PlayerRules.scrubStepMs(2_999))
        assertEquals(60_000L, PlayerRules.scrubStepMs(3_000))
    }

    @Test
    fun stripKeepsTheFocusedTileTwoFromTheLeft() {
        assertEquals(0f, PlayerRules.stripOffsetDp(0, 40))
        assertEquals(0f, PlayerRules.stripOffsetDp(2, 40))
        assertEquals(142f, PlayerRules.stripOffsetDp(3, 40))
        // Clamped at the end: 40 × 142 − 12 − 864.
        assertEquals(4804f, PlayerRules.stripOffsetDp(39, 40))
        // A short season never moves.
        assertEquals(0f, PlayerRules.stripOffsetDp(5, 6))
    }

    @Test
    fun upNextWindowIsTheLastTwoMinutes() {
        assertEquals(false, PlayerRules.shouldOfferUpNext(0.0, 0.0))
        assertEquals(false, PlayerRules.shouldOfferUpNext(1500.0, 1380.0))
        assertEquals(true, PlayerRules.shouldOfferUpNext(1500.0, 1380.5))
    }

    @Test
    fun neighboursStartFromZeroWithoutASource() {
        val t = target().copy(source = SourceLine(directUrl = "d"), startSeconds = 99.0)
        val n = PlayerRules.neighbour(t, "第02集")
        assertEquals("第02集", n.episodeLabel)
        assertNull(n.source)
        assertEquals(0.0, n.startSeconds!!, 0.0)
        assertEquals(t.episodes, n.episodes)
    }
}
