package com.streamhub.mobile

import android.content.Context
import com.streamhub.core.ClientKind
import com.streamhub.core.net.EncryptedSessionStore
import com.streamhub.core.net.RealtimeClient
import com.streamhub.core.net.SessionStore
import com.streamhub.core.net.StreamHubApi

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

    private var cached: Pair<String, StreamHubApi>? = null

    /** Rebuilt whenever the server address changes, which it can at any sign-in. */
    fun api(): StreamHubApi {
        val url = settings.baseUrl
        cached?.let { (cachedUrl, api) -> if (cachedUrl == url) return api }
        val api = StreamHubApi(url, sessionStore, ClientKind.PHONE)
        cached = url to api
        return api
    }

    fun realtime(): RealtimeClient {
        val api = api()
        return RealtimeClient(
            baseUrl = settings.baseUrl,
            store = sessionStore,
            renew = { stale -> api.renewSession(stale) },
        )
    }
}
