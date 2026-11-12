package com.streamhub.tv.cast

import com.streamhub.core.model.CastCommand
import com.streamhub.core.model.CastPlayRequest
import com.streamhub.core.model.CastPlaybackState
import com.streamhub.core.net.RealtimeEvent
import com.streamhub.tv.nav.PlayTarget
import com.streamhub.tv.player.SourceLine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.floor

/** What the on-screen player needs from the cast channel (`realtime.rs` "API for the player"). */
interface CastHub {
    /** Say what this set is doing (null = idle). Remembered and re-sent after every reconnect. */
    fun publish(state: CastPlaybackState?)

    /** Register the player for transport commands; the id clears only this registration. */
    fun setCommandHandler(handler: (CastCommand) -> Unit): Long

    fun clearCommandHandler(id: Long)

    /** The phone that last drove this set, while that is fresh (12 s). */
    fun controlledBy(): String?

    object None : CastHub {
        override fun publish(state: CastPlaybackState?) = Unit
        override fun setCommandHandler(handler: (CastCommand) -> Unit) = 0L
        override fun clearCommandHandler(id: Long) = Unit
        override fun controlledBy(): String? = null
    }
}

/**
 * This television on the cast channel: a port of the receiver half of
 * `tizen/app/src/realtime.rs`. The socket itself (auth frame, backoff, 4002 →
 * refresh → reconnect, 4003) is `:core`'s `RealtimeClient`; this is what Tizen
 * does on top of it.
 *
 * - **Announce.** The server lists a receiver once it has sent a playback
 *   frame, and forgets it on every reconnect. On each `ready` the *last*
 *   published state is sent again — `null` only when idle — so a playing
 *   television reappears in the phones' pickers as playing, not idle.
 * - **Library.** `favorites` / `progress` frames, and every reconnect after
 *   the first ready of a sign-in (events sent while the socket was down were
 *   never heard), bump [libraryVersion]; screens refetch on it.
 * - **Commands.** `play` becomes a [PlayTarget] in [pendingPlay] for the app
 *   root (push, or replace a playing player). Transport commands go to the
 *   registered player and are dropped with none; `fullscreen` and anything
 *   unknown are ignored entirely. A command's `fromName` is the driver, fresh
 *   for 12 s.
 * - **Heartbeat.** `POST /auth/heartbeat` as soon as a session appears, then
 *   every 45 s.
 *
 * There is no "refuse remote control": Tizen has none, and never withdraws.
 *
 * Everything that touches state runs on [main].
 */
