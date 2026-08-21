package com.streamhub.core.net

import com.streamhub.core.ApiConfig
import com.streamhub.core.ClientKind
import com.streamhub.core.model.ItemDetail
import com.streamhub.core.model.ProgressUpdate
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class StreamHubApiTest {

    private lateinit var server: MockWebServer
    private lateinit var store: SessionStore
    private lateinit var api: StreamHubApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = InMemorySessionStore()
        api = StreamHubApi(server.url("/").toString(), store, ClientKind.PHONE)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private fun sessionJson(role: String = "USER", access: String = "access-1") = """
        {
          "user": {"id":"u1","username":"viewer","email":"v@example.com","role":"$role","status":"ACTIVE"},
          "accessToken": "$access",
          "refreshToken": "refresh-1"
        }
    """.trimIndent()

    // ── auth ────────────────────────────────────────────────────────────────

    @Test
    fun `login stores the session and identifies the client`() = runBlocking {
        server.enqueue(ok(sessionJson()))

        val session = api.login("viewer", "pw")

        assertEquals("access-1", session.accessToken)
        assertEquals("access-1", store.load()?.accessToken)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.startsWith("${ApiConfig.BASE_PATH}/auth/login"))
        // Without this header the server hands admins a token that fails everywhere.
        assertEquals("android", request.getHeader(ApiConfig.CLIENT_HEADER))
        assertTrue(request.body.readUtf8().contains("\"login\":\"viewer\""))
    }

    @Test
    fun `login refuses an admin even if the server did not`() = runBlocking {
        server.enqueue(ok(sessionJson(role = "ADMIN")))

        try {
            api.login("admin", "pw")
            fail("expected an admin account to be refused")
        } catch (error: StreamHubException) {
            assertEquals(403, error.status)
        }
        assertNull("a refused session must not be stored", store.load())
    }

    @Test
    fun `a server refusal surfaces its own message`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"error":"Administrator accounts cannot sign in to a playback client. Use a viewer account."}""")
        )

        try {
            api.login("admin", "pw")
            fail("expected a refusal")
        } catch (error: StreamHubException) {
            assertEquals(403, error.status)
            assertTrue(error.isForbidden)
            assertTrue(error.message.startsWith("Administrator accounts cannot"))
        }
    }

    @Test
    fun `requests carry the bearer token`() = runBlocking {
        store.save(json(sessionJson()))
        server.enqueue(ok("""{"favorites":[]}"""))

        api.favorites()

        assertEquals("Bearer access-1", server.takeRequest().getHeader("Authorization"))
    }

    // ── catalogue ───────────────────────────────────────────────────────────

    @Test
    fun `a failed provider is reported in the body, not the status`() = runBlocking {
        store.save(json(sessionJson()))
        server.enqueue(
            ok(
                """
                {"query":"foo","results":[
                  {"provider":"movieffm","items":[{"provider":"movieffm","title":"A","url":"u","mediaType":"tv"}]},
                  {"provider":"777tv","items":[],"error":"timed out"}
                ]}
                """.trimIndent()
            )
        )

        val response = api.search("foo")

        assertEquals(2, response.results.size)
        assertNull(response.results[0].error)
        assertEquals("timed out", response.results[1].error)
    }

    @Test
    fun `item detail discriminates on which key is present`() = runBlocking {
        store.save(json(sessionJson()))

        server.enqueue(ok("""{"provider":"movieffm","title":"Hub","seasons":[{"label":"S1","url":"s1"}]}"""))
        val hub = api.item("movieffm", "u")
        assertTrue(hub is ItemDetail.Seasons)
        assertEquals("S1", (hub as ItemDetail.Seasons).seasons.single().label)

        server.enqueue(ok("""{"provider":"777tv","title":"Show","detailUrl":"d","episodes":["EP1","第2集"]}"""))
        val series = api.item("777tv", "u")
        assertTrue(series is ItemDetail.Episodes)
        series as ItemDetail.Episodes
        assertEquals(listOf("EP1", "第2集"), series.episodes)
        assertEquals("d", series.sourceUrl)

        server.enqueue(ok("""{"provider":"movieffm","title":"Film","streams":[{"sourceLabel":"L1","url":"m3u8"}]}"""))
        val movie = api.item("movieffm", "u")
        assertTrue(movie is ItemDetail.Movie)
        assertEquals("L1", (movie as ItemDetail.Movie).streams.single().sourceLabel)
    }

    @Test
    fun `sources arrive as NDJSON lines`() = runBlocking {
        store.save(json(sessionJson()))
        server.enqueue(
            ok(
                """
                {"sourceLabel":"A","url":"http://cdn/a.m3u8","directUrl":"http://cdn/a.m3u8","durationSeconds":2820,"adSeconds":88}

                {"sourceLabel":"B","url":"http://cdn/b.m3u8","directUrl":"http://cdn/b.m3u8","proxyUrl":"/api/stream?target=x"}
                """.trimIndent()
            )
        )

        val sources = api.sources("movieffm", "season", "EP1").toList()

        assertEquals(2, sources.size)
        assertEquals("A", sources[0].sourceLabel)
        // Already has ad segments subtracted, so it matches the player timeline.
        assertEquals(2820, sources[0].durationSeconds)
        assertEquals(88, sources[0].adSeconds)
        assertNull("a blank line must not produce an entry", sources[1].durationSeconds)
        assertEquals("B", sources[1].sourceLabel)
    }

    @Test
    fun `manifest and poster urls are versioned and encoded`() {
        val target = "http://cdn.example.com/a b/index.m3u8?x=1&y=2"

        val manifest = api.manifestUrl(target)
        assertTrue(manifest.contains("${ApiConfig.BASE_PATH}/manifest"))
        assertTrue("the target must survive as one parameter", manifest.contains("target="))
        assertTrue(manifest.contains("%3Fx%3D1%26y%3D2") || manifest.contains("x%3D1"))

        assertTrue(api.posterUrl(target).contains("${ApiConfig.BASE_PATH}/poster"))
    }

    // ── library ─────────────────────────────────────────────────────────────

    @Test
    fun `progress omits the fields the server derives`() = runBlocking {
        store.save(json(sessionJson()))
        server.enqueue(ok("""{"progress":{}}"""))

        api.putProgress(
            ProgressUpdate(
                providerKey = "movieffm",
                title = "Show",
                itemUrl = "http://x/1",
                durationSeconds = 2820,
                positionSeconds = 610,
                event = "pause",
            )
        )

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"positionSeconds\":610"))
        assertTrue(body.contains("\"event\":\"pause\""))
        assertTrue("progressPercent is derived server-side", !body.contains("progressPercent"))
        assertTrue("isCompleted is derived server-side", !body.contains("isCompleted"))
    }

    @Test
    fun `deleting progress sends a body`() = runBlocking {
        store.save(json(sessionJson()))
        server.enqueue(ok("""{"ok":true}"""))

        api.deleteProgress(
            com.streamhub.core.model.ProgressDelete(
                providerKey = "dramasq",
                scope = "title",
                title = "Show",
            )
        )

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        // Dismissing a card deletes by title and ignores itemUrl on purpose.
        assertTrue(request.body.readUtf8().contains("\"scope\":\"title\""))
    }

    @Test
    fun `an error body becomes a typed exception`() = runBlocking {
        store.save(json(sessionJson()))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"Validation failed.","details":[]}"""))

        try {
            api.episodes("movieffm", "")
            fail("expected a failure")
        } catch (error: StreamHubException) {
            assertEquals(400, error.status)
            assertEquals("Validation failed.", error.message)
        }
    }

    @Test
    fun `a non-JSON failure still produces a usable message`() = runBlocking {
        store.save(json(sessionJson()))
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"))

        try {
            api.favorites()
            fail("expected a failure")
        } catch (error: StreamHubException) {
            assertEquals(502, error.status)
            assertNotNull(error.message)
            assertTrue(error.message.contains("502"))
        }
    }

    private fun json(text: String) =
        kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<com.streamhub.core.model.Session>(text)
}
