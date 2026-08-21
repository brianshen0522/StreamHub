package com.streamhub.core.net

/**
 * Every server error is `{ "error": string }`. Validation failures add a
 * `details` array, which is deliberately not surfaced here — it names server
 * field paths and is not fit to show anyone.
 */
class StreamHubException(
    val status: Int,
    override val message: String,
) : Exception(message) {

    /** The account is real but barred from this client, or from playback. */
    val isForbidden: Boolean get() = status == 403

    /** Credentials were wrong, or the session is gone and could not be renewed. */
    val isUnauthorized: Boolean get() = status == 401
}
