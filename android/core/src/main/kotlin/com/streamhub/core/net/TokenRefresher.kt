package com.streamhub.core.net

import com.streamhub.core.model.Session

/**
 * The one place a token is renewed.
 *
 * The server rotates the refresh token on every use, so a second renewal running
 * concurrently with the first presents a token that has already been
 * invalidated and takes the session down with it. Two callers can trip this:
 * an HTTP request that got a 401, and the realtime socket that was closed with
 * 4002. They therefore share this object rather than each holding their own
 * lock, which would not exclude the other at all.
 *
 * A caller that finds the stored token already different from the one it was
 * using gets the newer one back instead of a second rotation.
 */
internal class TokenRefresher(
    private val store: SessionStore,
    private val perform: (refreshToken: String) -> Session?,
    /**
     * Called once when the session is beyond saving. Without it the app keeps
     * showing signed-in screens that fail on every request, with no way back to
     * the sign-in form.
     */
    private val onSessionEnded: () -> Unit = {},
) {

    private val lock = Any()

    fun refresh(staleAccessToken: String?): Session? = synchronized(lock) {
        val current = store.load() ?: return null

        // Someone else renewed while this caller waited for the lock.
        if (!staleAccessToken.isNullOrEmpty() && current.accessToken != staleAccessToken) {
            return current
        }

        val renewed = runCatching { perform(current.refreshToken) }.getOrNull()
        if (renewed == null) {
            // The refresh token is spent or rejected; the session is over.
            store.clear()
            onSessionEnded()
            return null
        }

        store.save(renewed)
        return renewed
    }
}
