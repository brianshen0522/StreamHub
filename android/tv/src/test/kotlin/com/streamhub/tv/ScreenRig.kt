package com.streamhub.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import com.github.takahirom.roborazzi.captureRoboImage
import com.streamhub.tv.focus.TvFocusEngine
import com.streamhub.tv.nav.TvNav
import com.streamhub.tv.sfx.SfxControl
import com.streamhub.tv.ui.theme.LocalTvFontFamily

/**
 * One screen under test: the shell, the engine on the test clock, a
 * [FakeServer], and helpers to press keys and wait for the IO the screens do
 * (`:core` calls run on Dispatchers.IO, outside the test clock).
 */
class ScreenRig(val compose: ComposeContentTestRule, val font: Boolean = false) {
    val server = FakeServer()
    val nav = TvNav()
    val sounds = RecordingSounds()
    val sfx = SfxControl.Silent()
    val engine = TvFocusEngine(nav, sounds, clock = { compose.mainClock.currentTime })
    var exited = false

    init {
        engine.onExitApp = { exited = true }
    }

    fun setContent(content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            if (font) {
                CompositionLocalProvider(LocalTvFontFamily provides notoSansCjk()) {
                    TvShell(engine, nav, sfx) { content() }
                }
            } else {
                TvShell(engine, nav, sfx) { content() }
            }
        }
    }

    /**
     * Advance the test clock by [ms] in small steps, letting IO answers land
     * between them. Steps are whole frames (16 ms): `advanceTimeBy` rounds up
     * to a frame, so a 50 ms step would really be 64 ms.
     */
    fun settle(ms: Long = 500) {
        var left = ms
        while (left > 0) {
            val step = minOf(left, 48)
            compose.mainClock.advanceTimeBy(step)
            compose.waitForIdle()
            Thread.sleep(2)
            left -= step
        }
        compose.waitForIdle()
    }

    /** Settle until [cond] holds (checked on the UI thread), or fail after [maxMs] of test time. */
    fun settleUntil(maxMs: Long = 10_000, cond: () -> Boolean) {
        var waited = 0L
        while (!compose.runOnIdle(cond)) {
            if (waited >= maxMs) throw AssertionError("condition not met after ${maxMs}ms")
            settle(48)
            Thread.sleep(5)
            waited += 48
        }
    }

    fun press(code: Int, holdMs: Long = 0, after: Long = 500) {
        compose.runOnIdle { engine.keyDown(code, false) }
        if (holdMs > 0) settle(holdMs)
        compose.runOnIdle { engine.keyUp(code) }
        settle(after)
    }

    fun right() = press(KeyEvent.KEYCODE_DPAD_RIGHT)
    fun left() = press(KeyEvent.KEYCODE_DPAD_LEFT)
    fun up() = press(KeyEvent.KEYCODE_DPAD_UP)
    fun down() = press(KeyEvent.KEYCODE_DPAD_DOWN)
    fun ok() = press(KeyEvent.KEYCODE_DPAD_CENTER)
    fun back() = press(KeyEvent.KEYCODE_BACK, after = 800)

    /** The focused element's focus id, else its action. */
    val focused: String? get() = compose.runOnIdle { engine.focused?.let { it.focusId ?: it.action } }

    fun capture(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    companion object {
        /**
         * Posters without a network: every image request answers with a
         * gradient bitmap whose colours come from the URL, so cards and the
         * backdrop render real pixels (and the backdrop's load-then-flip runs).
         */
        fun installFakePosters() {
            SingletonImageLoader.setUnsafe { context ->
                ImageLoader.Builder(context)
                    .components { add(FakePosterInterceptor) }
                    .build()
            }
        }
    }
}

private object FakePosterInterceptor : Interceptor {
    private val palette = intArrayOf(
        0xFF8E1B2A.toInt(), 0xFF1B5E8E.toInt(), 0xFF2E7D32.toInt(), 0xFF6A1B9A.toInt(),
        0xFFB26A00.toInt(), 0xFF00695C.toInt(), 0xFF37474F.toInt(), 0xFFAD1457.toInt(),
    )

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val key = chain.request.data.toString()
        val h = key.hashCode()
        val a = palette[Math.floorMod(h, palette.size)]
        val b = palette[Math.floorMod(h / 7, palette.size)]
        val bitmap = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, 200f, 300f, Paint().apply { shader = LinearGradient(0f, 0f, 200f, 300f, a, b, Shader.TileMode.CLAMP) })
        canvas.drawCircle(100f, 120f, 60f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF })
        canvas.drawRect(30f, 220f, 170f, 250f, Paint().apply { color = 0x55000000 })
        return SuccessResult(image = bitmap.asImage(), request = chain.request, dataSource = DataSource.MEMORY)
    }
}

