package com.streamhub.core.net

import com.streamhub.core.model.CastCommand
import com.streamhub.core.model.CastPlayRequest
import com.streamhub.core.model.CastPlaybackState
import com.streamhub.core.model.Session
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * The wire format of the cast frames, pinned against what the server validates.
 *
 * Worth its own test because both failure modes here are silent. kotlinx omits
 * a property that equals its default, so a frame built with defaults arrives
 * missing the very field the server switches on — that already cost this app a
 * realtime connection that never opened. And the command discriminator has to
 * be flat `{"action":…}` rather than kotlinx's nested envelope, or every
 * command is dropped by the server's validator without a word.
 */
class CastFrameTest {

    private lateinit var server: MockWebServer
    private lateinit var store: SessionStore

    /** Every frame the client sent, in order. */
    private val sent: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = InMemorySessionStore(
            Session(
                user = com.streamhub.core.model.User(
                    id = "u1", username = "viewer", email = "v@example.com",
                    role = "USER", status = "ACTIVE",
                ),
                accessToken = "access-1",
                refreshToken = "refresh-1",
            )
        )
        sent.clear()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = RealtimeClient(
        baseUrl = server.url("/").toString(),
        store = store,
        renew = { null },
        client = OkHttpClient(),
        reconnectBaseMs = 10,
        reconnectMaxMs = 20,
    )

    /**
     * Connects, waits for the handshake, then lets the test drive the client.
     *
     * The collection is held open for the whole test rather than taken with
     * `first()`: collecting is what owns the socket, so ending it early closes
     * the connection and there is nothing left to send on.
     */
    private fun connected(ready: String, act: (RealtimeClient) -> Unit): List<RealtimeEvent> = runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    sent.add(text)
                    if (sent.size == 1) webSocket.send(ready)
                }
            })
        )
        val realtime = client()
        val events = Collections.synchronizedList(mutableListOf<RealtimeEvent>())
        val collector = launch { realtime.events().collect { events.add(it) } }
        try {
            withTimeout(5_000) { realtime.connected.first { it } }
            act(realtime)
            // The frame is sent from this thread but observed on the server's,
            // so allow it to arrive before asserting on it.
            withTimeout(5_000) { while (sent.size < 2) delay(10) }
        } finally {
            collector.cancel()
        }
        events.toList()
    }

    @Test
    fun `an idle receiver still announces itself, with an explicit null state`() {
        connected("""{"type":"ready","sessionId":"s-tv","receivers":[]}""") { realtime ->
            realtime.publishPlayback(null)
        }

        // The state key must be present. Were it omitted the server would still
        // register the receiver, but the same omission on a real state would
        // silently drop the position.
        assertEquals("""{"type":"playback","state":null}""", sent[1])
    }

    @Test
    fun `a reported state carries position, duration and paused`() {
        connected("""{"type":"ready","sessionId":"s-tv","receivers":[]}""") { realtime ->
            realtime.publishPlayback(
                CastPlaybackState(
                    title = "Test",
                    positionMs = 12_000,
                    durationMs = 60_000,
                    paused = true,
                )
            )
        }

        val frame = json.parseToJsonElement(sent[1]).toString()
        assertTrue(frame, frame.contains(""""positionMs":12000"""))
        assertTrue(frame, frame.contains(""""durationMs":60000"""))
        assertTrue(frame, frame.contains(""""paused":true"""))
        assertTrue(frame, frame.contains(""""title":"Test""""))
    }

    @Test
    fun `a seek command is flat, with action as the discriminator`() {
        connected("""{"type":"ready","sessionId":"s-phone","receivers":[]}""") { realtime ->
            realtime.sendCommand("s-tv", CastCommand.Seek(30_000))
        }

        assertEquals(
            """{"type":"command","to":"s-tv","command":{"action":"seek","positionMs":30000}}""",
            sent[1],
        )
    }

    @Test
    fun `a command with no payload still names its action`() {
        connected("""{"type":"ready","sessionId":"s-phone","receivers":[]}""") { realtime ->
            realtime.sendCommand("s-tv", CastCommand.Pause)
        }

        assertEquals("""{"type":"command","to":"s-tv","command":{"action":"pause"}}""", sent[1])
    }

    @Test
    fun `a play command carries the stream url the receiver needs`() {
        connected("""{"type":"ready","sessionId":"s-phone","receivers":[]}""") { realtime ->
            realtime.sendCommand(
                "s-tv",
                CastCommand.Play(
                    CastPlayRequest(
                        streamUrl = "https://example.test/a.m3u8",
                        title = "Test",
                        positionMs = 5_000,
                    )
                ),
            )
        }

        val frame = sent[1]
        assertTrue(frame, frame.contains(""""action":"play""""))
        assertTrue(frame, frame.contains(""""streamUrl":"https://example.test/a.m3u8""""))
        assertTrue(frame, frame.contains(""""positionMs":5000"""))
    }

    @Test
    fun `the handshake delivers the receivers already connected`() {
        val events = connected(
            """{"type":"ready","sessionId":"s-phone","receivers":[
                {"sessionId":"s-tv","deviceName":"Living room","clientKind":"tv",
                 "state":{"title":"Test","positionMs":1000,"durationMs":2000,"paused":false}}
            ]}"""
        ) { realtime -> realtime.publishPlayback(null) }

        val receivers = (events.first() as RealtimeEvent.Receivers).receivers
        // A phone that connects after the television must not have to wait for
        // the next state tick to discover it.
        assertEquals(1, receivers.size)
        assertEquals("s-tv", receivers[0].sessionId)
        assertEquals("Living room", receivers[0].deviceName)
        assertTrue(receivers[0].isTelevision)
        assertEquals(1000L, receivers[0].state?.positionMs)
    }

    @Test
    fun `a command frame decodes into a typed command`() = runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    webSocket.send("""{"type":"ready","sessionId":"s-tv"}""")
                    webSocket.send(
                        """{"type":"command","from":"s-phone","fromName":"Pixel",
                           "command":{"action":"seek","positionMs":45000}}"""
                    )
                }
            })
        )

        val event = withTimeout(5_000) { client().events().first() }

        assertEquals(
            RealtimeEvent.Command("s-phone", "Pixel", CastCommand.Seek(45_000)),
            event,
        )
    }

    @Test
    fun `an action this build does not know does not break the connection`() = runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    webSocket.send("""{"type":"ready","sessionId":"s-tv"}""")
                    webSocket.send("""{"type":"command","command":{"action":"teleport"}}""")
                    webSocket.send("""{"type":"command","command":{"action":"pause"}}""")
                }
            })
        )

        // The unknown action arrives as Unknown rather than throwing, and the
        // known one right behind it still gets through.
        val events = withTimeout(5_000) { client().events().take(2).toList() }

        assertEquals(RealtimeEvent.Unknown("command"), events[0])
        assertEquals(CastCommand.Pause, (events[1] as RealtimeEvent.Command).command)
    }
}
