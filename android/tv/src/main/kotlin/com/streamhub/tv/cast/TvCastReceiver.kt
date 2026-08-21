package com.streamhub.tv.cast

import com.streamhub.core.model.CastCommand
import com.streamhub.core.model.CastPlayRequest
import com.streamhub.core.model.CastPlaybackState
import com.streamhub.core.net.RealtimeClient
import com.streamhub.core.net.RealtimeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * This television, seen from a phone.
 *
 * Two halves. Announcing: the server only lists devices that have sent a
 * playback frame, so an idle television has to send one with a null state or it
 * never appears as a cast target at all. Obeying: commands arrive on the same
 * socket and are split by what has to handle them — a `play` is a navigation
 * decision the app root makes, while a pause or a seek belongs to whatever
 * player is on screen.
 *
 * The split matters because the two have different lifetimes. A `play` can
 * arrive when no player exists yet, so it is held as state until something
 * consumes it; a pause is meaningless if nothing is playing, so it is a
 * one-shot event that is simply missed.
 */
class TvCastReceiver(
    private val realtime: RealtimeClient,
    events: SharedFlow<RealtimeEvent>,
    scope: CoroutineScope,
) {

    private val _pendingPlay = MutableStateFlow<CastPlayRequest?>(null)

    /** A title a phone asked this television to start. Cleared once acted on. */
    val pendingPlay: StateFlow<CastPlayRequest?> = _pendingPlay.asStateFlow()

    private val _transport = MutableSharedFlow<CastCommand>(extraBufferCapacity = 16)

    /** Pause, resume, seek, stop and next, for the player that is on screen. */
    val transport: SharedFlow<CastCommand> = _transport.asSharedFlow()

    private val _controlledBy = MutableStateFlow<String?>(null)

    /**
     * The phone currently driving, by name.
     *
     * Shown on screen when a command arrives: someone watching the television
     * should be able to see that the picture changed because a person with a
     * phone did it, not because the app misbehaved.
     */
    val controlledBy: StateFlow<String?> = _controlledBy.asStateFlow()

    init {
        scope.launch {
            events.collect { event ->
                if (event !is RealtimeEvent.Command) return@collect
                _controlledBy.value = event.fromName
                when (val command = event.command) {
                    is CastCommand.Play -> _pendingPlay.value = command.playback
                    else -> _transport.emit(command)
                }
            }
        }

        // Announce as soon as there is a socket, and again after every
        // reconnect: the server keeps the receiver list in memory only, so a
        // dropped connection means this television has been forgotten.
        scope.launch {
            while (true) {
                realtime.connected.first { it }
                publish(null)
                realtime.connected.first { !it }
            }
        }
    }

    fun consumePendingPlay() {
        _pendingPlay.value = null
    }

    /** Tells the account's phones what this television is doing. */
    fun publish(state: CastPlaybackState?) {
        realtime.publishPlayback(state)
    }
}
