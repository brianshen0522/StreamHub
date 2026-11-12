package com.streamhub.tv.player

import com.streamhub.core.labels.Labels
import com.streamhub.core.model.CastCommand
import com.streamhub.core.model.CastPlaybackState
import com.streamhub.core.model.WatchProgress
import com.streamhub.core.resume.ResumeRules
import com.streamhub.core.resume.UpNext
import com.streamhub.tv.cast.CastHub
import com.streamhub.tv.focus.TvKey
import com.streamhub.tv.focus.TvKeyPress
import com.streamhub.tv.nav.PlayTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Where the player sends the app. */
interface PlayerNav {
    /** `nav::replace(Route::Player(t))`: next/previous episode, the strip, the end. */
    fun replace(target: PlayTarget)

    /** `nav::back()`: leave to whatever opened the player. */
    fun back()
}

/** The three places the player moves focus to, each after the layer has rendered. */
enum class PlayerFocusTarget {
    /** `[data-vp-default]`: the 播放/暫停 pill. */
    Toggle,

    /** The picker's `is-current` row, else its first row. */
    PickerCurrent,

    /** 重試 on the error panel. */
    Retry,
}

fun interface PlayerFocus {
    fun focus(target: PlayerFocusTarget)
}

/**
 * The player's business end: a port of `Ctl` in `tizen/app/src/player/mod.rs`
 * — sources and preference, the recovery ladder, the shim's events, transport,
 * tap-to-seek and hold-to-scrub, OK tap vs long press, the episode strip, the
 * picker, up-next, the 1 s tick, progress reports and the cast state.
 *
 * Single-threaded: everything runs on [scope]'s dispatcher (the main thread
 * in the app, a test dispatcher under test). Timers are coroutines; assigning
 * a new one cancels the old, as dropping a gloo `Timeout` does. Writes that
 * must outlive the screen (progress, the source preference) go to
 * [background].
 */
