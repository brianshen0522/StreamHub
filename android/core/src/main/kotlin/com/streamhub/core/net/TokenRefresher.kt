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
 *
 * **Only a rejection ends the session.** [perform] throws a
 * [StreamHubException] when the server answered with an error and an
 * [java.io.IOException] when it did not answer at all. A 400, 401 or 403 means
 * the refresh token itself was refused, and the session is cleared. Anything
 * else — no network, a 429 (a household shares one address, so somebody's
 * typos on the web app rate-limit this device too), a 5xx during a deploy —
 * says nothing about the token, so it is kept and the error is rethrown to the
 * caller. Signing out on those meant pairing the television again after every
 * Wi-Fi blip. This is the rule the Tizen client follows (`tizen/app/src/api.rs`
 * `do_refresh` / `is_rejection`).
 */
internal class TokenRefresher(
    private val store: SessionStore,
    private val perform: (refreshToken: String) -> Session,
    /**
     * Called once when the session is beyond saving. Without it the app keeps
     * showing signed-in screens that fail on every request, with no way back to
     * the sign-in form.
     */
    private val onSessionEnded: () -> Unit = {},
) {

    private val lock = Any()

    /**
     * @return the renewed (or already-renewed) session, or null when there is
     *   no session or the server refused the refresh token, in which case the
     *   session has been cleared.
     * @throws java.io.IOException when the server could not be reached; the
     *   session is kept.
     * @throws StreamHubException when the server answered with anything other
     *   than a rejection (429, 5xx, an unreadable body); the session is kept.
     */
    fun refresh(staleAccessToken: String?): Session? = synchronized(lock) {
        val current = store.load() ?: return null

        // Someone else renewed while this caller waited for the lock.
        if (!staleAccessToken.isNullOrEmpty() && current.accessToken != staleAccessToken) {
            return current
        }

        val renewed = try {
            perform(current.refreshToken)
        } catch (error: StreamHubException) {
            if (!isRejection(error.status)) throw error
            // The refresh token is spent or refused; the session is over. Unless
            // somebody signed out (or in again) while this was in flight, in
            // which case that newer state is not ours to clear.
            if (stillOurs(current)) {
                store.clear()
                onSessionEnded()
            }
            return null
        }

        // A sign-out during the request must not be undone by writing its answer.
        if (!stillOurs(current)) return store.load()

        store.save(renewed)
        return renewed
    }

    private fun stillOurs(session: Session): Boolean =
        store.load()?.refreshToken == session.refreshToken

    internal companion object {
        /** The server refused the credentials themselves, as opposed to failing to answer. */
        fun isRejection(status: Int): Boolean = status == 400 || status == 401 || status == 403
    }
}
