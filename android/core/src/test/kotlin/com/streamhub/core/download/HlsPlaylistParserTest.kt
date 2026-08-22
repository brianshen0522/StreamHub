package com.streamhub.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistParserTest {

    @Test
    fun `parses segments with durations and resolves relative urls`() {
        val playlist = HlsPlaylistParser.parse(
            """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-MEDIA-SEQUENCE:5
            #EXTINF:4.2,
            seg5.ts
            #EXTINF:3.8,
            /abs/seg6.ts
            #EXTINF:4.0,
            https://other.example/seg7.ts
            #EXT-X-ENDLIST
            """.trimIndent(),
            "https://cdn.example/show/ep1/index.m3u8",
        )

        assertEquals(3, playlist.segments.size)
        assertEquals("https://cdn.example/show/ep1/seg5.ts", playlist.segments[0].url)
        assertEquals("https://cdn.example/abs/seg6.ts", playlist.segments[1].url)
        assertEquals("https://other.example/seg7.ts", playlist.segments[2].url)
        assertEquals(5L, playlist.segments[0].mediaSequence)
        assertEquals(7L, playlist.segments[2].mediaSequence)
        assertEquals(12.0, playlist.totalDurationSeconds, 0.001)
        assertNull(playlist.segments[0].keyMethod)
    }

    @Test
    fun `carries the key onto following segments and stops at method none`() {
        val playlist = HlsPlaylistParser.parse(
            """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x000102030405060708090A0B0C0D0E0F
            #EXTINF:4,
            a.ts
            #EXTINF:4,
            b.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4,
            c.ts
            """.trimIndent(),
            "https://cdn.example/x/index.m3u8",
        )

        assertEquals("AES-128", playlist.segments[0].keyMethod)
        assertEquals("https://cdn.example/x/key.bin", playlist.segments[0].keyUrl)
        assertEquals("000102030405060708090A0B0C0D0E0F", playlist.segments[0].keyIvHex)
        assertEquals("AES-128", playlist.segments[1].keyMethod)
        assertNull(playlist.segments[2].keyMethod)
    }

    @Test
    fun `a quoted key uri may contain commas`() {
        val playlist = HlsPlaylistParser.parse(
            """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://k.example/key?a=1,2&b=3"
            #EXTINF:4,
            a.ts
            """.trimIndent(),
            "https://cdn.example/index.m3u8",
        )
        assertEquals("https://k.example/key?a=1,2&b=3", playlist.segments[0].keyUrl)
    }

    @Test
    fun `recognises master playlists and lists their variants`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720
            720p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=300000
            /low/index.m3u8
        """.trimIndent()

        assertTrue(HlsPlaylistParser.isMaster(master))
        assertEquals(
            listOf(
                "https://cdn.example/show/720p/index.m3u8",
                "https://cdn.example/low/index.m3u8",
            ),
            HlsPlaylistParser.variantUris(master, "https://cdn.example/show/master.m3u8"),
        )
    }
}
