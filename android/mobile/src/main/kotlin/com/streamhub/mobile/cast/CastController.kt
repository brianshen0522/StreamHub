package com.streamhub.mobile.cast

import com.streamhub.core.model.CastCommand
import com.streamhub.core.model.CastPlayRequest
import com.streamhub.core.model.CastReceiver
import com.streamhub.core.net.RealtimeClient
import com.streamhub.core.net.RealtimeEvent
import com.streamhub.mobile.PlaybackRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

/**
 * Which device this phone is currently driving, and how to drive it.
 *
 * Being connected to a television is app-wide state rather than something the
 * player owns, and that is the whole design. Once you pick a device, *every*
 * Play goes there until you say otherwise — the same way Spotify and Netflix
 * behave — so there is no per-screen "cast this" decision to make and no way
 * to end up with the phone and the television playing different things.
 *
 * The connection is remembered by session id, not by holding the receiver
 * object: a television that drops off the socket and comes back is the same
 * device, and the phone should reattach to it rather than silently forget.
 */
class CastController(
    private val realtime: RealtimeClient,
    events: SharedFlow<RealtimeEvent>,
    scope: CoroutineScope,
) {

    private val _receivers = MutableStateFlow<List<CastReceiver>>(emptyList())

    /** Every device on this account that is connected and willing to be driven. */
    val receivers: StateFlow<List<CastReceiver>> = _receivers.asStateFlow()

    private val _targetId = MutableStateFlow<String?>(null)

    /**
     * The device being driven, or null when playback belongs to this phone.
     *
     * Derived rather than stored, so a television that disappears from the
     * socket cannot leave the phone showing controls for something that is no
     * longer listening.
     */
    val target: StateFlow<CastReceiver?> = combine(_receivers, _targetId) { list, id ->
        list.firstOrNull { it.sessionId == id }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * True when a device was chosen but is no longer reachable.
     *
     * Distinct from simply not casting: the phone has to say the television
     * went away rather than quietly dropping back to local playback, or the
     * next Play lands somewhere the person is not looking.
     */
    val lost: StateFlow<Boolean> = combine(_receivers, _targetId) { list, id ->
        id != null && list.none { it.sessionId == id }
    }.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        // Collected for the life of the app rather than per screen: the cast
        // button has to know whether a television is there *before* anyone
        // opens a player, and this subscription is also what keeps the one
        // shared socket alive while the app is in the foreground.
        scope.launch {
            events.collect { event ->
                if (event is RealtimeEvent.Receivers) _receivers.value = event.receivers
            }
        }
    }

    /** Sends everything from now on to [sessionId]. */
    fun connect(sessionId: String) {
        _targetId.value = sessionId
    }

    /**
     * Stops driving the remote device but leaves it playing.
     *
     * Deliberately not a stop: walking away from the remote is not the same as
     * wanting the television to go dark, and the person watching it did not ask
     * for anything to change.
     */
    fun disconnect() {
        _targetId.value = null
    }

    /** Ends playback on the remote device and takes control back to the phone. */
    fun stopAndDisconnect() {
        _targetId.value?.let { realtime.sendCommand(it, CastCommand.Stop) }
        _targetId.value = null
    }

    /**
     * Starts something on the connected device.
     *
     * Returns false when the command could not be put on the wire, which the
     * caller shows rather than leaving a Play button that appears to do
     * nothing.
     */
    fun play(request: PlaybackRequest): Boolean {
        val id = _targetId.value ?: return false
        return realtime.sendCommand(
            id,
            CastCommand.Play(
                CastPlayRequest(
                    streamUrl = request.directUrl,
                    provider = request.providerKey,
                    itemUrl = request.itemUrl,
                    title = request.title,
                    subtitle = listOfNotNull(request.seasonLabel, request.sourceLabel)
                        .joinToString(" · ")
                        .takeIf { it.isNotBlank() },
                    posterUrl = request.posterUrl,
                    episodeLabel = request.episodeLabel,
                    episodeUrl = request.seasonUrl,
                    nextEpisodeLabel = request.nextEpisodeLabel,
                    // The receiver resumes where this account left off, so
                    // handing a title to the television lands in the same place
                    // it would have on the phone.
                    positionMs = request.resumeAtSeconds * 1_000L,
                ),
            ),
        )
    }

    fun pause() = send(CastCommand.Pause)
    fun resume() = send(CastCommand.Resume)
    fun next() = send(CastCommand.Next)
    fun seekTo(positionMs: Long) = send(CastCommand.Seek(positionMs))

    private fun send(command: CastCommand): Boolean {
        val id = _targetId.value ?: return false
        return realtime.sendCommand(id, command)
    }
}
