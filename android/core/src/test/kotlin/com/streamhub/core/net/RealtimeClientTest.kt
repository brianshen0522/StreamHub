package com.streamhub.core.net

import com.streamhub.core.model.Session
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class RealtimeClientTest {

    private lateinit var server: MockWebServer
    private lateinit var store: SessionStore

    /** Every auth frame the server received, in order. */
    private val authFrames: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val renewals = AtomicInteger(0)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = InMemorySessionStore(session("access-1"))
        authFrames.clear()
        renewals.set(0)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun session(access: String) = Session(
        user = com.streamhub.core.model.User(
            id = "u1", username = "viewer", email = "v@example.com", role = "USER", status = "ACTIVE",
        ),
        accessToken = access,
        refreshToken = "refresh-1",
    )

    private fun client(
        renew: suspend (String?) -> Session? = { null },
    ) = RealtimeClient(
        baseUrl = server.url("/").toString(),
        store = store,
        renew = renew,
        client = OkHttpClient(),
        reconnectBaseMs = 10,
        reconnectMaxMs = 20,
    )

    /** Records the client's auth frame, then runs [afterAuth] with the socket. */
    private fun serverSocket(afterAuth: (WebSocket) -> Unit) =
        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                authFrames.add(text)
                afterAuth(webSocket)
            }
        })

    @Test
    fun `authenticates in the first frame`() = runBlocking {
        server.enqueue(
            serverSocket { socket ->
                socket.send("""{"type":"ready","expiresAt":123}""")
                socket.send("""{"type":"favorites","action":"added","id":"f1"}""")
            }
        )

        val event = withTimeout(5_000) { client().events().first() }

        assertEquals(RealtimeEvent.Favorites("added", "f1"), event)
        // The token goes in a frame, never the URL, so it stays out of proxy logs.
        assertEquals("""{"type":"auth","token":"access-1"}""", authFrames.single())
    }

    @Test
    fun `the ready frame is not delivered as an event`() = runBlocking {
        server.enqueue(
            serverSocket { socket ->
                socket.send("""{"type":"ready","expiresAt":123}""")
                socket.send("""{"type":"progress","action":"updated","history":true}""")
                socket.send("""{"type":"progress","action":"removed"}""")
            }
        )

        val events = withTimeout(5_000) { client().events().take(2).toList() }

        // Had "ready" been forwarded it would sit first, as Unknown("ready").
        assertEquals(
            listOf(
                RealtimeEvent.Progress("updated", historyChanged = true),
                RealtimeEvent.Progress("removed", historyChanged = false),
            ),
            events,
        )
    }

    @Test
    fun `progress events carry the history flag`() = runBlocking {
        server.enqueue(
            serverSocket { socket ->
                socket.send("""{"type":"ready"}""")
                socket.send("""{"type":"progress","action":"updated","history":true}""")
            }
        )

        val event = withTimeout(5_000) { client().events().first() }

        assertEquals(RealtimeEvent.Progress("updated", historyChanged = true), event)
    }

    @Test
    fun `an unknown event type does not break the stream`() = runBlocking {
        server.enqueue(
            serverSocket { socket ->
                socket.send("""{"type":"ready"}""")
                socket.send("""{"type":"somethingNew","action":"x"}""")
            }
        )

        val event = withTimeout(5_000) { client().events().first() }

        assertEquals(RealtimeEvent.Unknown("somethingNew"), event)
    }

    @Test
    fun `an expired token is renewed before reconnecting`() = runBlocking {
        // First connection: authenticate, then be dropped the way the server
        // drops a socket whose access token has lapsed.
        server.enqueue(
            serverSocket { socket ->
                socket.send("""{"type":"ready"}""")
                socket.close(4002, "Access token expired.")
            }
        )
        // Second connection: should present the renewed token.
        server.enqueue(
            serverSocket { socket ->
                socket.send("""{"type":"ready"}""")
                socket.send("""{"type":"favorites","action":"removed","id":"f9"}""")
            }
        )

        val realtime = client { stale ->
            renewals.incrementAndGet()
            assertEquals("the stale token is passed so a shared renewal can be reused", "access-1", stale)
            session("access-2").also { store.save(it) }
        }

        val event = withTimeout(10_000) { realtime.events().first() }

        assertEquals(RealtimeEvent.Favorites("removed", "f9"), event)
        assertEquals("renewed exactly once", 1, renewals.get())
        assertEquals(2, authFrames.size)
        assertEquals("""{"type":"auth","token":"access-1"}""", authFrames[0])
        assertEquals(
            "reconnecting with the same dead token would loop forever",
            """{"type":"auth","token":"access-2"}""",
            authFrames[1],
        )
    }

    @Test
    fun `a session that cannot be renewed ends the stream`() = runBlocking {
        server.enqueue(
            serverSocket { socket ->
                socket.send("""{"type":"ready"}""")
                socket.close(4002, "Access token expired.")
            }
        )

        val realtime = client { renewals.incrementAndGet(); null }

        val events = withTimeout(10_000) { realtime.events().toList() }

        assertTrue("no events, and the flow completes rather than spinning", events.isEmpty())
        assertEquals(1, renewals.get())
    }

    @Test
    fun `an unauthorized close is not retried`() = runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    authFrames.add(text)
                    webSocket.close(4003, "Unauthorized.")
                }
            })
        )

        val events = withTimeout(10_000) { client().events().toList() }

        assertTrue(events.isEmpty())
        assertEquals("retrying a rejected token is pointless", 1, authFrames.size)
        assertEquals(0, renewals.get())
    }

    @Test
    fun `no stored session means no connection attempt`() = runBlocking {
        store.clear()

        val events = withTimeout(5_000) { client().events().toList() }

        assertTrue(events.isEmpty())
        assertTrue("nothing should have been sent", authFrames.isEmpty())
    }

    @Test
    fun `a dropped connection reconnects on its own`() = runBlocking {
        server.enqueue(
            serverSocket { socket ->
                socket.send("""{"type":"ready"}""")
                // A plain drop, not an auth problem.
                socket.close(1001, "going away")
            }
        )
        server.enqueue(
            serverSocket { socket ->
                socket.send("""{"type":"ready"}""")
                socket.send("""{"type":"favorites","action":"added","id":"f2"}""")
            }
        )

        val event = withTimeout(10_000) { client().events().first() }

        assertEquals(RealtimeEvent.Favorites("added", "f2"), event)
        assertEquals("reconnected without help", 2, authFrames.size)
        assertEquals("and without renewing, because the token was fine", 0, renewals.get())
    }
}