class PlayerController(
    val target: PlayTarget,
    private val engine: PlaybackEngine,
    private val data: PlayerData,
    private val cast: CastHub,
    private val nav: PlayerNav,
    private val focus: PlayerFocus,
    scope: CoroutineScope,
    private val background: CoroutineScope,
    private val clock: () -> Long,
) : PlaybackEngine.Listener {

    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val cs = CoroutineScope(scope.coroutineContext + job)

    val ui = PlayerUi(PlayerRules.wholeSeconds(PlayerRules.startMs(target)))

    // ── Inner ──────────────────────────────────────────────────────────────
    internal val sources = ArrayList<SourceLine>()
    internal var ladder: Ladder? = null
    internal var started = false
    private var streamDone = false
    internal var waitingMore = false
    private var probing = false
    internal var offline = false
    private var preferred: String? = null
    private var prefTimer: Job? = null
    private var sourcesJob: Job? = null
    internal var posMs = PlayerRules.startMs(target)
    internal var durMs = 0.0
    internal var lastGoodMs = posMs
    private var lastFaultAt = 0L
    private var lastFault = ""
    internal var intentPlay = true
    internal var ended = false
    internal var upNextDismissed = false
    internal var next: PlayTarget? = null
    internal var prev: PlayTarget? = null
    internal var pendingSeek: Double? = null
    private var seekTimer: Job? = null
    private var hideTimer: Job? = null
    private var ticker: Job? = null
    private var lastProgressAt = 0L
    private var stallSince = 0L
    private var stallAnchor = 0.0
    private var cmdId = 0L
    internal var deferredSeek: Double? = null
    private var keyDownAt = 0L
    private var scrub: Scrub? = null
    private var scrubTimer: Job? = null
    private var okDown = false
    private var okRepeat = false
    private var okLong = false
    private var okTimer: Job? = null
    private var flashSeq = 0L
    private var flashTimer: Job? = null
    private var stripRows: List<WatchProgress?> = emptyList()
    private var focusTimer: Job? = null
    private var echoTimer: Job? = null
    private var mounted = false
    private var tornDown = false

    private class Scrub(var dir: Int, var since: Long, var heldUntil: Long)

    private fun now(): Long = clock()

    private fun timer(ms: Long, block: () -> Unit): Job = cs.launch {
        delay(ms)
        block()
    }

    // ── mount / teardown ─────────────────────────────────────────────────

    fun mount() {
        if (mounted) return
        mounted = true
        // A replace (next episode, cast play) can build the new screen before
        // the old one's cleanup runs: end the old one first, so it cannot
        // clear handlers this one installs.
        active?.takeIf { it !== this }?.teardown()
        active = this
        lastProgressAt = now()
        engine.setListener(this)

        // Neighbours, for up-next, the 下一集 button and the cast remote.
        val ep = target.episodeLabel
        next = (ResumeRules.upNext(target.episodes, ep, emptyList(), target.seasonUrl) as? UpNext.Episode)
            ?.let { PlayerRules.neighbour(target, it.label) }
        prev = ResumeRules.previousEpisode(target.episodes, ep)?.let { PlayerRules.neighbour(target, it) }
        ui.hasNext = next != null
        if (target.episodes.isNotEmpty() && target.episodes.last() == target.episodeLabel) resolveNextSeason()

        ticker = cs.launch {
            while (true) {
                delay(1_000)
                tick()
            }
        }
        cmdId = cast.setCommandHandler(::onCommand)

        target.source?.let {
            sources.add(it)
            refreshSourceLabels()
            begin(0)
        }
        // Always listed, even with a source in hand: the others are the
        // ladder's next rung and the 片源 menu.
        loadSources()
        showOsd()
        publish()
    }

    /** Any route change away from, or replacement of, the player (`teardown`, `mod.rs:247-279`). */
    fun teardown() {
        if (!mounted || tornDown) return
        tornDown = true
        if (active === this) active = null
        // Leaving is a pause, as far as progress is concerned.
        if (started && !ended) report("pause")
        cast.publish(null)
        cast.clearCommandHandler(cmdId)
        job.cancel()
        engine.setListener(null)
        engine.close()
    }

    /** The app went to the background (`visibilitychange` → hidden): save, and let the decoder go. */
    fun onHidden() {
        if (tornDown) return
        report("pause")
        engine.suspend()
    }

    fun onShown() {
        if (tornDown) return
        engine.restore()
    }

    // ── sources ──────────────────────────────────────────────────────────

    private fun loadSources() {
        sourcesJob?.cancel()
        streamDone = false
        sourcesJob = cs.launch {
            val pref = data.preferredLabel(target)
            preferred = pref
            val error: Throwable? = try {
                data.sources(target, pref).collect { onSource(it) }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                e
            }
            onSourcesDone(error)
        }
    }

    internal fun refreshSourceLabels() {
        ui.sources = sources.mapIndexed { i, v -> SourceRow.fromLine(i, v) }
    }

    internal fun onSource(line: SourceLine) {
        val direct = line.direct ?: return
        val knownAt = sources.indexOfFirst { it.direct == direct }
        if (knownAt >= 0) {
            // Already listed — typically the source a cast started with, which
            // carries no label, runtime or ad count. Fill those in; keep what is there.
            mergeMissing(sources[knownAt], line)?.let {
                sources[knownAt] = it
                refreshSourceLabels()
            }
            return
        }
        val label = line.label
        sources.add(line)
        val idx = sources.size - 1
        refreshSourceLabels()
        if (!started) {
            val p = preferred
            when {
                p == null || p == label -> begin(idx)
                prefTimer == null -> prefTimer = timer(PlayerRules.PREFERENCE_WAIT_MS) {
                    prefTimer = null
                    if (!started) begin(0)
                }
            }
        } else if (waitingMore) {
            waitingMore = false
            ladder?.select(idx)
            ui.notice = "改用其他片源…"
            startSource(idx, Tier.Manifest, lastGoodMs)
        }
    }

    private fun onSourcesDone(error: Throwable?) {
        streamDone = true
        val preferredIdx = preferred?.let { p -> sources.indexOfFirst { it.label == p }.takeIf { it >= 0 } }
        if (error != null && !started && error is IOException) {
            waitForNetwork()
            return
        }
        if (!started) {
            if (sources.isNotEmpty()) begin(preferredIdx ?: 0)
            else giveUp(error?.message ?: "找不到可播放的片源")
        } else if (waitingMore) {
            waitingMore = false
            giveUp(lastFault)
        }
    }

    /** First playback of a source chosen automatically. */
    private fun begin(idx: Int) {
        prefTimer?.cancel()
        prefTimer = null
        ladder = Ladder(idx)
        startSource(idx, Tier.Manifest, posMs)
    }

    private fun startSource(idx: Int, tier: Tier, pos: Double) {
        val source = sources.getOrNull(idx) ?: return
        val url = when (tier) {
            Tier.Manifest -> source.direct?.let(data::manifestUrl)
            Tier.Relay -> data.relayUrl(source)
        }
        if (url == null) {
            giveUp("片源網址無效")
            return
        }
        started = true
        ended = false
        intentPlay = true
        stallSince = 0
        posMs = pos
        ui.fatal = null
        ui.currentSource = idx
        ui.sourceLabel = source.label
        ui.buffering = true
        engine.open(url, pos)
        ui.backend = engine.backendName()
    }

    // ── recovery ─────────────────────────────────────────────────────────

    private fun onFault(msg: String) {
        if (probing || offline || ui.fatal != null) return
        probing = true
        lastFault = msg
        ui.notice = "重新連線中…"
        cs.launch {
            val reachable = data.serverReachable()
            probing = false
            if (reachable) climb(msg) else waitForNetwork()
        }
    }

    /** The network, not the source: hold the ladder and resume the same rung once the server answers. */
    private fun waitForNetwork() {
        offline = true
        ui.notice = "網路連線中斷，恢復後自動繼續…"
        cs.launch {
            var wait = 2_000L
            while (true) {
                delay(wait)
                if (data.serverReachable()) break
                wait = min(wait * 2, 30_000L)
            }
            offline = false
            val l = ladder
            val idx = l?.source ?: 0
            val tier = l?.tier ?: Tier.Manifest
            ui.notice = "重新連線中…"
            if (started) startSource(idx, tier, lastGoodMs) else loadSources()
        }
    }

    private fun climb(msg: String) {
        lastFaultAt = now()
        val l = ladder ?: return
        val step = l.next(sources.size)
        val idx = l.source
        val pos = lastGoodMs
        when (step) {
            Step.Retry -> startSource(idx, l.tier, pos)
            Step.SwitchToRelay -> {
                ui.notice = "改用伺服器轉送…"
                startSource(idx, Tier.Relay, pos)
            }
            is Step.NextSource -> {
                ui.notice = "改用其他片源…"
                startSource(step.index, Tier.Manifest, pos)
            }
            Step.Exhausted -> if (!streamDone) {
                waitingMore = true
                ui.notice = "尋找其他片源…"
            } else {
                giveUp(msg)
            }
        }
    }

    private fun giveUp(msg: String) {
        intentPlay = false
        ui.fatal = msg
        ui.notice = null
        ui.buffering = false
        ui.controls = false
        ui.picker = false
        ui.upNext = null
        ui.osd = false
        publish()
        focusLater(PlayerRules.RETRY_FOCUS_MS, PlayerFocusTarget.Retry)
    }

    /** 重試. */
    fun retry() {
        ui.fatal = null
        ui.notice = "重新連線中…"
        ladder?.select(0)
        if (sources.isEmpty()) {
            started = false
            loadSources()
        } else {
            startSource(0, Tier.Manifest, lastGoodMs)
        }
    }

    // ── player events ────────────────────────────────────────────────────

    override fun onState(state: String) {
        ui.status = state
        when (state) {
            PlayerState.PLAYING -> {
                if (!ui.buffering) ui.notice = null
                // Keys pressed while loading are not lost: a pause or a seek asked for then lands now.
                val pausedMeanwhile = !intentPlay
                val seek = deferredSeek
                deferredSeek = null
                seek?.let(engine::seek)
                if (pausedMeanwhile) engine.pause()
                publish()
            }
            PlayerState.PAUSED -> {
                val seek = deferredSeek
                deferredSeek = null
                seek?.let(engine::seek)
                intentPlay = false
                report("pause")
                showOsd()
                publish()
            }
        }
    }

    override fun onTime(ms: Double) = onTimeInner(ms)

    override fun onBuffering(buffering: Boolean) {
        ui.buffering = buffering
        if (!buffering && ui.status == PlayerState.PLAYING && !offline) ui.notice = null
        publish()
    }

    override fun onEnded() = onEndedInner()

    override fun onError(message: String) = onFault(message)

    private fun onTimeInner(ms: Double) {
        val dur = engine.durationMs()
        posMs = ms
        if (ms > 0) lastGoodMs = ms
        if (dur > 0) durMs = dur
        val sec = PlayerRules.wholeSeconds(ms)
        if (ui.posS != sec) ui.posS = sec
        val durS = PlayerRules.wholeSeconds(dur)
        if (durS > 0 && ui.durS != durS) ui.durS = durS
        checkUpNext()
    }

    private fun checkUpNext() {
        if (ended) return
        val n = next
        if (n == null) {
            if (ui.upNext != null) ui.upNext = null
            return
        }
        val pos = posMs / 1000.0
        val dur = durMs / 1000.0
        val offer = PlayerRules.shouldOfferUpNext(dur, pos)
        if (!offer) upNextDismissed = false
        val want = if (offer && !upNextDismissed) {
            UpNextCard("下一集 ${Labels.episodeCaption(n.episodeLabel)}", ceil(max(dur - pos, 0.0)).toLong())
        } else {
            null
        }
        if (ui.upNext != want && ui.fatal == null) ui.upNext = want
    }

    private fun onEndedInner() {
        if (ended) return
        ended = true
        intentPlay = false
        posMs = max(durMs, posMs)
        val n = next
        val dismissed = upNextDismissed
        // Finished means finished: the end position is what marks it complete.
        report("ended")
        ui.upNext = null
        publish()
        // Nothing to ask about: a frozen last frame is a worse place to leave someone than the title page.
        if (n != null && !dismissed) nav.replace(n) else leave()
    }

    // ── transport ────────────────────────────────────────────────────────

    private fun isPlayingIntent(): Boolean =
        ui.status == PlayerState.PLAYING || (intentPlay && ui.status == PlayerState.LOADING)

    fun toggle() {
        if (ui.fatal != null || ended) return
        if (isPlayingIntent()) pause() else play()
    }

    private fun play() {
        intentPlay = true
        engine.play()
    }

    private fun pause() {
        intentPlay = false
        engine.pause()
    }

    /** Move the pending seek target; nothing is sent to the player. */
    private fun advance(deltaMs: Double): Boolean {
        if (ui.fatal != null || !started) return false
        val base = pendingSeek ?: deferredSeek ?: posMs
        var t = max(base + deltaMs, 0.0)
        if (durMs > 0) t = min(t, max(durMs - 1_000.0, 0.0))
        pendingSeek = t
        ui.seekPreview = (t / 1000.0).toLong()
        showOsd()
        return true
    }

    /** Left/Right taps (and ⏪/⏩, the 10 秒 pills): presses accumulate into one seek, sent once they stop. */
    fun nudge(deltaMs: Long) {
        if (!advance(deltaMs.toDouble())) return
        seekTimer?.cancel()
        seekTimer = timer(PlayerRules.SEEK_DEBOUNCE_MS) {
            val t = pendingSeek
            pendingSeek = null
            if (t != null) seekTo(t)
        }
    }

    // ── hold-to-scrub ────────────────────────────────────────────────────

    /** A repeat keydown of Left/Right: enter scrub mode, or keep it alive. */
    private fun scrubHold(dir: Int) {
        val t = now()
        val sc = scrub
        if (sc != null) {
            sc.heldUntil = t + PlayerRules.SCRUB_HOLD_GRACE_MS
            if (sc.dir != dir) {
                sc.dir = dir
                sc.since = t
            }
            return
        }
        val since = if (keyDownAt > 0 && t - keyDownAt < PlayerRules.SCRUB_KEYDOWN_WINDOW_MS) keyDownAt else t
        scrub = Scrub(dir, since, t + PlayerRules.SCRUB_HOLD_GRACE_MS)
        // The tap's debounced seek would fire mid-hold.
        seekTimer?.cancel()
        seekTimer = null
        if (pendingSeek == null && !advance(0.0)) {
            scrub = null
            return
        }
        ui.scrub = true
        scrubTimer?.cancel()
        scrubTimer = cs.launch {
            while (true) {
                delay(PlayerRules.SCRUB_TICK_MS)
                scrubTick()
            }
        }
    }

    private fun scrubTick() {
        val t = now()
        val sc = scrub ?: return
        if (t > sc.heldUntil) {
            // Neither a repeat nor a keyup arrived: the release was lost.
            commitScrub()
        } else {
            advance((sc.dir * PlayerRules.scrubStepMs(t - sc.since)).toDouble())
        }
    }

    /** Release (or OK): one seek to wherever the target got to. */
    private fun commitScrub() {
        scrub = null
        scrubTimer?.cancel()
        scrubTimer = null
        seekTimer?.cancel()
        seekTimer = null
        val t = pendingSeek
        pendingSeek = null
        ui.scrub = false
        if (t != null) seekTo(t) else ui.seekPreview = null
    }

    /** Back during a scrub or a tap burst: forget the target, seek nowhere. */
    private fun cancelSeek() {
        scrub = null
        scrubTimer?.cancel()
        scrubTimer = null
        seekTimer?.cancel()
        seekTimer = null
        pendingSeek = null
        ui.scrub = false
        ui.seekPreview = null
        showOsd()
    }

    fun onKeyUp(key: TvKey) {
        when (key) {
            TvKey.Left, TvKey.Right -> if (scrub != null) commitScrub()
            TvKey.Enter -> okUp()
            else -> Unit
        }
    }

    // ── OK: tap toggles, long press opens the sources ────────────────────

    private fun okDownPress(repeat: Boolean) {
        if (repeat) {
            okRepeat = true
            return
        }
        if (okDown) return
        okDown = true
        okRepeat = false
        okLong = false
        okTimer?.cancel()
        okTimer = timer(PlayerRules.OK_LONG_PRESS_MS) {
            okTimer = null
            if (!okDown) return@timer
            if (okRepeat) {
                okLong = true
                openPicker()
            } else {
                // No repeats and no keyup: this platform lost the release. A press that never repeated was a tap.
                okDown = false
                okTap()
            }
        }
    }

    private fun okUp() {
        if (!okDown) return
        okDown = false
        okTimer?.cancel()
        okTimer = null
        val tap = !okLong
        okLong = false
        if (tap) okTap()
    }

    private fun okTap() {
        if (ui.fatal != null || ui.picker) return
        toggleWithFeedback()
    }

    /** Toggle, raise the bar, and pop a play/pause glyph so the press is seen. */
    private fun toggleWithFeedback() {
        if (ui.fatal != null || ended) return
        val pausing = isPlayingIntent()
        toggle()
        flash(!pausing)
        showOsd()
    }

    private fun flash(playing: Boolean) {
        flashSeq += 1
        val seq = flashSeq
        ui.flash = Flash(playing, seq)
        flashTimer?.cancel()
        flashTimer = timer(PlayerRules.FLASH_MS) {
            if (ui.flash?.nonce == seq) ui.flash = null
        }
    }

    // ── episode strip ────────────────────────────────────────────────────

    private fun currentIndex(): Int = target.episodes.indexOf(target.episodeLabel).coerceAtLeast(0)

    private fun openStrip() {
        val n = target.episodes.size
        if (n == 0) {
            showControls()
            return
        }
        ui.controls = false
        ui.osd = false
        hideTimer?.cancel()
        hideTimer = null
        ui.strip = currentIndex()
        cs.launch {
            val rows = try {
                data.progress(target.provider, target.itemUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            val map = ResumeRules.progressMap(rows)
            val per = target.episodes.map { map[ResumeRules.progressKey(target.seasonUrl, it)] }
            stripRows = per
            ui.stripInfo = per.map { p ->
                if (p == null) StripInfo(0.0, false) else StripInfo((p.progressPercent / 100.0).coerceIn(0.0, 1.0), p.isCompleted)
            }
        }
    }

    private fun closeStrip() {
        ui.strip = null
        showOsd()
    }

    private fun stripKey(key: TvKey, repeat: Boolean): Boolean {
        val idx = ui.strip ?: return false
        val last = max(target.episodes.size - 1, 0)
        when {
            key == TvKey.Left -> ui.strip = max(idx - 1, 0)
            key == TvKey.Right -> ui.strip = min(idx + 1, last)
            key == TvKey.Enter && !repeat -> chooseEpisode(idx)
            key == TvKey.Back || key == TvKey.Up -> closeStrip()
            key == TvKey.Down || key == TvKey.Enter -> Unit
            else -> return false
        }
        return true
    }

    /** Another episode of the season: from its resume point if it has one. The current one just closes the strip. */
    private fun chooseEpisode(idx: Int) {
        if (idx == currentIndex()) {
            closeStrip()
            return
        }
        val label = target.episodes.getOrNull(idx) ?: return
        val start = stripRows.getOrNull(idx)?.let { ResumeRules.resumePositionSeconds(it).toDouble() } ?: 0.0
        nav.replace(PlayerRules.neighbour(target, label).copy(startSeconds = start))
    }

    private fun ready(): Boolean = ui.status == PlayerState.PLAYING || ui.status == PlayerState.PAUSED

    private fun seekTo(ms: Double) {
        val isReady = ready()
        pendingSeek = null
        seekTimer?.cancel()
        seekTimer = null
        posMs = ms
        lastGoodMs = ms
        stallSince = 0
        // Still loading: the player would drop it. Applied on "playing".
        if (!isReady) deferredSeek = ms else engine.seek(ms)
        ui.seekPreview = null
        ui.posS = (ms / 1000.0).toLong()
        checkUpNext()
        publish()
    }

    fun goNext() {
        next?.let(nav::replace)
    }

    private fun goPrev() {
        prev?.let(nav::replace)
    }

    /** Progress is written by the teardown the route change triggers. */
    fun leave() = nav.back()

    fun selectSource(idx: Int) {
        ui.picker = false
        if (ui.currentSource == idx) {
            showControls()
            return
        }
        val l = ladder
        if (l != null) l.select(idx) else ladder = Ladder(idx)
        val label = sources.getOrNull(idx)?.label.orEmpty()
        val pos = posMs
        report("switch")
        ui.notice = "切換片源…"
        startSource(idx, Tier.Manifest, pos)
        background.launch { runCatching { data.savePreference(target, label) } }
        showOsd()
    }

    // ── chrome ───────────────────────────────────────────────────────────

    private fun armHide() {
        hideTimer?.cancel()
        hideTimer = timer(PlayerRules.CONTROLS_TIMEOUT_MS) {
            // Never while paused: hidden controls on a paused picture offer no way to start it again.
            if (ui.picker || ui.fatal != null || ui.status == PlayerState.PAUSED) return@timer
            if (ui.seekPreview != null) {
                armHide()
                return@timer
            }
            ui.controls = false
            ui.osd = false
        }
    }

    private fun showOsd() {
        ui.osd = true
        armHide()
    }

    private fun showControls() {
        ui.controls = true
        showOsd()
        focusLater(0, PlayerFocusTarget.Toggle)
    }

    private fun hideControls() {
        ui.controls = false
        ui.osd = ui.status == PlayerState.PAUSED
        hideTimer?.cancel()
        hideTimer = null
    }

    fun openPicker() {
        ui.controls = false
        ui.picker = true
        hideTimer?.cancel()
        hideTimer = null
        focusLater(0, PlayerFocusTarget.PickerCurrent)
    }

    /** Focus once the layer has rendered; a newer request replaces an older one. */
    private fun focusLater(ms: Long, which: PlayerFocusTarget) {
        focusTimer?.cancel()
        focusTimer = timer(ms) { focus.focus(which) }
    }

    private fun closePicker() {
        ui.picker = false
        showControls()
    }

    // ── the remote ───────────────────────────────────────────────────────

    /**
     * The remote. One layer per Back, innermost first: error panel → source
     * picker → episode strip → controls → scrub/seek burst → up-next → leave.
     * False lets the focus engine have the key (the pills, the panel buttons).
     */
    fun onKey(press: TvKeyPress): Boolean {
        val key = press.key
        val repeat = press.repeat
        if (key == TvKey.Exit) {
            // The app is about to die; this is the last chance to save.
            report("pause")
            return false
        }
        if (ui.fatal != null) {
            // The panel's 重試 / 返回 take the D-pad and OK.
            if (key == TvKey.Back) {
                leave()
                return true
            }
            return false
        }
        if (ui.picker) {
            if (key == TvKey.Back) {
                closePicker()
                return true
            }
            return false
        }
        when (key) {
            TvKey.PlayPause -> if (!repeat) toggleWithFeedback()
            TvKey.Play, TvKey.Pause -> if (!repeat) {
                val playing = key == TvKey.Play
                if (playing) play() else pause()
                flash(playing)
                showOsd()
            }
            TvKey.Stop -> leave()
            TvKey.Rewind -> nudge(-PlayerRules.MEDIA_SEEK_STEP_MS)
            TvKey.FastForward -> nudge(PlayerRules.MEDIA_SEEK_STEP_MS)
            TvKey.Next -> goNext()
            TvKey.Previous -> goPrev()
            else -> return onDpad(key, repeat)
        }
        return true
    }

    private fun onDpad(key: TvKey, repeat: Boolean): Boolean {
        if (ui.strip != null) return stripKey(key, repeat)
        if (ui.controls) {
            return when (key) {
                TvKey.Back -> {
                    hideControls()
                    true
                }
                TvKey.Up, TvKey.Down, TvKey.Left, TvKey.Right, TvKey.Enter -> {
                    // The focus engine moves between (or clicks) the pills.
                    armHide()
                    false
                }
                else -> false
            }
        }
        val dir = when (key) {
            TvKey.Left -> -1
            TvKey.Right -> 1
            else -> 0
        }
        if (scrub != null) {
            when {
                key == TvKey.Left || key == TvKey.Right -> scrubHold(dir)
                key == TvKey.Enter && !repeat -> commitScrub()
                key == TvKey.Back -> cancelSeek()
            }
            return true
        }
        if (ui.upNext != null) {
            when {
                key == TvKey.Enter && !repeat -> goNext()
                key == TvKey.Back || key == TvKey.Down -> {
                    upNextDismissed = true
                    ui.upNext = null
                }
                key == TvKey.Up -> showControls()
                // Nothing destructive while the card is up.
            }
            return true
        }
        when {
            key == TvKey.Enter -> okDownPress(repeat)
            (key == TvKey.Left || key == TvKey.Right) && repeat -> scrubHold(dir)
            key == TvKey.Left || key == TvKey.Right -> {
                keyDownAt = now()
                nudge(dir * PlayerRules.SEEK_STEP_MS)
            }
            key == TvKey.Up -> showControls()
            key == TvKey.Down && target.episodes.isNotEmpty() -> openStrip()
            key == TvKey.Down -> showControls()
            key == TvKey.Back && pendingSeek != null -> cancelSeek()
            key == TvKey.Back -> leave()
            else -> return false
        }
        return true
    }

    internal fun onCommand(cmd: CastCommand) {
        if (tornDown) return
        ui.driver = cast.controlledBy()
        when (cmd) {
            CastCommand.Pause -> pause()
            CastCommand.Resume -> play()
            is CastCommand.Seek -> {
                // Clamped like the remote's own seeks: never past the end.
                var ms = cmd.positionMs.toDouble()
                if (durMs > 0) ms = min(ms, max(durMs - 1_000.0, 0.0))
                seekTo(max(ms, 0.0))
            }
            CastCommand.Stop -> {
                leave()
                return
            }
            CastCommand.Next -> goNext()
            CastCommand.Previous -> goPrev()
            else -> return
        }
        showOsd()
        // The echo: the phone shows its press optimistically and waits for this.
        echoTimer?.cancel()
        echoTimer = timer(PlayerRules.ECHO_MS) { publish() }
    }

    // ── the 1 s tick ─────────────────────────────────────────────────────

    internal fun tick() {
        val driver = cast.controlledBy()
        if (ui.driver != driver) ui.driver = driver
        val playing = ui.status == PlayerState.PLAYING
        val buffering = ui.buffering
        val fatal = ui.fatal != null
        val t = now()
        val due = playing && t - lastProgressAt >= PlayerRules.PROGRESS_EVERY_MS
        if (playing && !buffering && lastFaultAt > 0 && t - lastFaultAt > PlayerRules.FORGIVE_MS) {
            lastFaultAt = 0
            ladder?.forgive()
        }
        // A player can also die without an error: buffering forever against a source that stopped answering.
        var stalled = false
        if (buffering && intentPlay && started && !fatal && !offline && !probing && !waitingMore) {
            if (stallSince == 0L || posMs != stallAnchor) {
                stallSince = t
                stallAnchor = posMs
            } else if (t - stallSince >= PlayerRules.STALL_MS) {
                stallSince = 0
                stalled = true
            }
        } else {
            stallSince = 0
        }
        if (playing || (intentPlay && buffering && !fatal)) publish()
        if (due) report("progress")
        if (stalled) onFault("STREAM_STALLED")
    }

    // ── reporting ────────────────────────────────────────────────────────

    internal fun report(event: String) {
        lastProgressAt = now()
        val livePos = engine.positionMs()
        val dur = max(durMs, engine.durationMs())
        val pos = when {
            event == "ended" -> dur
            livePos > 0 && pendingSeek == null -> livePos
            else -> posMs
        }
        // No duration yet means nothing has played: nothing worth recording.
        if (dur <= 0) return
        val body = PlayerRules.progressBody(target, ui.sourceLabel, pos, dur, event)
        background.launch { runCatching { data.putProgress(body) } }
    }

    /** What the phones see (`publish`, `mod.rs:1534-1558`). */
    internal fun state(): CastPlaybackState = CastPlaybackState(
        provider = target.provider,
        itemUrl = target.itemUrl,
        title = target.title,
        subtitle = ui.sourceLabel,
        posterUrl = target.posterUrl,
        episodeLabel = target.episodeLabel,
        positionMs = max(pendingSeek ?: posMs, 0.0).roundToLong(),
        durationMs = max(durMs, 0.0).roundToLong(),
        // Intent, not isPlaying: a buffering player that means to play is "playing" to the remote.
        paused = !intentPlay,
        buffering = ui.buffering,
        hasNext = next != null,
        hasPrevious = prev != null,
        controlledBy = cast.controlledBy(),
    )

    internal fun publish() {
        if (tornDown) return
        cast.publish(state())
    }

    /**
     * The last episode of a movieffm season: what follows is the next season's
     * first episode — /api/item for the season list, /api/episodes for its episodes.
     */
    private fun resolveNextSeason() {
        cs.launch {
            try {
                val seasons = data.seasons(target)
                val season = (ResumeRules.upNext(target.episodes, target.episodeLabel, seasons, target.seasonUrl) as? UpNext.Season)
                    ?.season ?: return@launch
                val episodes = data.episodes(target.provider, season.url)
                val first = episodes.firstOrNull() ?: return@launch
                next = PlayerRules.neighbour(target, first).copy(
                    seasonLabel = season.label,
                    seasonUrl = season.url,
                    episodes = episodes,
                )
                ui.hasNext = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        /** The one player on screen (`ACTIVE`). */
        private var active: PlayerController? = null
    }
}
