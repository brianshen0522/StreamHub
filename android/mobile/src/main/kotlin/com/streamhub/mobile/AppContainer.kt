package com.streamhub.mobile

import android.content.Context
import com.streamhub.core.ClientKind
import com.streamhub.core.net.EncryptedSessionStore
import com.streamhub.core.net.RealtimeClient
import com.streamhub.core.net.RealtimeEvent
import com.streamhub.core.net.SessionStore
import com.streamhub.core.net.StreamHubApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn

/**
 * Where the server lives.
 *
 * A client cannot hard-code this: the server is self-hosted, so its address is
 * whatever the person running it chose, and it differs between the home network
 * and outside. It is not a secret, so plain preferences are the right place —
 * unlike the tokens, which are encrypted.
 */
class ServerSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("streamhub.settings", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_BASE_URL, normalize(value)).apply()

    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    private companion object {
        const val KEY_BASE_URL = "baseUrl"

        /** People type "192.168.1.10:8787"; make that work rather than fail. */
        fun normalize(input: String): String {
            val trimmed = input.trim().trimEnd('/')
            if (trimmed.isEmpty()) return ""
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "http://$trimmed"
            }
        }
    }
}

/**
 * Hand-wired dependencies. One person's app with a handful of objects does not
 * need a DI framework, and the build stays simpler without one.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val settings = ServerSettings(appContext)
    val sessionStore: SessionStore = EncryptedSessionStore(appContext)
    val handover = Handover()

    private var cached: Pair<String, StreamHubApi>? = null

    /**
     * Rebuilt whenever the server address changes, which it can at any sign-in.
     *
     * There is nothing sensible to return before an address is known, so this
     * says so rather than letting the URL parser throw something cryptic from
     * deep inside the client.
     */
    fun api(): StreamHubApi {
        val url = settings.baseUrl
        require(url.isNotBlank()) { "No server address has been set yet." }
        cached?.let { (cachedUrl, api) -> if (cachedUrl == url) return api }
        val api = StreamHubApi(url, sessionStore, ClientKind.PHONE)
        cached = url to api
        return api
    }

    private var cachedRealtime: Pair<String, RealtimeClient>? = null

    /**
     * One socket for the whole app, not one per screen. Each `events()`
     * collection opens its own connection, so the flow is shared: several
     * screens subscribing at once share a single socket, and it closes shortly
     * after the last of them goes away.
     */
    fun realtime(): RealtimeClient {
        val url = settings.baseUrl
        cachedRealtime?.let { (cachedUrl, client) -> if (cachedUrl == url) return client }
        val api = api()
        val client = RealtimeClient(
            baseUrl = url,
            store = sessionStore,
            renew = { stale -> api.renewSession(stale) },
        )
        cachedRealtime = url to client
        sharedEvents = null
        return client
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sharedEvents: SharedFlow<RealtimeEvent>? = null

    fun realtimeEvents(): SharedFlow<RealtimeEvent> {
        sharedEvents?.let { return it }
        val shared = realtime().events()
            .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), replay = 0)
        sharedEvents = shared
        return shared
    }
}
