package com.streamhub.core.net

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

    /** A type this build does not know. A newer server can add events safely. */
    data class Unknown(val type: String) : RealtimeEvent
}
