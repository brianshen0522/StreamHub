package com.streamhub.tv.screens.home

import com.streamhub.core.net.RealtimeEvent
import com.streamhub.tv.AppContainer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * `realtime::library_version` as a stream of ticks (`realtime.rs:446-453`,
 * PARITY §9 item 7): one per `favorites` or `progress` frame, and one per
 * socket *re*connect (connected going false → true after an earlier true),
 * because events sent during the outage were never heard.
 */
fun libraryChanges(container: AppContainer): Flow<Unit> = merge(
    container.realtimeEvents
        .filter { it is RealtimeEvent.Favorites || it is RealtimeEvent.Progress }
        .map { },
    reconnects(container.realtime.connected),
)

/** A tick each time [connected] comes back up after having been up before. */
internal fun reconnects(connected: Flow<Boolean>): Flow<Unit> = flow {
    var everUp = false
    var wasUp = false
    connected.collect { up ->
        if (up && !wasUp && everUp) emit(Unit)
        if (up) everUp = true
        wasUp = up
    }
}
