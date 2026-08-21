package com.streamhub.core.net

import com.streamhub.core.model.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Live library updates from `/api/realtime`.
 *
 * Collecting [events] opens the socket; cancelling the collection closes it. The
 * flow survives disconnects on its own, so a caller never has to reconnect.
 */
class RealtimeClient(
    baseUrl: String,
    private val store: SessionStore,
    private val renew: suspend (staleAccessToken: String?) -> Session?,
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val reconnectBaseMs: Long = 1_000,
    private val reconnectMaxMs: Long = 30_000,
) {

    private val endpoint: String = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
        .addPathSegment("api")
        .addPathSegment("realtime")
        .build()
        .toString()

    private val _connected = MutableStateFlow(false)

    /**
     * Whether a socket is currently authenticated and carrying events. Useful in
     * a status view: live sync being down is a different problem from the server
     * being unreachable, and they should not look the same.
     */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun events(): Flow<RealtimeEvent> = channelFlow {
        var attempts = 0

        while (true) {
            val outcome = runSocket { trySend(it) }
            _connected.value = false

            when {
                // Nothing to authenticate with, or the server rejected the token
                // outright. Reconnecting cannot help; the app has to sign in.
                outcome.noSession || outcome.closeCode == CLOSE_UNAUTHORIZED -> return@channelFlow

                // The socket is only authenticated at the handshake, so the
                // server drops it the moment the token lapses. Renew *first* —
                // reconnecting with the same dead token just loops.
                outcome.closeCode == CLOSE_TOKEN_EXPIRED -> {
                    attempts = 0
                    if (renew(outcome.token) == null) return@channelFlow
                }

                else -> {
                    // A connection that reached "ready" was healthy, so treat a
                    // later drop as the first failure rather than staying at
                    // whatever backoff got us connected.
                    if (outcome.sawReady) attempts = 0
                    delay(backoffMs(attempts))
                    attempts += 1
                }
            }
        }

        awaitClose { }
    }.buffer(BUFFER)

    private fun backoffMs(attempts: Int): Long {
        val scaled = reconnectBaseMs shl attempts.coerceAtMost(16)
        return scaled.coerceIn(reconnectBaseMs, reconnectMaxMs)
    }

    /** Runs one connection and suspends until it closes. */
    private suspend fun runSocket(onEvent: (RealtimeEvent) -> Unit): Outcome {
        val token = store.load()?.accessToken?.takeIf { it.isNotEmpty() }
            ?: return Outcome(closeCode = null, sawReady = false, token = null, noSession = true)

        val closed = CompletableDeferred<Int?>()
        var sawReady = false

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Auth travels in the first frame rather than the query string,
                // so access tokens stay out of proxy logs. The server allows
                // five seconds for it.
                webSocket.send(json.encodeToString(AuthFrame(type = "auth", token = token)))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = runCatching { json.decodeFromString<JsonObject>(text) }.getOrNull() ?: return
                val type = frame["type"]?.jsonPrimitive?.contentOrNull ?: return
                if (type == READY) {
                    sawReady = true
                    _connected.value = true
                    return
                }
                onEvent(toEvent(type, frame))
            }

            /** The server's code arrives here; onClosed would lose it. */
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                closed.complete(code)
                webSocket.close(NORMAL_CLOSURE, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.complete(code)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed.complete(null)
            }
        }

        val socket = client.newWebSocket(Request.Builder().url(endpoint).build(), listener)
        return try {
            Outcome(closeCode = closed.await(), sawReady = sawReady, token = token, noSession = false)
        } finally {
            socket.cancel()
        }
    }

    private fun toEvent(type: String, frame: JsonObject): RealtimeEvent {
        val action = frame["action"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return when (type) {
            "favorites" -> RealtimeEvent.Favorites(action, frame["id"]?.jsonPrimitive?.contentOrNull)
            "progress" -> RealtimeEvent.Progress(
                action = action,
                historyChanged = frame["history"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
            else -> RealtimeEvent.Unknown(type)
        }
    }

    private class Outcome(
        val closeCode: Int?,
        val sawReady: Boolean,
        val token: String?,
        val noSession: Boolean,
    )

    /**
     * `type` deliberately has no default value. kotlinx.serialization omits
     * properties that equal their default, so a default here would send
     * `{"token":"…"}` with no type, and the server closes such a frame as
     * unauthorized — the socket would never connect at all.
     */
    @Serializable
    private data class AuthFrame(val type: String, val token: String)

    private companion object {
        const val READY = "ready"
        const val NORMAL_CLOSURE = 1000
        const val BUFFER = 64

        /** The server closes with this once the token behind the handshake lapses. */
        const val CLOSE_TOKEN_EXPIRED = 4002
        const val CLOSE_UNAUTHORIZED = 4003
    }
}
