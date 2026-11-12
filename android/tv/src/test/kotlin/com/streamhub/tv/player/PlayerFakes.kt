package com.streamhub.tv.player

import com.streamhub.core.model.CastCommand
import com.streamhub.core.model.CastPlaybackState
import com.streamhub.core.model.ProgressUpdate
import com.streamhub.core.model.SeasonRef
import com.streamhub.core.model.WatchProgress
import com.streamhub.tv.cast.CastHub
import com.streamhub.tv.focus.TvKey
import com.streamhub.tv.focus.TvKeyPress
import com.streamhub.tv.nav.PlayTarget
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/** Records every call; events are fired by the test, as the shim would. */
class FakeEngine : PlaybackEngine {
    var listener: PlaybackEngine.Listener? = null
        private set
    val opened = ArrayList<Pair<String, Double>>()
    val calls = ArrayList<String>()
    var position = 0.0
    var duration = 0.0

    override fun setListener(listener: PlaybackEngine.Listener?) {
        attach(listener)
    }

    private fun attach(l: PlaybackEngine.Listener?) {
        listener = l
    }

    override fun open(url: String, startMs: Double) {
        opened += url to startMs
        calls += "open"
    }

    override fun play() { calls += "play" }
    override fun pause() { calls += "pause" }
    override fun seek(ms: Double) { calls += "seek:${ms.toLong()}" }
    override fun close() { calls += "close" }
    override fun positionMs() = position
    override fun durationMs() = duration
    override fun backendName() = "fake"
    override fun suspend() { calls += "suspend" }
    override fun restore() { calls += "restore" }
    override fun release() = Unit

    val seeks get() = calls.filter { it.startsWith("seek:") }.map { it.removePrefix("seek:").toLong() }

    /** Comes up playing at [ms] of [durMs]. */
    fun playing(ms: Double = position, durMs: Double = duration) {
        position = ms
        duration = durMs
        listener?.onBuffering(false)
        listener?.onState(PlayerState.PLAYING)
        listener?.onTime(ms)
    }
}

class FakeData : PlayerData {
    var preferred: String? = null
    var reachable = true
    val lines = Channel<SourceLine>(Channel.UNLIMITED)
    val progress = ArrayList<ProgressUpdate>()
    val preferenceSaves = ArrayList<String>()
    var rows: List<WatchProgress> = emptyList()
    var seasonList: List<SeasonRef> = emptyList()
    var episodeLists: Map<String, List<String>> = emptyMap()
    var health = 0

    override suspend fun preferredLabel(target: PlayTarget) = preferred
    override fun sources(target: PlayTarget, preferred: String?): Flow<SourceLine> = lines.consumeAsFlow()
    override suspend fun putProgress(body: ProgressUpdate) { progress += body }
    override suspend fun savePreference(target: PlayTarget, sourceLabel: String) { preferenceSaves += sourceLabel }
    override suspend fun progress(provider: String, itemUrl: String) = rows
    override suspend fun seasons(target: PlayTarget) = seasonList
    override suspend fun episodes(provider: String, sourceUrl: String) = episodeLists[sourceUrl].orEmpty()
    override suspend fun serverReachable(): Boolean {
        health += 1
        return reachable
    }
    override fun manifestUrl(directUrl: String) = "M:$directUrl"
    override fun relayUrl(line: SourceLine) = line.direct?.let { "R:$it" }

    fun line(direct: String, label: String = direct, ads: Double = 0.0, dur: Double? = null) =
        lines.trySend(SourceLine(directUrl = direct, url = direct, sourceLabel = label, adSeconds = ads, durationSeconds = dur))
}

class FakeCast(private val clock: () -> Long) : CastHub {
    val published = ArrayList<CastPlaybackState?>()
    var handler: ((CastCommand) -> Unit)? = null
    var driver: Pair<String, Long>? = null

    override fun publish(state: CastPlaybackState?) { published += state }
    override fun setCommandHandler(handler: (CastCommand) -> Unit): Long {
        this.handler = handler
        return 1
    }
    override fun clearCommandHandler(id: Long) { handler = null }
    override fun controlledBy(): String? = driver?.takeIf { clock() - it.second < 12_000 }?.first
}

class FakeNav : PlayerNav {
    val replaced = ArrayList<PlayTarget>()
    var backs = 0
    override fun replace(target: PlayTarget) { replaced += target }
    override fun back() { backs += 1 }
}

/** One player on virtual time. */
class Harness(val scope: TestScope, val target: PlayTarget = TV) {
    val engine = FakeEngine()
    val data = FakeData()
    val cast = FakeCast { scope.testScheduler.currentTime }
    val nav = FakeNav()
    val focus = ArrayList<PlayerFocusTarget>()
    val ctl = PlayerController(
        target, engine, data, cast, nav, { focus += it },
        scope.backgroundScope, scope.backgroundScope,
        // A monotonic clock is never 0 in practice; the controller uses 0 as "unset".
        { scope.testScheduler.currentTime + CLOCK_ORIGIN },
    )
    val ui get() = ctl.ui

    fun mount(): Harness {
        ctl.mount()
        scope.runCurrent()
        return this
    }

    fun key(k: TvKey, repeat: Boolean = false): Boolean = ctl.onKey(TvKeyPress(k, repeat)).also { scope.runCurrent() }
    fun up(k: TvKey) {
        ctl.onKeyUp(k)
        scope.runCurrent()
    }

    /** Mount, one source, playing at [posMs] of [durMs]. */
    fun playing(posMs: Double = 60_000.0, durMs: Double = 1_500_000.0): Harness {
        mount()
        data.line("https://cdn/a.m3u8", "A")
        scope.runCurrent()
        engine.playing(posMs, durMs)
        scope.runCurrent()
        return this
    }

    companion object {
        const val CLOCK_ORIGIN = 1_000_000L
        val TV = PlayTarget(
            provider = "dramasq",
            itemUrl = "https://d/1",
            title = "黑暗榮耀",
            posterUrl = "https://img/p.jpg",
            mediaType = "tv",
            episodeLabel = "02",
            episodes = listOf("01", "02", "03"),
            startSeconds = 60.0,
        )
    }
}
