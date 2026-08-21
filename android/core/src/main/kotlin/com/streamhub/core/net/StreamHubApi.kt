package com.streamhub.core.net

import com.streamhub.core.ApiConfig
import com.streamhub.core.ClientKind
import com.streamhub.core.model.ContinueItem
import com.streamhub.core.model.ContinueResponse
import com.streamhub.core.model.EpisodesResponse
import com.streamhub.core.model.ErrorEnvelope
import com.streamhub.core.model.Favorite
import com.streamhub.core.model.FavoriteResponse
import com.streamhub.core.model.FavoritesResponse
import com.streamhub.core.model.ItemDetail
import com.streamhub.core.model.LoginRequest
import com.streamhub.core.model.NewFavorite
import com.streamhub.core.model.ProgressDelete
import com.streamhub.core.model.ProgressResponse
import com.streamhub.core.model.ProgressUpdate
import com.streamhub.core.model.ProviderInfo
import com.streamhub.core.model.ProvidersResponse
import com.streamhub.core.model.RefreshRequest
import com.streamhub.core.model.SearchResponse
import com.streamhub.core.model.Session
import com.streamhub.core.model.Source
import com.streamhub.core.model.User
import com.streamhub.core.model.WatchProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * The whole server surface this client uses.
 *
 * Requests go to the versioned prefix, so a build already installed on a phone
 * or a TV keeps working against a server whose unversioned paths have moved on.
 * See shared/api/README.md in the repository root for the behaviour behind the
 * awkward parts — the polymorphic item response, the NDJSON source stream, and
 * why refresh has to be single-flight.
 */
