package com.streamhub.core.net

import okhttp3.Authenticator
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException

/**
 * Renews the access token when a request comes back 401, and retries it once.
 *
 * The renewal itself lives in [TokenRefresher], which the realtime socket shares
 * — see the note there about why a second concurrent refresh destroys the
 * session.
 *
 * OkHttp only consults an Authenticator on a 401 and stops when this returns
 * null, which is what bounds the retry to one.
 *
 * A renewal that failed without the token being refused (no network, 429,
 * 5xx) keeps the session and fails the request with that error instead of the
 * original 401: an [IOException] as itself, and a server answer wrapped in
 * [RefreshUnavailableException], because an Authenticator may only throw
 * IOException. [StreamHubApi] unwraps the latter back into the
 * [StreamHubException] it carries.
 */
internal class TokenAuthenticator(
    private val refresher: TokenRefresher,
    private val apiRoot: HttpUrl,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // A CDN's 401 says nothing about this session, and answering it would
        // hand the CDN a fresh token.
        if (!response.request.url.sameOriginAs(apiRoot)) return null

        // Give up rather than loop: one renewal per original request.
        if (response.priorResponse != null) return null

        val attempted = response.request.header("Authorization")
            ?.removePrefix("Bearer ")
            ?.trim()

        val session = try {
            refresher.refresh(attempted)
        } catch (error: StreamHubException) {
            throw RefreshUnavailableException(error)
        } ?: return null
        return response.request.newBuilder()
            .header("Authorization", "Bearer ${session.accessToken}")
            .build()
    }
}

/**
 * The access token lapsed and could not be renewed *right now*, though the
 * session itself is intact. Carries the server's answer to the refresh.
 */
class RefreshUnavailableException(val error: StreamHubException) :
    IOException("Could not renew the session (${error.status}): ${error.message}", error)