class TvCastReceiver(
    events: Flow<RealtimeEvent>,
    connected: StateFlow<Boolean>,
    private val send: (CastPlaybackState?) -> Boolean,
    scope: CoroutineScope,
    main: CoroutineDispatcher,
    private val clock: () -> Long,
    private val sessionToken: () -> String? = { null },
    private val heartbeat: (suspend () -> Unit)? = null,
) : CastHub {

    private val _pendingPlay = MutableStateFlow<PlayTarget?>(null)

    /** A title a phone asked this television to start. Cleared once acted on. */
    val pendingPlay: StateFlow<PlayTarget?> = _pendingPlay.asStateFlow()

    private val _libraryVersion = MutableStateFlow(0L)

    /** Ticks on every favourites/progress event and on every reconnect (`library_version`). */
    val libraryVersion: StateFlow<Long> = _libraryVersion.asStateFlow()

    /** What this set last said it is doing — re-sent after every reconnect. */
    var lastState: CastPlaybackState? = null
        private set

    private var everReady = false
    private var driver: Pair<String, Long>? = null
    private var handler: Pair<Long, (CastCommand) -> Unit>? = null
    private var handlerSeq = 0L

    init {
        scope.launch(main) { events.collect(::onEvent) }
        scope.launch(main) {
            var was = false
            connected.collect { now ->
                if (now && !was) onReady()
                was = now
            }
        }
        if (heartbeat != null) {
            scope.launch(main) {
                var lastToken: String? = null
                var lastBeat = 0L
                while (true) {
                    val token = sessionToken()
                    val t = clock()
                    if (token != null && (lastToken == null || t - lastBeat >= HEARTBEAT_MS)) {
                        lastBeat = t
                        launch { runCatching { heartbeat.invoke() } }
                    }
                    lastToken = token
                    delay(1_000)
                }
            }
        }
    }

    /** `ready`: announce the last state, and refetch the library after a re-connect. */
    internal fun onReady() {
        send(lastState)
        if (everReady) _libraryVersion.value += 1
        everReady = true
    }

    internal fun onEvent(event: RealtimeEvent) {
        when (event) {
            is RealtimeEvent.Favorites, is RealtimeEvent.Progress -> _libraryVersion.value += 1
            is RealtimeEvent.Command -> onCommand(event)
            else -> Unit
        }
    }

    private fun onCommand(event: RealtimeEvent.Command) {
        val command = event.command
        // Fullscreen (a television already is), and anything newer: not even a driver.
        if (command is CastCommand.Fullscreen) return
        val from = event.fromName.orEmpty()
        if (from.isNotEmpty()) driver = from to clock()
        when (command) {
            is CastCommand.Play -> castPlayTarget(command.playback)?.let { _pendingPlay.value = it }
            else -> handler?.second?.invoke(command)
        }
    }

    fun consumePendingPlay() {
        _pendingPlay.value = null
    }

    /** Signing out forgets the state, the driver and the "has been ready" mark (`on_token(None)`). */
    fun onSignedOut() {
        lastState = null
        driver = null
        everReady = false
        _pendingPlay.value = null
    }

    override fun publish(state: CastPlaybackState?) {
        lastState = state
        send(state)
    }

    override fun setCommandHandler(handler: (CastCommand) -> Unit): Long {
        handlerSeq += 1
        this.handler = handlerSeq to handler
        return handlerSeq
    }

    override fun clearCommandHandler(id: Long) {
        if (handler?.first == id) handler = null
    }

    override fun controlledBy(): String? =
        driver?.takeIf { clock() - it.second < DRIVER_FRESH_MS }?.first

    companion object {
        /** The "controlled by" badge's freshness window, the same 12 s as the phones'. */
        const val DRIVER_FRESH_MS = 12_000L
        const val HEARTBEAT_MS = 45_000L
    }
}

/**
 * A cast `play` as a [PlayTarget] (`realtime.rs` `play_target`). `streamUrl`
 * is the provider's direct URL; the player resolves `/api/manifest` from it.
 * `episodeUrl` is the progress key's season URL. The episode list is just
 * [prev?, episode, next?], so the player keeps its own next/previous.
 */
fun castPlayTarget(p: CastPlayRequest): PlayTarget? {
    if (p.streamUrl.isEmpty()) return null
    val episode = p.episodeLabel.orEmpty()
    val episodes = buildList {
        if (episode.isNotEmpty()) {
            p.prevEpisodeLabel?.takeIf { it.isNotEmpty() }?.let(::add)
            add(episode)
            p.nextEpisodeLabel?.takeIf { it.isNotEmpty() }?.let(::add)
        }
    }
    return PlayTarget(
        provider = p.provider.orEmpty(),
        itemUrl = p.itemUrl.orEmpty(),
        seasonUrl = p.episodeUrl.orEmpty(),
        title = p.title.orEmpty(),
        posterUrl = p.posterUrl.orEmpty(),
        mediaType = "",
        seasonLabel = "",
        episodeLabel = episode,
        episodes = episodes,
        source = SourceLine(directUrl = p.streamUrl, sourceLabel = p.subtitle.orEmpty()),
        startSeconds = floor(p.positionMs.coerceAtLeast(0) / 1000.0),
        fromCast = true,
    )
}
