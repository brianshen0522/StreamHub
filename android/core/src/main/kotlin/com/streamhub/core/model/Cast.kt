package com.streamhub.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Driving playback on another of the account's devices.
 *
 * There is no pairing step and no device code. Both devices already signed in
 * to the same account, and the server only ever routes a command between two
 * sockets belonging to the same user — holding the account *is* the
 * authorization. Nothing here is discovered over the local network either, so
 * the phone and the television do not have to be on the same Wi-Fi.
 */

/** What a receiver reports it is doing, about once a second while it plays. */
@Serializable
data class CastPlaybackState(
    val provider: String? = null,
    val itemUrl: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val posterUrl: String? = null,
    val episodeLabel: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val paused: Boolean = false,
    val buffering: Boolean = false,
) {
    /** Nothing loaded. The device is reachable but showing its own UI. */
    val idle: Boolean get() = title == null && durationMs == 0L
}

/** A device on this account that is connected and willing to be driven. */
@Serializable
data class CastReceiver(
    val sessionId: String,
    val deviceName: String,
    val clientKind: String? = null,
    val state: CastPlaybackState? = null,
) {
    val isTelevision: Boolean get() = clientKind == "tv"
}

/** Everything a receiver needs to start playing without asking the phone again. */
@Serializable
data class CastPlayRequest(
    val streamUrl: String,
    val provider: String? = null,
    val itemUrl: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val posterUrl: String? = null,
    val episodeLabel: String? = null,
    val episodeUrl: String? = null,
    /** What follows this episode, so the receiver keeps its own next control. */
    val nextEpisodeLabel: String? = null,
    val positionMs: Long = 0,
)

/**
 * The commands a controller may send.
 *
 * Serialized with `action` as the discriminator so the wire form is the flat
 * `{"action":"seek","positionMs":…}` the server validates, rather than
 * kotlinx's default nested envelope.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("action")
sealed class CastCommand {

    @Serializable
    @SerialName("play")
    data class Play(val playback: CastPlayRequest) : CastCommand()

    @Serializable
    @SerialName("seek")
    data class Seek(val positionMs: Long) : CastCommand()

    @Serializable
    @SerialName("pause")
    data object Pause : CastCommand()

    @Serializable
    @SerialName("resume")
    data object Resume : CastCommand()

    @Serializable
    @SerialName("stop")
    data object Stop : CastCommand()

    @Serializable
    @SerialName("next")
    data object Next : CastCommand()
}
