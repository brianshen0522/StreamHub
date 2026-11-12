package com.streamhub.tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.OkHttpClient

/**
 * [PlaybackEngine] over Media3 ExoPlayer: the Android counterpart of the
 * shim's `avplay` backend (`player-shim.js:84-176`).
 *
 * Every request goes through [client] — `StreamHubApi.authenticatedClient`,
 * which puts the bearer token on this server's requests only (the manifest,
 * a master playlist's variants, the relay) and sends CDN segment requests
 * bare.
 *
 * Events are posted, not delivered inline, and stamped with the [open]
 * generation they belong to; a stamp that is no longer current is dropped
 * (`avplay.rs` `is_current`). That is what keeps source A's late error away
 * from source B's recovery ladder.
 */
@OptIn(UnstableApi::class)
class ExoEngine(context: Context, client: OkHttpClient) : PlaybackEngine {

    val player: ExoPlayer = ExoPlayer.Builder(context)
        // Media reads keep OkHttp's 10 s read timeout: a stalled segment must
        // fail into the ladder, whatever the API client's longer timeout is.
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                OkHttpDataSource.Factory(client.newBuilder().readTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()),
            ),
        )
        // Scraped CDNs stall and vanish mid-episode: buffer well ahead, but
        // cap the bytes — ExoPlayer buffers on the Java heap, and a
        // television's heap can be 48 MB (the old player's OOM lesson).
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(50_000, 180_000, 2_500, 5_000)
                .setBackBuffer(60_000, true)
                .setTargetBufferBytes(
                    (Runtime.getRuntime().maxMemory() / 4).coerceIn(8L * 1024 * 1024, 64L * 1024 * 1024).toInt(),
                )
                .setPrioritizeTimeOverSizeThresholds(false)
                .build(),
        )
        .build()

    private val main = Handler(Looper.getMainLooper())
    private var listener: PlaybackEngine.Listener? = null
    private var gen = 0L
    private var state = PlayerState.IDLE
    private var wantPaused = false
    private var buffering = false
    private var suspended = false
    private var resumeAfterSuspend = false

    /** `oncurrentplaytime`: AVPlay reports the position about every 500 ms while playing. */
    private val ticker = object : Runnable {
        override fun run() {
            if (player.isPlaying) emit { it.onTime(positionMs()) }
            main.postDelayed(this, TIME_TICK_MS)
        }
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> setBuffering(true)
                    Player.STATE_READY -> {
                        setBuffering(false)
                        // prepareAsync → (seekTo start) → play, or paused when asked meanwhile.
                        if (state == PlayerState.LOADING) setState(if (wantPaused) PlayerState.PAUSED else PlayerState.PLAYING)
                    }
                    Player.STATE_ENDED -> {
                        if (state == PlayerState.LOADING || state == PlayerState.PLAYING || state == PlayerState.PAUSED) {
                            setState(PlayerState.ENDED)
                            emit { it.onEnded() }
                        }
                    }
                    else -> Unit
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // The platform pausing us (audio focus, a headset unplugged):
                // the shim's state follows, so the page shows paused.
                if (suspended || reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) return
                if (state == PlayerState.PLAYING && !playWhenReady) setState(PlayerState.PAUSED)
                else if (state == PlayerState.PAUSED && playWhenReady) setState(PlayerState.PLAYING)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (state == PlayerState.IDLE) return
                state = PlayerState.ERROR
                val msg = error.errorCodeName
                emit { it.onState(PlayerState.ERROR) }
                emit { it.onError(msg) }
            }
        })
        main.postDelayed(ticker, TIME_TICK_MS)
    }

    override fun setListener(listener: PlaybackEngine.Listener?) {
        this.listener = listener
    }

    override fun open(url: String, startMs: Double) {
        gen += 1
        wantPaused = false
        suspended = false
        state = PlayerState.IDLE
        setState(PlayerState.LOADING)
        buffering = false
        setBuffering(true)
        player.stop()
        // The MIME type is not optional: these URLs end in a query string, not
        // .m3u8, and without the hint ExoPlayer picks a progressive source.
        val item = MediaItem.Builder()
            .setUri(url)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setMediaId(gen.toString())
            .build()
        player.setMediaItem(item, startMs.toLong().coerceAtLeast(0))
        player.playWhenReady = true
        player.prepare()
    }

    override fun play() {
        wantPaused = false
        if (state == PlayerState.LOADING) {
            player.playWhenReady = true
            return
        }
        player.play()
        setState(PlayerState.PLAYING)
    }

    override fun pause() {
        wantPaused = true
        if (state == PlayerState.LOADING) {
            // Recorded, not applied: the source comes up paused.
            player.playWhenReady = false
            return
        }
        player.pause()
        setState(PlayerState.PAUSED)
    }

    override fun seek(ms: Double) {
        if (state == PlayerState.IDLE) return
        setBuffering(true)
        player.seekTo(ms.toLong().coerceAtLeast(0))
        // A seek inside the buffer may never leave READY: clear the spinner
        // once ExoPlayer has taken it in.
        val g = gen
        main.post { if (g == gen && player.playbackState == Player.STATE_READY) setBuffering(false) }
    }

    override fun close() {
        gen += 1
        state = PlayerState.IDLE
        player.stop()
        player.clearMediaItems()
    }

    override fun positionMs(): Double =
        if (state == PlayerState.IDLE) 0.0 else player.currentPosition.coerceAtLeast(0).toDouble()

    override fun durationMs(): Double {
        if (state == PlayerState.IDLE) return 0.0
        val d = player.duration
        return if (d == C.TIME_UNSET || d <= 0) 0.0 else d.toDouble()
    }

    override fun backendName(): String = "exoplayer"

    override fun suspend() {
        if (suspended) return
        suspended = true
        resumeAfterSuspend = player.playWhenReady
        player.playWhenReady = false
    }

    override fun restore() {
        if (!suspended) return
        suspended = false
        if (resumeAfterSuspend) player.playWhenReady = true
    }

    override fun release() {
        gen += 1
        listener = null
        main.removeCallbacksAndMessages(null)
        player.release()
    }

    private fun setState(next: String) {
        if (state == next) return
        state = next
        emit { it.onState(next) }
    }

    private fun setBuffering(b: Boolean) {
        if (buffering == b) return
        buffering = b
        emit { it.onBuffering(b) }
    }

    private fun emit(event: (PlaybackEngine.Listener) -> Unit) {
        val stamp = gen
        main.post { if (stamp == gen) listener?.let(event) }
    }

    private companion object {
        const val TIME_TICK_MS = 500L
    }
}
