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

    init {
        installModernTls()
    }

    private val appContext = context.applicationContext

    /** Fixed at build time. Typing a URL on a remote control is nobody's idea of setup. */
    val serverUrl: String = BuildConfig.SERVER_URL

    val sessionStore: SessionStore = EncryptedSessionStore(appContext)

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

    /**
     * The cast receiver (Tizen's realtime.rs on top of the socket). Built
     * with the container — MainActivity touches it before the first frame —
     * because an idle television must announce itself to be castable at all.
     */
    val castReceiver: TvCastReceiver by lazy {
        TvCastReceiver(
            events = realtimeEvents,
            connected = realtime.connected,
            send = { state -> realtime.publishPlayback(state) },
            scope = scope,
            main = Dispatchers.Main.immediate,
            clock = { android.os.SystemClock.uptimeMillis() },
            sessionToken = { sessionStore.load()?.accessToken?.takeIf { it.isNotEmpty() } },
            heartbeat = { api.heartbeat() },
        )
    }

    /** What the player asks the server. */
    val playerData: com.streamhub.tv.player.PlayerData by lazy {
        com.streamhub.tv.player.ApiPlayerData(api, serverUrl)
    }

    /** Fire-and-forget writes (progress, source preference) that must outlive the screen that sent them. */
    val backgroundScope: CoroutineScope get() = scope

    /**
     * Give the app its own TLS stack instead of the one the television shipped
     * with.
     *
     * Android 7's is from 2016, and the server will not talk to it: it answers
     * that ClientHello with a handshake_failure alert and hangs up, before any
     * certificate is exchanged. Bundling root certificates cannot help with
     * that — those settle whether a certificate is *trusted*, and this
     * connection never gets as far as one. What is missing is the modern
     * ciphers and curves the server insists on, and Conscrypt is that same
     * stack packaged as a library, so installing it ahead of the platform's own
     * provider is enough.
     *
     * Registered before anything builds a client, which is why this is in the
     * container's constructor rather than beside the code that makes calls.
     * Failure is not fatal: a newer television has a perfectly good provider of
     * its own and simply carries on with it.
     */
    private fun installModernTls() {
        if (java.security.Security.getProvider(CONSCRYPT) != null) return
        runCatching {
            java.security.Security.insertProviderAt(
                org.conscrypt.Conscrypt.newProviderBuilder().setName(CONSCRYPT).build(),
                1,
            )
        }
    }

    private companion object {
        const val CONSCRYPT = "Conscrypt"
    }
}
