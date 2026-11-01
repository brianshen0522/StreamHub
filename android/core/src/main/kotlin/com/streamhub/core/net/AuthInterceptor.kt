package com.streamhub.core.net

import android.os.Build
import com.streamhub.core.ApiConfig
import com.streamhub.core.ClientKind
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Puts the bearer token and the client identity on every request to this server.
 *
 * The client header is not decoration: it is what makes the server refuse an
 * admin account at login and refresh, instead of handing back a session that
 * 403s on every content screen afterwards. It also has to be on *every*
 * request to the server, not just the first — a master playlist sends the
 * player back to /api/manifest for the variant it picks.
 *
 * Only to the server, though. This client also carries the player's segment
 * requests and direct poster loads, and those go to whichever CDN a provider
 * uses. A token sent there is a token handed to a stranger.
 */
class AuthInterceptor(
    private val store: SessionStore,
    private val clientKind: ClientKind,
    private val apiRoot: HttpUrl,
) : Interceptor {

    private val userAgent = userAgentFor(clientKind)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request.newBuilder()
            // The server records this against the session, and it is what the
            // device list shows. OkHttp's default is "okhttp/5.x", which tells
            // the account holder nothing about which of their devices this is.
            .header("User-Agent", userAgent)

        if (request.url.sameOriginAs(apiRoot)) {
            builder.header(ApiConfig.CLIENT_HEADER, clientKind.header)
            store.load()?.accessToken?.takeIf { it.isNotEmpty() }?.let { token ->
                builder.header("Authorization", "Bearer $token")
            }
        }

        return chain.proceed(builder.build())
    }
}

internal fun HttpUrl.sameOriginAs(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port

/**
 * How this device names itself to the server, which is what the account's device
 * list shows. OkHttp's default is "okhttp/5.x", which tells the account holder
 * nothing about which of their devices they are looking at.
 *
 * It has to be sent by the unauthenticated client too: the session row — and
 * with it the recorded user agent — is created by the sign-in request, which by
 * definition does not carry a token.
 */
fun userAgentFor(clientKind: ClientKind): String = buildString {
    append("StreamHub-")
    append(clientKind.header.replaceFirstChar { it.uppercase() })
    append("/")
    append(ApiConfig.CLIENT_VERSION)
    append(" (")
    append(Build.MODEL ?: "Android")
    append("; Android ")
    append(Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString())
    append(")")
}
