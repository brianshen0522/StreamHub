package com.streamhub.core.net

import com.streamhub.core.ApiConfig
import com.streamhub.core.ClientKind
import com.streamhub.core.model.ItemDetail
import com.streamhub.core.model.ProgressUpdate
import com.streamhub.core.model.Session
import com.streamhub.core.model.User
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Request
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

    private fun sessionFor(access: String) = Session(
        user = User(id = "u1", username = "viewer", email = "v@example.com", role = "USER", status = "ACTIVE"),
        accessToken = access,
        refreshToken = "refresh-1",
    )

    private fun sessionJson(role: String = "USER", access: String = "access-1") = """
        {
          "user": {"id":"u1","username":"viewer","email":"v@example.com","role":"$role","status":"ACTIVE"},
          "accessToken": "$access",
          "refreshToken": "refresh-1"
        }
    """.trimIndent()

    // ── credentials stay on this server ─────────────────────────────────────

    @Test
    fun `the authenticated client keeps the token off other hosts`() {
        store.save(sessionFor("secret-access"))
        val cdn = MockWebServer().apply { start() }
        try {
            cdn.enqueue(ok("segment"))
            server.enqueue(ok("manifest"))

            val client = api.authenticatedClient
            client.newCall(Request.Builder().url(cdn.url("/seg1.ts")).build()).execute().close()
            client.newCall(Request.Builder().url(server.url("/api/v1/manifest")).build()).execute().close()

            val atCdn = cdn.takeRequest()
            assertNull(atCdn.getHeader("Authorization"))
            assertNull(atCdn.getHeader(ApiConfig.CLIENT_HEADER))

            val atServer = server.takeRequest()
            assertEquals("Bearer secret-access", atServer.getHeader("Authorization"))
            assertEquals(ClientKind.PHONE.header, atServer.getHeader(ApiConfig.CLIENT_HEADER))
        } finally {
            cdn.shutdown()
        }
    }

    @Test
    fun `a 401 from another host does not renew the session`() {
        store.save(sessionFor("secret-access"))
        val cdn = MockWebServer().apply { start() }
        try {
            cdn.enqueue(MockResponse().setResponseCode(401))

            api.authenticatedClient
                .newCall(Request.Builder().url(cdn.url("/seg1.ts")).build())
                .execute()
                .use { assertEquals(401, it.code) }

            assertEquals(1, cdn.requestCount)
            assertEquals(0, server.requestCount)
            assertEquals("secret-access", store.load()?.accessToken)
        } finally {
            cdn.shutdown()
        }
    }

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
        assertNull("a single-page series has no season, so its resume key is empty", series.seasonUrl)
        assertEquals("d", series.detailUrl)

        server.enqueue(ok("""{"provider":"movieffm","title":"Film","streams":[{"sourceLabel":"L1","url":"m3u8"}]}"""))
        val movie = api.item("movieffm", "u")
        assertTrue(movie is ItemDetail.Movie)
        assertEquals("L1", (movie as ItemDetail.Movie).streams.single().sourceLabel)
    }

    // ── /api/item, read the way Tizen reads it (detail.rs `load`) ───────────

    private suspend fun itemFor(body: String): ItemDetail {
        server.enqueue(ok(body))
        return api.item("p", "u")
    }

    @Test
    fun `an item with neither episodes nor streams is a movie`() = runBlocking {
        store.save(json(sessionJson()))

        val item = itemFor("""{"provider":"movieffm","title":"Film","mediaType":"movie"}""")

        assertTrue("not an empty series", item is ItemDetail.Movie)
        assertEquals(emptyList<Any>(), (item as ItemDetail.Movie).streams)
    }

    @Test
    fun `an empty seasons list is not a hub`() = runBlocking {
        store.save(json(sessionJson()))

        // movieffm answers a hub page whose season links it could not read with
        // `seasons: []`. With nothing to pick, Tizen reads on past the key.
        val bare = itemFor("""{"provider":"movieffm","title":"Hub","mediaType":"tv","seasons":[]}""")
        assertTrue(bare is ItemDetail.Movie)

        val withEpisodes = itemFor("""{"provider":"movieffm","title":"Hub","seasons":[],"episodes":["EP1"],"seasonUrl":"s"}""")
        assertTrue(withEpisodes is ItemDetail.Episodes)
        assertEquals("s", (withEpisodes as ItemDetail.Episodes).seasonUrl)
    }

    @Test
    fun `seasons without a url are dropped, and a hub of only those is not a hub`() = runBlocking {
        store.save(json(sessionJson()))

        val hub = itemFor(
            """{"provider":"movieffm","title":"Hub","seasons":[
                {"label":"S1","url":""},{"label":"S2","url":"  "},{"label":"S3","url":"s3"}]}"""
        )
        assertEquals(listOf("S3"), (hub as ItemDetail.Seasons).seasons.map { it.label })

        val blank = itemFor("""{"provider":"movieffm","title":"Hub","seasons":[{"label":"S1","url":""}],"episodes":[]}""")
        assertTrue("falls through to the episodes key", blank is ItemDetail.Episodes)
    }

    @Test
    fun `an episodes key makes a series even when it is empty`() = runBlocking {
        store.save(json(sessionJson()))

        val item = itemFor("""{"provider":"dramasq","title":"Gone","detailUrl":"d","episodes":[]}""")

        assertTrue(item is ItemDetail.Episodes)
        assertEquals(emptyList<String>(), (item as ItemDetail.Episodes).episodes)
    }

    @Test
    fun `episodes win over streams, as in Tizen`() = runBlocking {
        store.save(json(sessionJson()))

        val item = itemFor(
            """{"provider":"p","title":"T","episodes":["EP1"],"streams":[{"sourceLabel":"L","url":"m"}]}"""
        )

        assertTrue(item is ItemDetail.Episodes)
    }

    @Test
    fun `item detail carries description, media type and detail url`() = runBlocking {
        store.save(json(sessionJson()))

        val described = itemFor(
            """{"provider":"777tv","title":"Show","mediaType":"tv","description":"A plot.",
               "summary":"ignored","detailUrl":"d","episodes":["1"]}"""
        )
        assertEquals("A plot.", described.description)
        assertEquals("tv", described.mediaType)
        assertEquals("d", described.detailUrl)

        val summarised = itemFor("""{"provider":"p","title":"Film","summary":"Short.","streams":[]}""")
        assertEquals("summary is the fallback", "Short.", summarised.description)
        assertNull(summarised.mediaType)
        assertNull(summarised.detailUrl)

        val hub = itemFor("""{"provider":"movieffm","title":"Hub","mediaType":"tv","seasons":[{"label":"S1","url":"s1"}]}""")
        assertEquals("tv", hub.mediaType)
        assertNull(hub.description)
    }

    @Test
    fun `a movieffm season keys progress on its seasonUrl`() = runBlocking {
        store.save(json(sessionJson()))

        val item = itemFor("""{"provider":"movieffm","title":"S","seasonUrl":"https://m/s2","episodes":["EP1"]}""")

        item as ItemDetail.Episodes
        assertEquals("https://m/s2", item.seasonUrl)
        assertEquals("https://m/s2", item.sourceUrl)
    }

    // ── numbers as JavaScript writes them ───────────────────────────────────

    @Test
    fun `fractional ad and duration seconds decode`() = runBlocking {
        store.save(json(sessionJson()))
        server.enqueue(
            ok(
                """
                {"sourceLabel":"線路A","url":"u","directUrl":"d","adSeconds":31.6,"durationSeconds":6727.4}
                {"sourceLabel":"線路B","url":"u","directUrl":"d","adSeconds":52,"durationSeconds":null}
                """.trimIndent()
            )
        )

        val sources = api.sources("p", "s", "EP1").toList()

        assertEquals(2, sources.size)
        assertEquals(31.6, sources[0].adSeconds, 1e-9)
        assertEquals(6727.4, sources[0].durationSeconds!!, 1e-9)
        assertEquals(52.0, sources[1].adSeconds, 0.0)
        assertNull(sources[1].durationSeconds)
    }

    @Test
    fun `one unreadable line does not end the source stream`() = runBlocking {
        store.save(json(sessionJson()))
        server.enqueue(
            ok(
                """
                {"sourceLabel":"A","url":"u","directUrl":"d","adSeconds":1.5}
                {"sourceLabel":"broken","adSeconds":"lots"
                {"url":"no label or directUrl"}
                {"sourceLabel":"C","url":"u","directUrl":"d"}
                """.trimIndent()
            )
        )

        val labels = api.sources("p", "s", "EP1").toList().map { it.sourceLabel }

        assertEquals(listOf("A", "C"), labels)
    }

    @Test
    fun `checked movie sources accept fractional numbers too`() = runBlocking {
        store.save(json(sessionJson()))
        server.enqueue(ok("""{"sourceLabel":"L1","url":"u","directUrl":"d","adSeconds":0.4,"durationSeconds":5400.5}"""))

        val source = api.checkSources("p", emptyList()).toList().single()

        assertEquals(0.4, source.adSeconds, 1e-9)
        assertEquals(5400.5, source.durationSeconds!!, 1e-9)
    }

    @Test
    fun `library rows accept fractional seconds and round them`() = runBlocking {
        store.save(json(sessionJson()))

        server.enqueue(
            ok(
                """{"items":[{"providerKey":"p","title":"T","itemUrl":"i","durationSeconds":2820.6,
                   "positionSeconds":61.2,"episodesTouched":2.0,"episodesCompleted":1}]}"""
            )
        )
        val card = api.continueWatching().single()
        assertEquals(2821, card.durationSeconds)
        assertEquals(61, card.positionSeconds)
        assertEquals(2, card.episodesTouched)

        server.enqueue(
            ok("""{"progress":[{"providerKey":"p","title":"T","itemUrl":"i","durationSeconds":99.5,"positionSeconds":null}]}""")
        )
        val row = api.progress().single()
        assertEquals(100, row.durationSeconds)
        assertEquals(0, row.positionSeconds)

        server.enqueue(
            ok("""{"history":[{"id":"h","providerKey":"p","title":"T","itemUrl":"i","positionSeconds":12.4,"durationSeconds":30.5}]}""")
        )
        val entry = api.history().single()
        assertEquals(12, entry.positionSeconds)
        assertEquals(31, entry.durationSeconds)
    }

    // ── fields screens need, optional so older payloads still decode ─────────

    @Test
    fun `library rows carry the unavailable mark only when the server set it`() = runBlocking {
        store.save(json(sessionJson()))

        server.enqueue(
            ok(
                """{"favorites":[
                  {"id":"f1","providerKey":"p","title":"Gone","itemUrl":"i","isUnavailable":true,"unavailableReason":"no-episodes"},
                  {"id":"f2","providerKey":"p","title":"Fine","itemUrl":"j"}
                ]}"""
            )
        )
        val favorites = api.favorites()
        assertTrue(favorites[0].isUnavailable)
        assertEquals(com.streamhub.core.model.UnavailableReason.NO_EPISODES, favorites[0].unavailableReason)
        assertEquals("absent reads as no record", false, favorites[1].isUnavailable)
        assertNull(favorites[1].unavailableReason)

        server.enqueue(
            ok(
                """{"history":[{"id":"h","providerKey":"p","title":"T","itemUrl":"i","detailUrl":"d",
                   "isUnavailable":true,"unavailableReason":"not-found"}]}"""
            )
        )
        val entry = api.history().single()
        assertTrue(entry.isUnavailable)
        assertEquals("not-found", entry.unavailableReason)
        assertEquals("d", entry.detailUrl)

        server.enqueue(
            ok("""{"items":[{"id":"c1","providerKey":"p","title":"T","itemUrl":"i","isUnavailable":true,"unavailableReason":"not-found"}]}""")
        )
        val card = api.continueWatching().single()
        assertTrue(card.isUnavailable)
        assertEquals("c1", card.id)

        server.enqueue(ok("""{"items":[{"providerKey":"p","title":"T","itemUrl":"i"}]}"""))
        val old = api.continueWatching().single()
        assertNull("older servers send no id", old.id)
        assertEquals(false, old.isUnavailable)
    }

    @Test
    fun `a search result carries its year, as text or as a number`() = runBlocking {
        store.save(json(sessionJson()))
        server.enqueue(
            ok(
                """{"query":"q","results":[{"provider":"p","items":[
                  {"provider":"p","title":"A","url":"a","year":"2019"},
                  {"provider":"p","title":"B","url":"b","year":2021},
                  {"provider":"p","title":"C","url":"c"},
                  {"provider":"p","title":"D","url":"d","year":null},
                  {"provider":"p","title":"E","url":"e","year":""}
                ]}]}"""
            )
        )

        val years = api.search("q").results.single().items.map { it.year }

        assertEquals(listOf("2019", "2021", null, null, null), years)
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
        assertEquals(2820.0, sources[0].durationSeconds!!, 0.0)
        assertEquals(88.0, sources[0].adSeconds, 0.0)
        assertNull("a blank line must not produce an entry", sources[1].durationSeconds)
        assertEquals("B", sources[1].sourceLabel)
    }

    @Test
    fun `a movie posts its streams back to be checked`() = runBlocking {
        store.save(json(sessionJson()))
        server.enqueue(
            ok("""{"sourceLabel":"L1","url":"http://cdn/m.m3u8","directUrl":"http://cdn/m.m3u8"}""")
        )

        val sources = api.checkSources(
            provider = "movieffm",
            streams = listOf(
                com.streamhub.core.model.RawStream(sourceLabel = "L1", episodeLabel = "Movie", url = "http://cdn/m.m3u8")
            ),
            preferredLabel = "L1",
        ).toList()

        assertEquals(1, sources.size)
        assertEquals("L1", sources.single().sourceLabel)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"provider\":\"movieffm\""))
        assertTrue(body.contains("\"streams\":["))
        assertTrue(body.contains("\"preferredLabel\":\"L1\""))
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

    @Test
    fun `stream relay url is versioned and carries the target`() {
        val target = "http://cdn.example.com/a b/index.m3u8?x=1&y=2"

        val relay = api.streamUrl(target)
        assertTrue(relay.contains("${ApiConfig.BASE_PATH}/stream"))
        assertTrue("the target must survive as one parameter", relay.contains("target="))
        assertTrue(relay.contains("%3Fx%3D1%26y%3D2") || relay.contains("x%3D1"))
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
    fun `creating a favorite does not invent an id`() = runBlocking {
        store.save(json(sessionJson()))
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"favorite":{"id":"srv-1","providerKey":"dramasq","title":"Show","itemUrl":"http://x/1"}}""")
        )

        val created = api.addFavorite(
            com.streamhub.core.model.NewFavorite(
                providerKey = "dramasq",
                title = "Show",
                itemUrl = "http://x/1",
                episodeLabel = "EP3",
            )
        )

        assertEquals("srv-1", created.id)
        val body = server.takeRequest().body.readUtf8()
        assertTrue("the server assigns the id", !body.contains("\"id\""))
        assertTrue(body.contains("\"episodeLabel\":\"EP3\""))
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