/** Typical library JSON for Home tests. */
object Library {
    fun cont(n: Int = 4) = """{"items":[${(1..n).joinToString(",") { contRow(it) }}]}"""

    fun contRow(i: Int) = when (i) {
        1 -> """{"id":"p1","providerKey":"777tv","mediaType":"tv","title":"黑暗榮耀","posterUrl":"https://img.test/glory.jpg","itemUrl":"https://777tv.test/glory","seasonUrl":"https://777tv.test/glory/s1","episodeLabel":"01","durationSeconds":3000,"positionSeconds":1500,"progressPercent":50,"nextUp":false}"""
        2 -> """{"id":"p2","providerKey":"chinaq","mediaType":"tv","title":"蘭香如故","posterUrl":"https://img.test/lan.jpg","itemUrl":"https://chinaq.test/lan","episodeLabel":"第05集","durationSeconds":2700,"positionSeconds":1260,"progressPercent":46.6,"nextUp":false}"""
        3 -> """{"id":"p3","providerKey":"movieffm","mediaType":"tv","title":"瑞克和莫蒂","posterUrl":"https://img.test/rick.jpg","itemUrl":"https://movieffm.test/rick","episodeLabel":"3","durationSeconds":1320,"positionSeconds":1320,"progressPercent":100,"nextUp":true}"""
        else -> """{"id":"p$i","providerKey":"dramasq","mediaType":"tv","title":"天橋上的魔術師 $i","posterUrl":"","itemUrl":"https://dramasq.test/$i","episodeLabel":"第2集","durationSeconds":3000,"positionSeconds":600,"progressPercent":20,"isUnavailable":true,"unavailableReason":"not-found"}"""
    }

    fun favs(n: Int = 5) = """{"favorites":[${(1..n).joinToString(",") { favRow(it) }}]}"""

    fun favRow(i: Int) = when (i) {
        1 -> """{"id":"f1","providerKey":"movieffm","mediaType":"tv","title":"俗女養成記","posterUrl":"https://img.test/su.jpg","itemUrl":"https://movieffm.test/su","seasonLabel":"第2季","episodeLabel":""}"""
        2 -> """{"id":"f2","providerKey":"777tv","mediaType":"movie","title":"做工的人","posterUrl":"https://img.test/work.jpg","itemUrl":"https://777tv.test/work"}"""
        3 -> """{"id":"f3","providerKey":"chinaq","mediaType":"tv","title":"不夠善良的我們","posterUrl":"https://img.test/good.jpg","itemUrl":"https://chinaq.test/good","isUnavailable":true,"unavailableReason":"no-episodes"}"""
        else -> """{"id":"f$i","providerKey":"dramasq","mediaType":"tv","title":"台北女子圖鑑 $i","posterUrl":"https://img.test/tp$i.jpg","itemUrl":"https://dramasq.test/tp$i","episodeLabel":"12"}"""
    }

    fun hist(n: Int = 6) = """{"history":[${(1..n).joinToString(",") { histRow(it) }}]}"""

    fun histRow(i: Int) =
        """{"id":"h$i","providerKey":"777tv","mediaType":"tv","title":"想見你 $i","posterUrl":"https://img.test/see$i.jpg","itemUrl":"https://777tv.test/see$i","episodeLabel":"$i","positionSeconds":${i * 300},"durationSeconds":3000,"watchedAt":"2026-11-0${(i % 9) + 1}T10:00:00Z"}"""

    const val PROVIDERS =
        """{"providers":[{"key":"777tv","name":"777TV","available":true},{"key":"movieffm","name":"MovieFFM","available":true},{"key":"chinaq","name":"chinaqtw","available":true},{"key":"dramasq","name":"DramasQ","available":true}]}"""

    fun FakeServer.library(cont: String = cont(), favs: String = favs(), hist: String = hist()) {
        json("GET", "/me/continue-watching", cont)
        json("GET", "/me/favorites", favs)
        json("GET", "/me/history", hist)
        json("GET", "/me/providers", PROVIDERS)
        json("DELETE", "/me/progress", """{"ok":true}""")
        for (i in 1..9) json("DELETE", "/me/favorites/f$i", """{"ok":true}""")
        json(
            "POST", "/me/favorites",
            """{"favorite":{"id":"new","providerKey":"777tv","title":"x","itemUrl":"u"}}""",
            status = 201,
        )
        json("POST", "/auth/logout", """{"ok":true}""")
    }
}