class StreamHubApi(
    baseUrl: String,
    private val store: SessionStore,
    clientKind: ClientKind,
    baseClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    private val root: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()

    /** No auth and no renewal: login and refresh use this, or they would recurse. */
    private val bare: OkHttpClient = baseClient.newBuilder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header(ApiConfig.CLIENT_HEADER, clientKind.header)
                    .build()
            )
        }
        .build()

    /**
     * Shared with the realtime socket, so an HTTP 401 and a 4002 socket close
     * cannot rotate the refresh token out from under each other.
     */
    internal val refresher = TokenRefresher(store) { refreshToken -> refreshBlocking(refreshToken) }

    private val authed: OkHttpClient = baseClient.newBuilder()
        .addInterceptor(AuthInterceptor(store, clientKind))
        .authenticator(TokenAuthenticator(refresher))
        .build()

    // ── auth ────────────────────────────────────────────────────────────────

    /**
     * Signs in and stores the session.
     *
     * An admin account is refused by the server because the client header is
     * set; the role is checked here as well so an older server cannot leave the
     * app holding a session that fails on every screen.
     */
    suspend fun login(login: String, password: String): Session = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(path("auth", "login"))
            .post(json.encodeToString(LoginRequest(login, password)).asJson())
            .build()

        val session = bare.newCall(request).execute().use { it.decode<Session>() }
        if (!session.user.canPlay) {
            throw StreamHubException(403, "This account cannot play anything. Sign in with a viewer account.")
        }
        store.save(session)
        session
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        val refreshToken = store.load()?.refreshToken
        store.clear()
        if (refreshToken != null) {
            val request = Request.Builder()
                .url(path("auth", "logout"))
                .post(json.encodeToString(RefreshRequest(refreshToken)).asJson())
                .build()
            runCatching { bare.newCall(request).execute().close() }
        }
        Unit
    }

    suspend fun me(): User = get(path("auth", "me")) { body -> decodeField(body, "user") }

    /** Keeps the account visible in the admin console's online list. Poll every 30-60s. */
    suspend fun heartbeat() = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(path("auth", "heartbeat"))
            .post(ByteArray(0).toRequestBody())
            .build()
        authed.newCall(request).execute().use { it.expectSuccess() }
    }

    /**
     * Renew the session out of band — the realtime socket needs this when the
     * server closes it because the token behind the handshake lapsed.
     *
     * @param staleAccessToken the token that stopped working, so a renewal that
     *   another caller already performed is reused rather than repeated.
     */
    suspend fun renewSession(staleAccessToken: String?): Session? = withContext(Dispatchers.IO) {
        refresher.refresh(staleAccessToken)
    }

    /**
     * Called from the authenticator, on a thread OkHttp already blocked, so this
     * is deliberately not a suspend function.
     */
    private fun refreshBlocking(refreshToken: String): Session? {
        val request = Request.Builder()
            .url(path("auth", "refresh"))
            .post(json.encodeToString(RefreshRequest(refreshToken)).asJson())
            .build()
        return runCatching {
            bare.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else json.decodeFromString<Session>(response.body.string())
            }
        }.getOrNull()
    }

    // ── catalogue ───────────────────────────────────────────────────────────

    suspend fun providers(): List<ProviderInfo> =
        get(path("me", "providers")) { json.decodeFromString<ProvidersResponse>(it).providers }

    /**
     * Answers 200 even when a provider fails, so check [ProviderResults.error]
     * on each entry rather than trusting the status code.
     */
    suspend fun search(query: String, provider: String = "all"): SearchResponse =
        get(path("search") { addQueryParameter("q", query); addQueryParameter("provider", provider) }) {
            json.decodeFromString(it)
        }

    suspend fun item(
        provider: String,
        url: String,
        title: String? = null,
        mediaType: String? = null,
        posterUrl: String? = null,
    ): ItemDetail = get(
        path("item") {
            addQueryParameter("provider", provider)
            addQueryParameter("url", url)
            title?.let { addQueryParameter("title", it) }
            mediaType?.let { addQueryParameter("mediaType", it) }
            posterUrl?.let { addQueryParameter("posterUrl", it) }
        }
    ) { parseItemDetail(it) }

    suspend fun episodes(provider: String, sourceUrl: String): List<String> =
        get(path("episodes") { addQueryParameter("provider", provider); addQueryParameter("sourceUrl", sourceUrl) }) {
            json.decodeFromString<EpisodesResponse>(it).episodes
        }

    /**
     * Sources arrive one JSON object per line as each health probe finishes, so
     * they are emitted as they land rather than collected. Sources that failed
     * their probe are never sent at all.
     *
     * [preferredLabel] biases which is probed first and guarantees nothing about
     * arrival order.
     */
    fun sources(
        provider: String,
        sourceUrl: String,
        episode: String,
        preferredLabel: String? = null,
    ): Flow<Source> = flow {
        val request = Request.Builder()
            .url(
                path("sources") {
                    addQueryParameter("provider", provider)
                    addQueryParameter("sourceUrl", sourceUrl)
                    addQueryParameter("episode", episode)
                    preferredLabel?.let { addQueryParameter("preferredLabel", it) }
                }
            )
            .build()

        authed.newCall(request).execute().use { response ->
            response.expectSuccess()
            val body = response.body.source()
            while (true) {
                val line = body.readUtf8Line() ?: break
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                emit(json.decodeFromString<Source>(trimmed))
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * What to hand the player, rather than the raw source URL: the manifest comes
     * back with ad runs removed and absolute CDN segment URLs, so playback is
     * ad-free and segments never touch the server.
     */
    fun manifestUrl(target: String): String =
        path("manifest") { addQueryParameter("target", target) }.toString()

    fun posterUrl(target: String): String =
        path("poster") { addQueryParameter("target", target) }.toString()

    // ── library ─────────────────────────────────────────────────────────────

    suspend fun favorites(): List<Favorite> =
        get(path("me", "favorites")) { json.decodeFromString<FavoritesResponse>(it).favorites }

    suspend fun addFavorite(favorite: NewFavorite): Favorite = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(path("me", "favorites"))
            .post(json.encodeToString(favorite).asJson())
            .build()
        authed.newCall(request).execute().use { json.decodeFromString<FavoriteResponse>(it.bodyOrThrow()).favorite }
    }

    suspend fun removeFavorite(id: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(path("me", "favorites", id)).delete().build()
        authed.newCall(request).execute().use { it.expectSuccess() }
    }

    suspend fun continueWatching(): List<ContinueItem> =
        get(path("me", "continue-watching")) { json.decodeFromString<ContinueResponse>(it).items }

    suspend fun progress(providerKey: String? = null, itemUrl: String? = null): List<WatchProgress> =
        get(
            path("me", "progress") {
                providerKey?.let { addQueryParameter("providerKey", it) }
                itemUrl?.let { addQueryParameter("itemUrl", it) }
            }
        ) { json.decodeFromString<ProgressResponse>(it).progress }

    suspend fun putProgress(update: ProgressUpdate) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(path("me", "progress"))
            .put(json.encodeToString(update).asJson())
            .build()
        authed.newCall(request).execute().use { it.expectSuccess() }
    }

    /** Note this DELETE carries a body, which the server requires. */
    suspend fun deleteProgress(delete: ProgressDelete) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(path("me", "progress"))
            .delete(json.encodeToString(delete).asJson())
            .build()
        authed.newCall(request).execute().use { it.expectSuccess() }
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    private fun path(vararg segments: String, query: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        root.newBuilder()
            .apply {
                ApiConfig.BASE_PATH.trim('/').split('/').forEach { addPathSegment(it) }
                segments.forEach { addPathSegment(it) }
                query()
            }
            .build()

    private suspend fun <T> get(url: HttpUrl, decode: (String) -> T): T = withContext(Dispatchers.IO) {
        authed.newCall(Request.Builder().url(url).build()).execute().use { decode(it.bodyOrThrow()) }
    }

    private inline fun <reified T> Response.decode(): T = json.decodeFromString(bodyOrThrow())

    private inline fun <reified T> decodeField(body: String, field: String): T {
        val root = json.decodeFromString<JsonObject>(body)
        val value = root[field] ?: throw StreamHubException(502, "Missing \"$field\" in response.")
        return json.decodeFromJsonElement(value)
    }

    private fun Response.bodyOrThrow(): String {
        val text = body.string()
        if (!isSuccessful) throw errorFor(code, text)
        return text
    }

    private fun Response.expectSuccess() {
        if (!isSuccessful) throw errorFor(code, body.string())
    }

    private fun errorFor(status: Int, body: String): StreamHubException {
        val message = runCatching { json.decodeFromString<ErrorEnvelope>(body).error }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "Request failed ($status)."
        return StreamHubException(status, message)
    }

    /**
     * Which key is present decides the shape; there is no type field to switch
     * on. `seasons` means a hub that needs a second call, `streams` means a
     * movie, `episodes` means either a season or a single-page series.
     */
    private fun parseItemDetail(body: String): ItemDetail {
        val obj = json.decodeFromString<JsonObject>(body)
        val provider = obj.text("provider").orEmpty()
        val title = obj.text("title").orEmpty()
        val poster = obj.text("posterUrl")

        obj["seasons"]?.let { seasons ->
            return ItemDetail.Seasons(
                provider = provider,
                title = title,
                posterUrl = poster,
                seasons = seasons.jsonArray.map { json.decodeFromJsonElement(it) },
            )
        }

        obj["streams"]?.let { streams ->
            return ItemDetail.Movie(
                provider = provider,
                title = title,
                posterUrl = poster,
                streams = streams.jsonArray.map { json.decodeFromJsonElement(it) },
            )
        }

        val episodes = obj["episodes"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        return ItemDetail.Episodes(
            provider = provider,
            title = title,
            posterUrl = poster,
            episodes = episodes,
            sourceUrl = obj.text("seasonUrl") ?: obj.text("detailUrl"),
        )
    }

    private fun JsonObject.text(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.takeIf { it.isNotBlank() }

    private fun String.asJson() = toRequestBody(JSON_MEDIA_TYPE)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
