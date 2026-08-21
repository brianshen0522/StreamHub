package com.streamhub.mobile.player

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.streamhub.mobile.AppContainer
import com.streamhub.mobile.PlaybackRequest
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    container: AppContainer,
    request: PlaybackRequest,
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val api = container.api()

    val player = remember(request.directUrl) {
        // Media requests go through the same authenticated client the rest of the
        // app uses, so they carry the bearer token — including the second request
        // a master playlist causes — and a token that lapses mid-playback is
        // renewed by the same single-flight refresher rather than ending the
        // stream.
        val dataSourceFactory = OkHttpDataSource.Factory(api.authenticatedClient)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                // The cleaned manifest, not the raw source: ads are already cut
                // and segments point straight at the CDN.
                //
                // The MIME type is not optional here. ExoPlayer infers the format
                // from the URI's extension, and this URL ends in a query string
                // rather than .m3u8, so without the hint it picks the progressive
                // source, fails to sniff the playlist with file extractors and
                // dies with UnrecognizedInputFormatException.
                setMediaItem(
                    MediaItem.Builder()
                        .setUri(api.manifestUrl(request.directUrl))
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()
                )
                if (request.resumeAtSeconds > 0) {
                    seekTo(request.resumeAtSeconds * 1000L)
                }
                prepare()
                playWhenReady = true
            }
    }

    // Report while playing, and once more on the way out so the shelf is right
    // even if the last tick has not come round yet.
    LaunchedEffect(player) {
        while (true) {
            delay(1_000)
            if (player.isPlaying) {
                viewModel.report(player.currentPosition, player.duration.coerceAtLeast(0), "progress")
            }
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    viewModel.report(player.currentPosition, player.duration.coerceAtLeast(0), "pause")
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    // Finished means finished: report the end position so the
                    // server marks it complete and the next episode is offered.
                    val duration = player.duration.coerceAtLeast(0)
                    viewModel.report(duration, duration, "ended")
                }
            }
        }
        player.addListener(listener)

        onDispose {
            viewModel.report(player.currentPosition, player.duration.coerceAtLeast(0), "pause")
            player.removeListener(listener)
            player.release()
        }
    }

    BackHandler { onBack() }

    // Black behind the video, not the theme surface: a letterboxed 16:9 frame
    // floating on a pale background looks broken rather than deliberate.
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    setKeepScreenOn(true)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
