package com.streamhub.core.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The behaviour the whole feature was asked for: stopping at 80% and starting
 * again must continue, not begin again. The server in these tests counts every
 * request, so "nothing is fetched twice" is asserted rather than assumed.
 */
class SegmentDownloaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val hits = mutableMapOf<String, Int>()

    private val bodies = mapOf(
        "/seg0.ts" to ByteArray(1000) { 0x10 },
        "/seg1.ts" to ByteArray(1500) { 0x11 },
        "/seg2.ts" to ByteArray(2000) { 0x12 },
    )

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: return MockResponse().setResponseCode(404)
                hits[path] = (hits[path] ?: 0) + 1
                val body = bodies[path] ?: extra[path] ?: return MockResponse().setResponseCode(404)
                return MockResponse().setBody(Buffer().write(body))
            }
        }
        server.start()
    }

    private val extra = mutableMapOf<String, ByteArray>()

    @After
    fun stop() {
        server.shutdown()
    }

    private fun record(segments: List<HlsSegment>) = DownloadRecord(
        id = "test",
        title = "Test title",
        providerKey = "777tv",
        itemUrl = "https://example/item",
        sourceLabel = "line",
        createdAtMs = 1,
        segments = segments,
        totalDurationSeconds = segments.sumOf { it.durationSeconds },
    )

    private fun plainSegments() = bodies.keys.sorted().mapIndexed { index, path ->
        HlsSegment(url = server.url(path).toString(), durationSeconds = 4.0, mediaSequence = index.toLong())
    }

    @Test
    fun `downloads everything into one file in order`() = runTest {
        val paths = SegmentDownloader.create(folder.root, record(plainSegments()))
        val result = SegmentDownloader(OkHttpClient(), paths).run()

        assertTrue(result.finished)
        assertEquals(4500L, paths.media.length())
        val expected = bodies.keys.sorted().flatMap { bodies[it]!!.asIterable() }.toByteArray()
        assertArrayEquals(expected, paths.media.readBytes())
    }

    @Test
    fun `cancelling keeps what was fetched and resuming fetches only the rest`() = runTest {
        val paths = SegmentDownloader.create(folder.root, record(plainSegments()))

        // Cancel as soon as the second segment has landed.
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                SegmentDownloader(OkHttpClient(), paths, onProgress = { progressed ->
                    if (progressed.completedSegments == 2) throw CancellationException("stopped by the test")
                }).run()
            } catch (_: CancellationException) {
                // The cancel is the point.
            }
        }
        job.join()

        val afterCancel = DownloadStore.read(paths.record)!!
        assertEquals(2, afterCancel.completedSegments)
        assertEquals(2500L, afterCancel.bytesWritten)
        assertTrue(!afterCancel.finished)

        val result = SegmentDownloader(OkHttpClient(), paths).run()
        assertTrue(result.finished)
        assertEquals(4500L, paths.media.length())

        // The heart of the matter: the first two segments were fetched once.
        assertEquals(1, hits["/seg0.ts"])
        assertEquals(1, hits["/seg1.ts"])
        assertEquals(1, hits["/seg2.ts"])
    }

    @Test
    fun `a torn tail past the recorded offset is truncated away on resume`() = runTest {
        val paths = SegmentDownloader.create(folder.root, record(plainSegments()))

        // As if the process died mid-write: one whole segment recorded, then
        // garbage past the offset that no record accounts for.
        SegmentDownloader(OkHttpClient(), paths, onProgress = {
            if (it.completedSegments == 1) throw CancellationException("stop")
        }).let { downloader ->
            try { downloader.run() } catch (_: CancellationException) {}
        }
        paths.media.appendBytes(ByteArray(700) { 0x77 })
        assertEquals(1700L, paths.media.length())

        val result = SegmentDownloader(OkHttpClient(), paths).run()
        assertTrue(result.finished)
        assertEquals(4500L, paths.media.length())
        val expected = bodies.keys.sorted().flatMap { bodies[it]!!.asIterable() }.toByteArray()
        assertArrayEquals(expected, paths.media.readBytes())
    }

    @Test
    fun `aes-128 segments are decrypted with the sequence-number iv`() = runTest {
        val key = ByteArray(16) { it.toByte() }
        val plain = ByteArray(3200) { (it % 251).toByte() }
        val sequence = 7L
        val iv = ByteArray(16).also { for (i in 0 until 8) it[15 - i] = ((sequence shr (8 * i)) and 0xFF).toByte() }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        extra["/key.bin"] = key
        extra["/enc.ts"] = cipher.doFinal(plain)

        val segments = listOf(
            HlsSegment(
                url = server.url("/enc.ts").toString(),
                durationSeconds = 4.0,
                mediaSequence = sequence,
                keyMethod = "AES-128",
                keyUrl = server.url("/key.bin").toString(),
            )
        )
        val paths = SegmentDownloader.create(folder.root, record(segments))
        val result = SegmentDownloader(OkHttpClient(), paths).run()

        assertTrue(result.finished)
        assertArrayEquals(plain, paths.media.readBytes())
    }

    @Test
    fun `a failing segment leaves a resumable record rather than a corrupt file`() = runTest {
        val segments = plainSegments() + HlsSegment(
            url = server.url("/missing.ts").toString(),
            durationSeconds = 4.0,
            mediaSequence = 3,
        )
        val paths = SegmentDownloader.create(folder.root, record(segments))

        val failure = runCatching { SegmentDownloader(OkHttpClient(), paths).run() }
        assertTrue(failure.isFailure)

        val record = DownloadStore.read(paths.record)!!
        assertEquals(3, record.completedSegments)
        assertEquals(4500L, record.bytesWritten)
        assertEquals(4500L, paths.media.length())
    }
}
