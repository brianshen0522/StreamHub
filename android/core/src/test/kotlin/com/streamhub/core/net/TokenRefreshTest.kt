package com.streamhub.core.net

import com.streamhub.core.ApiConfig
import com.streamhub.core.ClientKind
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The refresh token rotates on every use, so a second concurrent refresh
 * presents a token the first already invalidated and takes the whole session
 * with it. These tests are the reason the authenticator serialises.
 */
class TokenRefreshTest {

    private lateinit var server: MockWebServer
    private lateinit var store: SessionStore
    private lateinit var api: StreamHubApi

    private val refreshCount = AtomicInteger(0)

    private fun session(access: String, refresh: String) = """
        {
          "user": {"id":"u1","username":"viewer","email":"v@example.com","role":"USER","status":"ACTIVE"},
          "accessToken": "$access",
          "refreshToken": "$refresh"
        }
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = InMemorySessionStore()
        api = StreamHubApi(server.url("/").toString(), store, ClientKind.PHONE)
        refreshCount.set(0)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * Rejects the stale token, accepts the renewed one, and counts refreshes.
     */
    private fun rotatingDispatcher(refreshSucceeds: Boolean = true) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()

            if (path.startsWith("${ApiConfig.BASE_PATH}/auth/refresh")) {
                if (!refreshSucceeds) return MockResponse().setResponseCode(401).setBody("""{"error":"Invalid refresh token."}""")
                val n = refreshCount.incrementAndGet()
                return MockResponse().setResponseCode(200).setBody(session("access-2", "refresh-$n"))
            }

            return if (request.getHeader("Authorization") == "Bearer access-2") {
                MockResponse().setResponseCode(200).setBody("""{"favorites":[]}""")
            } else {
                MockResponse().setResponseCode(401).setBody("""{"error":"Invalid access token."}""")
            }
        }
    }

    @Test
    fun `a 401 renews the token and retries once`() = runBlocking {
        server.dispatcher = rotatingDispatcher()
        store.save(parse(session("access-1", "refresh-1")))

        api.favorites()

        assertEquals("exactly one renewal", 1, refreshCount.get())
        assertEquals("the renewed token replaces the stored one", "access-2", store.load()?.accessToken)
        assertEquals("the rotated refresh token is kept", "refresh-1", store.load()?.refreshToken)
    }

    @Test
    fun `concurrent 401s cause exactly one refresh`() = runBlocking {
        server.dispatcher = rotatingDispatcher()
        store.save(parse(session("access-1", "refresh-1")))

        // Every one of these starts with the stale token and will be rejected.
        val calls = (1..8).map { async { api.favorites() } }
        calls.awaitAll()

        assertEquals(
            "a second refresh would rotate away the token the first just obtained",
            1,
            refreshCount.get(),
        )
        assertEquals("access-2", store.load()?.accessToken)
    }

    @Test
    fun `a spent refresh token ends the session`() = runBlocking {
        server.dispatcher = rotatingDispatcher(refreshSucceeds = false)
        store.save(parse(session("access-1", "refresh-1")))

        try {
            api.favorites()
            fail("expected the call to fail once the session cannot be renewed")
        } catch (error: StreamHubException) {
            assertEquals(401, error.status)
            assertTrue(error.isUnauthorized)
        }

        assertNull("the dead session must be cleared so the app can send the user to sign in", store.load())
    }

    @Test
    fun `no session means no renewal attempt`() = runBlocking {
        server.dispatcher = rotatingDispatcher()

        try {
            api.favorites()
            fail("expected a failure with no session")
        } catch (error: StreamHubException) {
            assertEquals(401, error.status)
        }
        assertEquals("nothing to refresh with", 0, refreshCount.get())
    }

    private fun parse(text: String) =
        kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<com.streamhub.core.model.Session>(text)
}
