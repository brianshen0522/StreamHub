package com.streamhub.core.net

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Renews the access token when a request comes back 401, and retries it once.
 *
 * The renewal itself lives in [TokenRefresher], which the realtime socket shares
 * — see the note there about why a second concurrent refresh destroys the
 * session.
 *
 * OkHttp only consults an Authenticator on a 401 and stops when this returns
 * null, which is what bounds the retry to one.
 */
internal class TokenAuthenticator(
    private val refresher: TokenRefresher,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // Give up rather than loop: one renewal per original request.
        if (response.priorResponse != null) return null

        val attempted = response.request.header("Authorization")
            ?.removePrefix("Bearer ")
            ?.trim()

        val session = refresher.refresh(attempted) ?: return null
        return response.request.newBuilder()
            .header("Authorization", "Bearer ${session.accessToken}")
            .build()
    }
}
