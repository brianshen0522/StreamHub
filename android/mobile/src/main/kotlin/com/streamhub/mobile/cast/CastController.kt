package com.streamhub.mobile.cast

import com.streamhub.core.model.CastCommand
import com.streamhub.core.model.CastPlayRequest
import com.streamhub.core.model.CastPlaybackState
import com.streamhub.core.model.CastReceiver
import com.streamhub.core.net.RealtimeClient
import com.streamhub.core.net.RealtimeEvent
import com.streamhub.mobile.MediaSelection
import com.streamhub.mobile.PlaybackRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
 * device (the server's session id survives reconnects), and the phone
 * reattaches to it rather than silently forgetting.
 *
 * Takes the socket as three plain pieces rather than a [RealtimeClient] so it
 * can be driven by a fake in tests; see the secondary constructor.
 */
class CastController(
    events: Flow<RealtimeEvent>,
    /** This phone's socket has answered `ready`. */
    private val linkUp: StateFlow<Boolean>,
    /** This phone's own session id, never offered as a target. */
    private val ownSessionId: StateFlow<String?>,
    private val sendCommand: (to: String, command: CastCommand) -> Boolean,
    private val scope: CoroutineScope,
    /**
     * How long this phone's own socket may be down before the receiver list
     * is treated as stale. Short enough that a dead link is admitted, long
     * enough that the reconnect after a token renewal does not flash
     * "disconnected" every quarter of an hour.
     */
    private val linkGraceMs: Long = 4_000,
) {

    constructor(realtime: RealtimeClient, events: Flow<RealtimeEvent>, scope: CoroutineScope) : this(
        events = events,
        linkUp = realtime.connected,
        ownSessionId = realtime.sessionId,
        sendCommand = realtime::sendCommand,
        scope = scope,
    )

    /** Whether a [play] reached the wire, and if not, why. */
    enum class PlayOutcome {
        /** No device chosen: play here. */
        LOCAL,
        /** Sent to the chosen device. */
        SENT,
        /** A device is chosen but cannot be reached. Do not quietly play here instead. */
        UNREACHABLE,
        /** A downloaded file: a path on this phone means nothing to a television. */
        LOCAL_ONLY,
    }

    private val _all = MutableStateFlow<List<CastReceiver>>(emptyList())

    /**
     * Every device on this account that is connected and willing to be
     * driven, except this phone itself.
     */
    val receivers: StateFlow<List<CastReceiver>> = combine(_all, ownSessionId) { list, own ->
        if (own == null) list else list.filter { it.sessionId != own }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _targetId = MutableStateFlow<String?>(null)

    /** The chosen device as last seen, kept while it is unreachable. */
    private val _lastSeen = MutableStateFlow<CastReceiver?>(null)

    /**
     * The device being driven, or null when playback belongs to this phone.
     *
     * While the device is unreachable this is its last known picture and
     * [lost] is true. It used to go null instead, which made the remote screen
     * close itself on any blip and every Play fall silently back to the phone
     * while the cast button still said "television".
     */
    val target: StateFlow<CastReceiver?> = combine(receivers, _targetId, _lastSeen) { list, id, seen ->
        if (id == null) null else list.firstOrNull { it.sessionId == id } ?: seen?.takeIf { it.sessionId == id }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * True when a device was chosen but is no longer reachable — it left the
     * receiver list, or this phone's own socket is down.
     *
     * Distinct from simply not casting: the phone has to say the television
     * went away rather than quietly dropping back to local playback, or the
     * next Play lands somewhere the person is not looking.
     */
    val lost: StateFlow<Boolean> = combine(receivers, _targetId) { list, id ->
        id != null && list.none { it.sessionId == id }
    }.stateIn(scope, SharingStarted.Eagerly, false)

    private val _assumedPaused = MutableStateFlow<Boolean?>(null)
    private var assumptionTimeout: Job? = null

    /**
     * Whether the remote should show "paused": what the last press asked for
     * until the device confirms it (or [ASSUME_MS] passes), otherwise what it
     * reported. Shared by the remote screen and the cast bar, so a press on
     * one is not contradicted by the other. Without it the button lagged a
     * round-trip behind the thumb — long enough to press again and undo the
     * first press.
     */
    val paused: StateFlow<Boolean> = combine(target, _assumedPaused) { t, assumed ->
        assumed ?: (t?.state?.paused ?: true)
    }.stateIn(scope, SharingStarted.Eagerly, true)

    private val _starting = MutableStateFlow<CastPlayRequest?>(null)
    private var startTimeout: Job? = null

    /**
     * A play sent and not yet visible in the device's reports. The remote
     * shows this title rather than whatever the television was on before (or
     * "Nothing playing"), with the transport disabled until it arrives.
     */
    val starting: StateFlow<CastPlayRequest?> = _starting.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)

    /** A one-line problem to show on the remote, cleared by [clearNotice]. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    init {
        // Collected for the life of the app rather than per screen: the cast
        // button has to know whether a television is there *before* anyone
        // opens a player, and this subscription is also what keeps the one
        // shared socket alive.
        scope.launch {
            events.collect { event ->
                if (event is RealtimeEvent.Receivers) onReceivers(event.receivers)
            }
        }
        // The list is only as fresh as this phone's socket. Down for longer
        // than the grace, it is a list of devices that may well be gone, and
        // offering them invites commands into nothing. The `ready` after a
        // reconnect carries the current list.
        scope.launch {
            linkUp.collectLatest { up ->
                if (up) return@collectLatest
                delay(linkGraceMs)
                _all.value = emptyList()
            }
        }
    }

    private fun onReceivers(list: List<CastReceiver>) {
        _all.value = list
        val id = _targetId.value ?: return
        val mine = list.firstOrNull { it.sessionId == id } ?: return
        _lastSeen.value = mine
        val state = mine.state

        val assumed = _assumedPaused.value
        if (assumed != null && state != null && state.paused == assumed) clearAssumption()

        val pending = _starting.value
        if (pending != null && state != null && isShowing(state, pending)) {
            startTimeout?.cancel()
            _starting.value = null
        }
    }

    /** Sends everything from now on to [sessionId]. */
    fun connect(sessionId: String) {
        if (_targetId.value != sessionId) reset()
        _targetId.value = sessionId
        _lastSeen.value = _all.value.firstOrNull { it.sessionId == sessionId }
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
        _lastSeen.value = null
        reset()
    }

    /** Ends playback on the remote device and takes control back to the phone. */
    fun stopAndDisconnect() {
        _targetId.value?.let { if (!lost.value) sendCommand(it, CastCommand.Stop) }
        disconnect()
    }

    /** Where a Play goes, and whether it got there. */
    fun play(request: PlaybackRequest): PlayOutcome {
        val id = _targetId.value ?: return PlayOutcome.LOCAL
        if (request.directUrl.startsWith("file:")) return PlayOutcome.LOCAL_ONLY
        if (lost.value) return PlayOutcome.UNREACHABLE
        val playback = playRequest(request)
        if (!sendCommand(id, CastCommand.Play(playback))) return PlayOutcome.UNREACHABLE
        clearAssumption()
        _notice.value = null
        _starting.value = playback
        startTimeout?.cancel()
        startTimeout = scope.launch {
            delay(START_TIMEOUT_MS)
            if (_starting.value === playback) {
                _starting.value = null
                _notice.value = "${target.value?.deviceName ?: "The device"} did not start ${playback.title ?: "it"}."
            }
        }
        return PlayOutcome.SENT
    }

    /** Pause if playing, resume if paused, showing the result at once. */
    fun togglePause(): Boolean {
        val next = !paused.value
        if (!send(if (next) CastCommand.Pause else CastCommand.Resume)) return false
        _assumedPaused.value = next
        assumptionTimeout?.cancel()
        assumptionTimeout = scope.launch {
            delay(ASSUME_MS)
            _assumedPaused.value = null
        }
        return true
    }

    fun fullscreen() = send(CastCommand.Fullscreen)
    fun next() = send(CastCommand.Next)
    fun previous() = send(CastCommand.Previous)
    fun seekTo(positionMs: Long) = send(CastCommand.Seek(positionMs.coerceAtLeast(0)))

    fun clearNotice() {
        _notice.value = null
    }

    /**
     * Every command goes through here, and a failure says so: a remote whose
     * buttons do nothing without comment reads as broken, not as offline.
     */
    private fun send(command: CastCommand): Boolean {
        val id = _targetId.value ?: return false
        val sent = !lost.value && sendCommand(id, command)
        _notice.value = if (sent) null else "${target.value?.deviceName ?: "The device"} is not reachable right now."
        return sent
    }

    private fun clearAssumption() {
        assumptionTimeout?.cancel()
        _assumedPaused.value = null
    }

    private fun reset() {
        clearAssumption()
        startTimeout?.cancel()
        _starting.value = null
        _notice.value = null
    }

    companion object {
        /** How long an optimistic play/pause stands without confirmation. */
        const val ASSUME_MS = 2_500L

        /** How long a play may take to show up in the device's reports. */
        const val START_TIMEOUT_MS = 20_000L

        /** What the receiver needs to start [request] without asking again. */
        fun playRequest(request: PlaybackRequest) = CastPlayRequest(
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
            prevEpisodeLabel = request.prevEpisodeLabel,
            // The receiver resumes where this account left off, so handing a
            // title to the television lands in the same place it would have
            // on the phone.
            positionMs = request.resumeAtSeconds * 1_000L,
        )

        /**
         * Whether [state] is the thing [request] asked for. A television
         * reports a movie's episode as "" where the phone has null.
         */
        fun isShowing(state: CastPlaybackState, request: CastPlayRequest): Boolean =
            state.itemUrl == request.itemUrl &&
                state.episodeLabel.orEmpty() == request.episodeLabel.orEmpty()

        /**
         * What "Play on this phone" should open, given what the device is
         * showing and the last thing this phone started.
         *
         * The phone's last request is only the right one if the television is
         * still on it: after the television's own next-episode, or a title
         * someone else started, replaying it would put the wrong episode on
         * the phone. Then the title's detail page is the honest answer.
         */
        fun handBack(state: CastPlaybackState?, last: PlaybackRequest?, positionMs: Long): HandBack {
            if (state == null || state.idle) return HandBack.Nothing
            val itemUrl = state.itemUrl?.takeIf { it.isNotEmpty() } ?: return HandBack.Nothing
            if (last != null && last.itemUrl == itemUrl &&
                last.episodeLabel.orEmpty() == state.episodeLabel.orEmpty() &&
                !last.directUrl.startsWith("file:")
            ) {
                return HandBack.Play(last.copy(resumeAtSeconds = (positionMs / 1000).toInt().coerceAtLeast(0)))
            }
            val provider = state.provider ?: last?.providerKey ?: return HandBack.Nothing
            val mediaType = when {
                last != null && last.itemUrl == itemUrl -> last.mediaType
                state.episodeLabel.isNullOrEmpty() -> "movie"
                else -> "tv"
            }
            return HandBack.Open(
                MediaSelection(
                    provider = provider,
                    itemUrl = itemUrl,
                    title = state.title.orEmpty(),
                    mediaType = mediaType,
                    posterUrl = state.posterUrl,
                ),
            )
        }
    }

    sealed interface HandBack {
        /** The device is idle: just take control back. */
        data object Nothing : HandBack
        data class Play(val request: PlaybackRequest) : HandBack
        data class Open(val selection: MediaSelection) : HandBack
    }
}
