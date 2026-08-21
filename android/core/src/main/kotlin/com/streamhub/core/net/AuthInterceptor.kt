package com.streamhub.core.net

import com.streamhub.core.ApiConfig
import com.streamhub.core.ClientKind
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Puts the bearer token and the client identity on every request.
 *
 * The client header is not decoration: it is what makes the server refuse an
 * admin account at login and refresh, instead of handing back a session that
 * 403s on every content screen afterwards. It also has to be on *every*
 * request, not just the first — a master playlist sends the player back to
 * /api/manifest for the variant it picks.
 */
class AuthInterceptor(
    private val store: SessionStore,
    private val clientKind: ClientKind,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder()
            .header(ApiConfig.CLIENT_HEADER, clientKind.header)

        store.load()?.accessToken?.takeIf { it.isNotEmpty() }?.let { token ->
            builder.header("Authorization", "Bearer $token")
        }

        return chain.proceed(builder.build())
    }
}
