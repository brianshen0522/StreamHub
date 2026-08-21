package com.streamhub.tv

import android.content.Context
import com.streamhub.core.ClientKind
import com.streamhub.core.net.EncryptedSessionStore
import com.streamhub.core.net.RealtimeClient
import com.streamhub.core.net.RealtimeEvent
import com.streamhub.core.net.SessionStore
import com.streamhub.core.net.StreamHubApi
import com.streamhub.tv.cast.TvCastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn

/**
 * Hand-wired dependencies, matching the phone app. One person's app with a
 * handful of objects does not need a DI framework.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** Fixed at build time. Typing a URL on a remote control is nobody's idea of setup. */
    val serverUrl: String = BuildConfig.SERVER_URL

    val sessionStore: SessionStore = EncryptedSessionStore(appContext)
    val handover = Handover()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val api: StreamHubApi by lazy {
        StreamHubApi(serverUrl, sessionStore, ClientKind.TV)
    }

    val realtime: RealtimeClient by lazy {
        RealtimeClient(
            baseUrl = serverUrl,
            store = sessionStore,
            renew = { stale -> api.renewSession(stale) },
        )
    }

    /**
     * One socket for the whole app. On a television this also has to stay up
     * while nothing is playing, because being reachable *is* the feature —
     * a phone can only cast to a television the server can see.
     */
    val realtimeEvents: SharedFlow<RealtimeEvent> by lazy {
        realtime.events()
            .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), replay = 0)
    }

    val castReceiver: TvCastReceiver by lazy {
        TvCastReceiver(realtime, realtimeEvents, scope)
    }
}
