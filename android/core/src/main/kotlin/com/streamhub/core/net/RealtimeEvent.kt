package com.streamhub.core.net

import com.streamhub.core.model.CastCommand
import com.streamhub.core.model.CastReceiver

/**
 * What the server pushes down the realtime socket.
 *
 * Events say only *what changed*, never the new value, so a subscriber refetches
 * rather than merging a delta. That is what keeps two devices consistent without
 * either of them having to reason about ordering.
 */
sealed interface RealtimeEvent {

    /** A favorite was added or removed on some device belonging to this user. */
    data class Favorites(val action: String, val id: String?) : RealtimeEvent

    /**
     * Watch progress changed. [historyChanged] is the server telling the client
     * whether the history list also needs refetching, which it only does for
     * some events — refetching it every time would be wasted work.
     */
    data class Progress(val action: String, val historyChanged: Boolean) : RealtimeEvent

    /**
     * The set of devices on this account currently willing to be driven.
     *
     * Unlike the events above this one carries its value, because there is
     * nothing to refetch: the receiver list only exists in the server's memory
     * for as long as those sockets are open.
     */
    data class Receivers(val receivers: List<CastReceiver>) : RealtimeEvent

    /** Another of this account's devices is telling this one what to do. */
    data class Command(
        val from: String?,
        val fromName: String?,
        val command: CastCommand,
    ) : RealtimeEvent

    /** A type this build does not know. A newer server can add events safely. */
    data class Unknown(val type: String) : RealtimeEvent
}
