package com.streamhub.core.net

import com.streamhub.core.model.Session
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Renews the access token when a request comes back 401, and retries it once.
 *
 * The refresh **must** be single-flight. The server rotates the refresh token on
 * every use, so two concurrent refreshes mean the second presents a token the
 * first already invalidated and the whole session dies. Requests therefore
 * serialise on [lock], and a caller that finds the stored token already changed
 * while it waited reuses that one instead of refreshing again.
 *
 * OkHttp only consults an Authenticator on a 401, and stops if this returns
 * null, which is what bounds the retry.
 */
internal class TokenAuthenticator(
    private val store: SessionStore,
    private val refresh: (refreshToken: String) -> Session?,
) : Authenticator {

    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        // Give up rather than loop: one renewal per original request.
        if (response.priorResponse != null) return null

        val attempted = response.request.header("Authorization")
            ?.removePrefix("Bearer ")
            ?.trim()

        synchronized(lock) {
            val current = store.load() ?: return null

            // Another thread refreshed while this one waited for the lock. Its
            // token is good; refreshing again would rotate that one away.
            if (!attempted.isNullOrEmpty() && current.accessToken != attempted) {
                return response.request.withBearer(current.accessToken)
            }

            val renewed = runCatching { refresh(current.refreshToken) }.getOrNull()
            if (renewed == null) {
                // The refresh token is spent or rejected; nothing to retry with.
                store.clear()
                return null
            }

            store.save(renewed)
            return response.request.withBearer(renewed.accessToken)
        }
    }
}

private fun Request.withBearer(token: String): Request =
    newBuilder().header("Authorization", "Bearer $token").build()
