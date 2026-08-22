package com.streamhub.mobile

import android.content.Context
import com.streamhub.core.ClientKind
import com.streamhub.core.net.EncryptedSessionStore
import com.streamhub.core.net.RealtimeClient
import com.streamhub.core.net.RealtimeEvent
import com.streamhub.core.net.SessionStore
import com.streamhub.core.net.StreamHubApi
import com.streamhub.mobile.cast.CastController
import com.streamhub.mobile.downloads.DownloadService
import com.streamhub.mobile.downloads.DownloadsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn

/**
 * Hand-wired dependencies. One person's app with a handful of objects does not
 * need a DI framework, and the build stays simpler without one.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Fixed at build time. There is one deployment, so asking each user for its
     * address was a setup step with exactly one right answer — and a chance to
     * get it wrong before ever reaching the password field.
     */
    val serverUrl: String = BuildConfig.SERVER_URL

    val sessionStore: SessionStore = EncryptedSessionStore(appContext)
    val handover = Handover()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Built once, because the address can no longer change under them.
    val api: StreamHubApi by lazy {
        StreamHubApi(serverUrl, sessionStore, ClientKind.PHONE)
    }

    val realtime: RealtimeClient by lazy {
        RealtimeClient(
            baseUrl = serverUrl,
            store = sessionStore,
            renew = { stale -> api.renewSession(stale) },
        )
    }

    /**
     * One socket for the whole app, not one per screen. Each `events()`
     * collection opens its own connection, so the flow is shared: several
     * screens subscribing at once share a single socket, and it closes shortly
     * after the last of them goes away.
     */
    val realtimeEvents: SharedFlow<RealtimeEvent> by lazy {
        realtime.events()
            .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), replay = 0)
    }

    /**
     * Which device this phone is driving. App-wide rather than owned by the
     * player, because the choice outlives any one screen: picking a television
     * on the detail page has to still hold when the next episode starts.
     */
    val cast: CastController by lazy {
        CastController(realtime, realtimeEvents, scope)
    }

    /**
     * Downloads survive the process, so their owner is the container rather
     * than any screen. The service runs exactly while something is moving —
     * that callback is the only coupling between the two.
     */
    val downloads: DownloadsRepository by lazy {
        DownloadsRepository(
            context = appContext,
            api = api,
            onActiveChanged = { count -> DownloadService.update(appContext, count) },
        )
    }
}
