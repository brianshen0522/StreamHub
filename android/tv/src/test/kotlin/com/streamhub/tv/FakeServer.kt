package com.streamhub.tv

import com.streamhub.core.ClientKind
import com.streamhub.core.model.Session
import com.streamhub.core.model.User
import com.streamhub.core.net.InMemorySessionStore
import com.streamhub.core.net.StreamHubApi
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A server in-process: a real [StreamHubApi] over an OkHttp client whose
 * interceptor answers by method and path, so screens run their actual `:core`
 * calls. Unrouted requests answer 404.
 */
class FakeServer {
    /** A canned answer: status and body, or [fail] to throw (no answer at all). */
    data class Reply(val status: Int = 200, val body: String = "{}", val fail: Boolean = false)

    private val routes = HashMap<String, () -> Reply>()

    /** "METHOD /path" of each request, and its body. */
    val requests = CopyOnWriteArrayList<Pair<String, String>>()

    val store = InMemorySessionStore()

    fun on(method: String, path: String, reply: () -> Reply) {
        routes["$method $path"] = reply
    }

    fun json(method: String, path: String, body: String, status: Int = 200) = on(method, path) { Reply(status, body) }

    fun down(method: String, path: String) = on(method, path) { Reply(fail = true) }

    fun count(method: String, path: String) = requests.count { it.first == "$method $path" }

    private val client = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val path = req.url.encodedPath.removePrefix("/api/v1")
            val key = "${req.method} $path"
            val body = req.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
            requests.add(key to body)
            val reply = routes[key]?.invoke() ?: Reply(404, """{"error":"Not found."}""")
            if (reply.fail) throw IOException("Failed to connect")
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(reply.status)
                .message("x")
                .body(reply.body.toResponseBody("application/json".toMediaType()))
                .build()
        })
        .build()

    val api = StreamHubApi("http://tv.test", store, ClientKind.TV, baseClient = client)

    companion object {
        fun session(role: String = "USER", name: String? = "Tizen QA") = Session(
            User(id = "u1", username = "tizen-qa", email = "qa@example.com", displayName = name, role = role, status = "ACTIVE"),
            accessToken = "a",
            refreshToken = "r",
        )

        fun sessionJson(role: String = "USER") =
            """{"user":{"id":"u1","username":"tizen-qa","email":"qa@example.com","displayName":"Tizen QA","role":"$role","status":"ACTIVE"},"accessToken":"a","refreshToken":"r"}"""
    }
}
